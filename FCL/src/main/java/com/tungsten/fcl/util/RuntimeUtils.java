package com.tungsten.fcl.util;

import android.content.Context;
import android.system.Os;

import com.tungsten.fclcore.util.Logging;
import com.tungsten.fclcore.util.io.FileUtils;
import com.tungsten.fclcore.util.io.IOUtils;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * 资产解压工具。
 *
 * [外壳改造] 原 FCL 版本还包含 {@code installJna} / {@code installJava} / {@code patchJava}
 * （为 Minecraft 运行时装 JRE、JNA、打 freetype/jsound 补丁），那些已随 MC 一起移除。
 * 现在只保留 dsh 首启解压（{@link com.dsh.core.DshBootstrap}）用到的通用部分：
 * version 比对、assets 目录复制、tar.xz 解压（含符号链接处理）。
 */
public class RuntimeUtils {

    /**
     * 按 tar 头还原"可执行位"。
     *
     * 只在 tar 里带 x 位时动作（普通数据文件直接返回）。目标权限 = tar 里的 mode，另加 owner 的 x。
     */
    private static void restoreExecutableBit(File path, TarArchiveEntry entry) {
        int mode = entry.getMode();
        if ((mode & 0111) == 0) return;
        int target = (mode & 0777) | 0100;
        try {
            Os.chmod(path.getAbsolutePath(), target);
        } catch (Throwable e) {
            // Os.chmod 在非 Android 环境（单元测试）不可用：退回 Java API
            if (!path.setExecutable(true, false)) {
                Logging.LOG.log(Level.WARNING, "chmod failed: " + path + " (" + e.getMessage() + ")");
            }
        }
    }

    /**
     * 安装进度回调，回调运行在后台线程，实现方需自行切换到主线程刷新 UI。
     */
    public interface InstallListener {

        /**
         * 正在处理的文件（相对路径、压缩包内条目名等）。
         */
        void onUpdate(String detail);

        /**
         * 进入某个阶段，参数为 R.string 资源 id。
         */
        void onStage(int resId);
    }

