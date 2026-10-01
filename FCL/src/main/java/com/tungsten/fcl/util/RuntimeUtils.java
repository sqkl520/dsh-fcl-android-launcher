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

    public static boolean isLatest(String targetDir, String srcDir) throws IOException {
        File targetFile = new File(targetDir + "/version");
        try (InputStream stream = RuntimeUtils.class.getResourceAsStream(srcDir + "/version")) {
            if (stream == null) {
                return true;
            }
        }
        if (!targetFile.exists()) return false;
        long version = Long.parseLong(IOUtils.readFullyAsString(RuntimeUtils.class.getResourceAsStream(srcDir + "/version")).trim());
        String installedVersion = FileUtils.readText(targetFile).trim();
        if (installedVersion.isEmpty()) return false;
        return targetFile.exists() && Long.parseLong(installedVersion) == version;
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

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void uncompressTarXZ(final InputStream tarFileInputStream, final File dest, final InstallListener listener) throws IOException {
        dest.mkdirs();
        TarArchiveInputStream tarIn = new TarArchiveInputStream(new XZCompressorInputStream(tarFileInputStream));
        TarArchiveEntry tarEntry = tarIn.getNextTarEntry();
        while (tarEntry != null) {
            if (listener != null && !tarEntry.isDirectory()) {
                listener.onUpdate(tarEntry.getName());
            }
            if (tarEntry.getSize() <= 20480) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException ignored) {

                }
            }
            File destPath = new File(dest, tarEntry.getName());
            if (tarEntry.isSymbolicLink()) {
                Objects.requireNonNull(destPath.getParentFile()).mkdirs();
                try {
                    Os.symlink(tarEntry.getLinkName().replace("..", dest.getAbsolutePath()), new File(dest, tarEntry.getName()).getAbsolutePath());
                } catch (Throwable e) {
                    Logging.LOG.log(Level.WARNING, e.getMessage());
                }
            } else if (tarEntry.isDirectory()) {
                destPath.mkdirs();
                destPath.setExecutable(true);
            } else if (!destPath.exists() || destPath.length() != tarEntry.getSize()) {
                Objects.requireNonNull(destPath.getParentFile()).mkdirs();
                destPath.createNewFile();
                FileOutputStream os = new FileOutputStream(destPath);
                byte[] buffer = new byte[1024];
                int byteCount;
                while ((byteCount = tarIn.read(buffer)) != -1) {
                    os.write(buffer, 0, byteCount);
                }
                os.close();
            }
            tarEntry = tarIn.getNextTarEntry();
        }
        tarIn.close();
    }

}
