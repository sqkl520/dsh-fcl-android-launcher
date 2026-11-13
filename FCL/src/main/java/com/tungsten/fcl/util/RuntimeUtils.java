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
    private static boolean restoreExecutableBit(File path, TarArchiveEntry entry) {
        int mode = entry.getMode();
        if ((mode & 0111) == 0) return true;
        int target = (mode & 0777) | 0100;
        try {
            Os.chmod(path.getAbsolutePath(), target);
            return true;
        } catch (Throwable e) {
            // Os.chmod 在非 Android 环境（单元测试）不可用：退回 Java API
            if (path.setExecutable(true, false)) return true;
            // [round31/s10-eh-05] 返回 false 交给调用方计数，而不是"只写一行 WARNING 就当没事"。
            // 第二个参数传异常对象：原来只取 e.getMessage()（为 null 时日志退化成一行 "null"，
            // 既没有异常类型也没有堆栈，排障时无法区分"权限被拒"和"文件系统不支持"）。
            Logging.LOG.log(Level.WARNING, "chmod failed: " + path, e);
            return false;
        }
    }

    /**
     * 解压一轮的**子项失败汇总**（round31 / s10-eh-05）。
     *
     * 为什么需要它：{@code uncompressTarXZ} 对"符号链接建不出来 / 可执行位没还原 / 硬链接建不出来
     * 且退化复制也失败"这几类子项失败一直是 `catch (Throwable) { WARNING }` 然后继续下一条。
     * 循环能跑完、方法正常返回、调用方只看到"解压成功"——而 `:245-251` 的注释自己写着这类失败
     * 的后果是"proot 第一次 execve 就 EACCES，底座永远不就绪"。
     *
     * 容错策略**不改变**（仍然尽量解压完，不因单个条目中断整轮），改的是"失败可见且可判"：
     * 逐项计数 + 首个失败摘要，由方法返回；调用方据此区分「全部成功」与「部分失败」。
     *
     * 两个计数分开是因为**后果不同**：
     * - 链接失败 / 可执行位失败 ⇒ 该条目在这个环境里用不了 ⇒ 算失败（{@link #hasFailures()}）。
     * - 硬链接退化为复制 ⇒ 内容仍然对，只是丢了 inode 共享 ⇒ 单独计数、**不计入失败**。
     *   这条分级很重要：不是所有目标文件系统都支持硬链接，"退化即失败"会把一条正常的
     *   降级路径变成假失败。
     */
    public static final class ExtractReport {
        private int failedLinks;
        private int failedExecutableBits;
        private int degradedHardLinks;
        private String firstFailure;

        private void record(String detail) {
            if (firstFailure == null) firstFailure = detail;
        }

        private void recordLinkFailure(String detail) {
            failedLinks++;
            record(detail);
        }

        private void recordExecutableBitFailure(String detail) {
            failedExecutableBits++;
            record(detail);
        }

        private void recordHardLinkDegraded(String detail) {
            degradedHardLinks++;
            record(detail);
        }

        /** 符号链接 / 硬链接**完全没建成**的条目数（不含退化复制） */
        public int getFailedLinks() {
            return failedLinks;
        }

        /** 可执行位还原失败的条目数 */
        public int getFailedExecutableBits() {
            return failedExecutableBits;
        }

        /**
         * 硬链接退化为复制的条目数：内容正确、inode 共享丢失。
         * 不计入 {@link #getFailureCount()} —— 这是降级而非失败。
         */
        public int getDegradedHardLinks() {
            return degradedHardLinks;
        }

        /** 子项**失败**总数；0 表示整轮没有失败（退化复制不算失败） */
        public int getFailureCount() {
            return failedLinks + failedExecutableBits;
        }

        /** 「全部成功」与「部分失败」的唯一判据 —— 调用方用它决定是否可以认为底座就绪 */
        public boolean hasFailures() {
            return getFailureCount() > 0;
        }

        /** 首个异常事件的摘要（含条目名与异常类型）；无任何事件时为 null */
        public String getFirstFailure() {
            return firstFailure;
        }

        public String describe() {
            if (!hasFailures() && degradedHardLinks == 0) return "all entries applied";
            StringBuilder sb = new StringBuilder();
            if (hasFailures()) {
                sb.append(getFailureCount()).append(" entr")
                        .append(getFailureCount() == 1 ? "y" : "ies").append(" failed (links=")
                        .append(failedLinks).append(", executable-bit=").append(failedExecutableBits).append(')');
            } else {
                sb.append("no failures");
            }
            if (degradedHardLinks > 0) {
                sb.append(", ").append(degradedHardLinks).append(" hard link(s) degraded to copy (inode sharing lost)");
            }
            if (firstFailure != null) sb.append(", first: ").append(firstFailure);
            return sb.toString();
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
            copyAssetStream(context.getAssets().open(src), outFile);
        }
    }

    /**
     * [round31/s10-eh-04] 把一个输入流的内容落到 {@code dest}，**失败时不留半成品、不泄漏流**。
     *
     * 独立成包级方法是为了让"异常路径真的会关流、真的不留下看起来完整的文件"这件事
     * 可以被 JVM 单测直接驱动（`Context.getAssets()` 在单测里拿不到，原来整段逻辑
     * 只能靠读代码判断）。
     *
     * 语义：
     * - 成功：dest 内容 == is 的全部内容，且目录下不留 `<dest>.tmp`。
     * - 失败：`<dest>.tmp` 被删除，dest **要么保持原样（调用前就已存在时）要么不存在**，
     *   异常原样抛出（调用方链路上 `copyAssets`/`install` 都声明 `throws IOException`）。
     *
     * 流的所有权：{@code is} 由本方法关闭（try-with-resources），{@code dest} 的输出流同理。
     * 调用方再用一层 try-with-resources 包 {@code is} 是安全的（重复 close 是幂等的）。
     */
    static void copyAssetStream(InputStream is, File dest) throws IOException {
        if (is == null) throw new IOException("asset stream is null: " + dest);
        // 半成品问题的根因：FileOutputStream 一构造就把目标文件**截断创建**，写到一半失败会留下
        // 0 字节或半截文件，而 isLatest 只凭 version 文件判断，半成品不会被识别成"需重解压"。
        // 改成"先写 <dest>.tmp、成功后 rename"：失败时删掉 .tmp，dest 上要么是上一次的完整
        // 文件、要么不存在 —— 不存在才是"看起来完整"的反面，也才有下一轮重解压的机会。
        File tmpFile = new File(dest.getPath() + ".tmp");
        try (InputStream in = is; FileOutputStream fos = new FileOutputStream(tmpFile)) {
            byte[] buffer = new byte[1024];
            int byteCount;
            while ((byteCount = in.read(buffer)) != -1) {
                fos.write(buffer, 0, byteCount);
            }
            fos.flush();
        } catch (IOException | RuntimeException e) {
            // 失败必须可观测，且不得留下半成品：删掉 .tmp 后原样抛出。
            tmpFile.delete();
            throw e;
        }
        try {
            // 成功后原子替换：REPLACE_EXISTING 在 POSIX 上就是 rename(2) 的覆盖语义，
            // 在 Windows 上也由 Files.move 内部处理（比"先删目标再 renameTo"少一个丢失窗口：
            // 那条路径上"删掉了旧文件、改名又失败"会让两个都没有）。
            // minSdk 26 起 java.nio.file.Files 可用（本文件已 import 并在别处使用）。
            Files.move(tmpFile.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            // rename 失败（跨文件系统 / 目标被占用）：删掉 .tmp，不留下无标记的半成品。
            tmpFile.delete();
            throw e;
        }
    }

    /**
     * 解压 tar.xz 到 dest。
     *
     * [round31/s10-eh-05] 返回本轮的子项失败汇总（{@link ExtractReport}），调用方据此区分
     * 「全部成功」与「部分失败」。容错策略未变：单个条目失败不中断整轮，仍尽量解压完。
     *
     * 为什么改返回值而不是抛异常：rootfs 里 1049 个符号链接 + 2 个硬链接，某一条建不出来
     * （SELinux 拒绝 / 目标 fs 不支持）时**中断整轮**会让一个条目的问题升级成"连旧底座都丢掉"。
     * 但"不中断"不等于"可以当没发生"——原实现两者混为一谈，这是本条缺陷的本质。
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static ExtractReport uncompressTarXZ(final InputStream tarFileInputStream, final File dest) throws IOException {
        return uncompressTarXZ(tarFileInputStream, dest, null);
    }

    /** 解压文件时的拷贝缓冲区：原来固定 1024 字节，解压 300MB+ 的 rootfs 时会多出几十万次系统调用 */
    private static final int EXTRACT_BUFFER_SIZE = 64 * 1024;

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static ExtractReport uncompressTarXZ(final InputStream tarFileInputStream, final File dest,
                                                final InstallListener listener) throws IOException {
        dest.mkdirs();
        TarArchiveInputStream tarIn = new TarArchiveInputStream(new XZCompressorInputStream(tarFileInputStream));
        ExtractReport report = new ExtractReport();
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
                    // [round31/s10-eh-05] 不再只写一行 WARNING 就当没事：计数 + 摘要返回给调用方。
                    // 第二个参数传异常本身（原来只取 e.getMessage()，为 null 时日志退化成一行 "null"，
                    // 既无异常类型也无堆栈）。
                    Logging.LOG.log(Level.WARNING, "symlink failed: " + tarEntry.getName(), e);
                    report.recordLinkFailure("symlink " + tarEntry.getName() + " (" + e + ")");
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
                    Logging.LOG.log(Level.WARNING, "hard link failed: " + tarEntry.getName(), e);
                    // 退化路径：复制目标内容。复制也失败才算这个条目失败 —— 但退化本身也要记一笔，
                    // 因为它意味着"共享 inode"的语义没保住（内容可读，但改一处不会同步另一处）。
                    try {
                        if (linkTarget.isFile()) {
                            Files.copy(linkTarget.toPath(), destPath.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            // 退化成功：内容对、inode 共享丢。**不算失败**，但要可见 ——
                            // "改一处不同步另一处"是真实的行为差异，不能和"全部成功"同级。
                            report.recordHardLinkDegraded("hard link " + tarEntry.getName()
                                    + " degraded to copy, inode sharing lost (" + e + ")");
                        } else {
                            report.recordLinkFailure("hard link " + tarEntry.getName()
                                    + " target missing: " + linkTarget + " (" + e + ")");
                        }
                    } catch (IOException copyError) {
                        Logging.LOG.log(Level.WARNING, "hard link fallback copy failed: " + tarEntry.getName(), copyError);
                        report.recordLinkFailure("hard link " + tarEntry.getName() + " (" + copyError + ")");
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
                if (!restoreExecutableBit(destPath, tarEntry)) {
                    // [round31/s10-eh-05] 这条失败的后果正是上面注释写的"连 /bin/sh 都跑不起来"，
                    // 不能只当日志噪音：计数进报告。
                    report.recordExecutableBitFailure("exec-bit " + tarEntry.getName());
                }
            }
            tarEntry = tarIn.getNextTarEntry();
        }
        tarIn.close();
        if (report.hasFailures() || report.getDegradedHardLinks() > 0) {
            // 整轮结束后留一条**汇总**（原来只有逐条 WARNING，没有任何"本轮共失败 N 项"的收口）。
            // 失败与退化用不同级别：失败是 WARNING，纯退化是 INFO（内容仍然正确）。
            Logging.LOG.log(report.hasFailures() ? Level.WARNING : Level.INFO,
                    "uncompressTarXZ finished: " + report.describe());
        }
        return report;
    }

}