    /**
     * 判断 targetDir 里的 version 文件是否与 assets（classpath）里的 srcDir/version 一致。
     *
     * ★ 本次修复的两个隐患：
     * 1. 原来对 asset 版本号做 `Long.parseLong(...)`，只支持**纯数字**版本。而本项目的
     *    `assets/dsh/rootfs/version` 是语义化字符串（`debian-bookworm-arm64-node22-dsh0.1.6-alpha.2-layout2`）——
     *    一旦 `getResourceAsStream` 在任何环境下解析成功（桌面/单测/未来换打包方式），
     *    `Long.parseLong` 会抛 `NumberFormatException`：在 `DshBootstrap.isReady()` 里被
     *    runCatching 吞掉变成\"永远不就绪\"，在 `install()` 里则直接把首启解压判为失败。
     *    现在改为\"字符串相等优先，纯数字时保持数值语义\"，两种版本号写法都能用。
     * 2. 原来把同一个 stream 打开了两次（第一次只判 null、第二次再读），第二次若返回 null
     *    会直接 NPE。现在只读一次。
     *
     * 语义保持：**资源读不到时返回 true**（视为\"无需解压\"）。Android 上 assets 不在 java
     * classpath 里，getResourceAsStream 拿不到属常态，因此调用方（DshBootstrap）必须自己
     * 用\"目标文件是否存在 / 内容是否可用\"兜底 —— 这一条已在 DshBootstrap 里落实。
     */
    public static boolean isLatest(String targetDir, String srcDir) throws IOException {
        final String assetVersion;
        try (InputStream stream = RuntimeUtils.class.getResourceAsStream(srcDir + "/version")) {
            if (stream == null) {
                return true;
            }
            assetVersion = IOUtils.readFullyAsString(stream).trim();
        }

        File targetFile = new File(targetDir + "/version");
        if (!targetFile.exists()) return false;
        String installedVersion = FileUtils.readText(targetFile).trim();
        if (installedVersion.isEmpty()) return false;
        if (installedVersion.equals(assetVersion)) return true;

        // 兼容历史行为：两边都是纯数字时按数值比较（"007" 与 "7" 视为同版本）
        try {
            return Long.parseLong(installedVersion) == Long.parseLong(assetVersion);
        } catch (NumberFormatException notNumeric) {
            return false;
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void install(Context context, String targetDir, String srcDir) throws IOException {
        install(context, targetDir, srcDir, null);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void install(Context context, String targetDir, String srcDir, InstallListener listener) throws IOException {
        FileUtils.deleteDirectory(new File(targetDir));
        new File(targetDir).mkdirs();
        copyAssets(context, srcDir, targetDir, listener);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void copyAssets(Context context, String src, String dest) throws IOException {
        copyAssets(context, src, dest, null);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void copyAssets(Context context, String src, String dest, InstallListener listener) throws IOException {
        int total = countAssetFiles(context, src);
        AtomicInteger done = new AtomicInteger();
        copyAssetsInternal(context, src, dest, src, total, done, listener);
    }

    private static int countAssetFiles(Context context, String path) throws IOException {
        String[] fileNames = context.getAssets().list(path);
        if (fileNames == null || fileNames.length == 0) {
            return 1;
        }
        int count = 0;
        for (String fileName : fileNames) {
            count += countAssetFiles(context, path.isEmpty() ? fileName : path + "/" + fileName);
        }
        return count;
    }

    private static void copyAssetsInternal(Context context, String src, String dest, String root, int total, AtomicInteger done, InstallListener listener) throws IOException {
        String[] fileNames = context.getAssets().list(src);
        if (fileNames != null && fileNames.length > 0) {
            File file = new File(dest);
            if (!file.exists())
                file.mkdirs();
            for (String fileName : fileNames) {
                copyAssetsInternal(context,
                        src.isEmpty() ? fileName : src + "/" + fileName,
                        dest + File.separator + fileName, root, total, done, listener);
            }
        } else {
            if (listener != null) {
                // 单文件直接复制时（src == root），相对路径取文件名
                String relative = src.equals(root) ? new File(src).getName() : src.substring(root.length() + 1);
                listener.onUpdate(relative + " (" + done.incrementAndGet() + "/" + total + ")");
            }
            File outFile = new File(dest);
            InputStream is = context.getAssets().open(src);
            FileOutputStream fos = new FileOutputStream(outFile);
            byte[] buffer = new byte[1024];
            int byteCount;
            while ((byteCount = is.read(buffer)) != -1) {
                fos.write(buffer, 0, byteCount);
            }
            fos.flush();
            is.close();
            fos.close();
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void uncompressTarXZ(final InputStream tarFileInputStream, final File dest) throws IOException {
        uncompressTarXZ(tarFileInputStream, dest, null);
    }

    /** 解压文件时的拷贝缓冲区：原来固定 1024 字节，解压 300MB+ 的 rootfs 时会多出几十万次系统调用 */
    private static final int EXTRACT_BUFFER_SIZE = 64 * 1024;

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void uncompressTarXZ(final InputStream tarFileInputStream, final File dest, final InstallListener listener) throws IOException {
        dest.mkdirs();
        TarArchiveInputStream tarIn = new TarArchiveInputStream(new XZCompressorInputStream(tarFileInputStream));
        byte[] buffer = new byte[EXTRACT_BUFFER_SIZE];
        TarArchiveEntry tarEntry = tarIn.getNextTarEntry();
        while (tarEntry != null) {
            if (listener != null && !tarEntry.isDirectory()) {
                listener.onUpdate(tarEntry.getName());
            }
            // [性能修复] 这里原来是：
            //     if (tarEntry.getSize() <= 20480) Thread.sleep(25);
            // rootfs 这类"几万个小文件"的包里，几乎每个条目都会命中，等于 25ms × N 的纯等待
            // （N 以万计 → 十几分钟）。它原本是想给 UI 留刷新机会，但那应该由进度回调自己节流：
            // 现在由调用方 DshBootstrap.listener 按时间节流（默认 ≤5 次/秒），解压本身全速跑。
            File destPath = new File(dest, tarEntry.getName());
            if (tarEntry.isSymbolicLink()) {
                Objects.requireNonNull(destPath.getParentFile()).mkdirs();
                try {
                    // [第十一轮 P1] 链接目标必须**原样**写入，不能把 `..` 改写成宿主解压目录。
                    // 原实现 `getLinkName().replace("..", dest.getAbsolutePath())` 来自 FCL 上游
                    // （为 JRE 资产写的），对本项目 rootfs 会造出断链，其中 `/opt/node22/bin/npm`
                    // 断链会让 probe.sh 的 npm 项 FAIL → 底座永远不就绪（详见 TarLinkPolicy）。
                    Os.symlink(com.dsh.core.TarLinkPolicy.symlinkTarget(tarEntry.getLinkName()),
                            new File(dest, tarEntry.getName()).getAbsolutePath());
                } catch (Throwable e) {
                    Logging.LOG.log(Level.WARNING, e.getMessage());
                }
            } else if (tarEntry.isLink()) {
                // [正确性修复] 硬链接条目：旧实现落到最后一个 else 分支，被当成"0 字节普通文件"
                // 创建出来（tar 里链接条目 size=0），于是 rootfs 内共享 inode 的可执行文件
                // （如 /usr/bin 下的一批命令）会"存在但跑不起来/内容为空"。
                // 优先建真硬链接；目标文件系统不支持时退化为复制目标内容。
                File linkTarget = new File(dest, tarEntry.getLinkName());
                Objects.requireNonNull(destPath.getParentFile()).mkdirs();
                try {
                    Os.link(linkTarget.getAbsolutePath(), destPath.getAbsolutePath());
                } catch (Throwable e) {
                    Logging.LOG.log(Level.WARNING, "hard link failed: " + e.getMessage());
                    try {
                        if (linkTarget.isFile()) {
                            Files.copy(linkTarget.toPath(), destPath.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException copyError) {
                        Logging.LOG.log(Level.WARNING, copyError.getMessage());
                    }
                }
            } else if (tarEntry.isDirectory()) {
                destPath.mkdirs();
                destPath.setExecutable(true);
            } else if (!destPath.exists() || destPath.length() != tarEntry.getSize()) {
                Objects.requireNonNull(destPath.getParentFile()).mkdirs();
                destPath.createNewFile();
                // try-with-resources：原来异常路径上 FileOutputStream 不会关闭（解压失败时句柄泄漏）
                try (FileOutputStream os = new FileOutputStream(destPath)) {
                    int byteCount;
                    while ((byteCount = tarIn.read(buffer)) != -1) {
                        os.write(buffer, 0, byteCount);
                    }
                }
            }
            // [正确性修复] 普通文件还原可执行位（tar 头里的 x 位）。
            // 原来只有目录被 setExecutable(true)，普通文件一律是 FileOutputStream 的默认权限
            // （0600/0644，无 x 位）—— 于是解压出来的 rootfs 里 /bin/sh、/usr/bin/env、node
            // **全都不可执行**，proot 第一次 execve 就 EACCES：表象是"rootfs 解压成功，但连
            // /bin/sh 都跑不起来"（DshBootstrap 的自检探针会在这里失败）。
            // 注意只还原 x 位，不还原读/写位：App 是这些文件唯一的用户，保持 owner 可写，
            // 避免 tar 里 0444/0555 的文件在"重复解压"时写不进去。
            if (!tarEntry.isDirectory() && !tarEntry.isSymbolicLink() && !tarEntry.isLink()) {
                restoreExecutableBit(destPath, tarEntry);
            }
            tarEntry = tarIn.getNextTarEntry();
        }
        tarIn.close();
    }

}
