package com.tungsten.fclauncher.utils;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

public class FCLPath {

    /**
     * JUL logger，名字与 `com.tungsten.fclcore.util.Logging.LOG` **相同**（`Logger.getLogger("FCL")`
     * 按名字返回同一实例），因此这里写的 WARNING 会落进同一个 `fcl.log`，无需跨包依赖。
     */
    private static final Logger LOG = Logger.getLogger("FCL");

    /**
     * [R31/s10-eh-01] 本次 {@link #loadPaths} 创建失败的目录（空 = 全部就绪）。
     * 由 {@link #loadPaths} 的返回值交给调用方，构成「目录已就绪 / 创建失败」的判据 ——
     * 原实现丢弃 {@code mkdirs()} 的返回值，调用方无从区分这两种状态。
     */
    private static final List<String> FAILED_DIRS = Collections.synchronizedList(new ArrayList<String>());

    public static String NATIVE_LIB_DIR;

    public static final String LATEST_GAME_LOG = "latest_game.log";

    public static String LOG_DIR;
    public static String CACHE_DIR;

    public static String RUNTIME_DIR;
    public static String MOD_RUNTIME_DIR;
    public static String JAVA_8_PATH;
    public static String JAVA_17_PATH;
    public static String JAVA_21_PATH;
    public static String JAVA_25_PATH;
    public static String JAVA_PATH;
    public static String JNA_PATH;
    public static String LWJGL_DIR;
    public static String CACIOCAVALLO_8_DIR;
    public static String CACIOCAVALLO_17_DIR;

    public static String FILES_DIR;
    public static String PLUGIN_DIR;
    public static String BACKGROUND_DIR;
    public static String SKIN_DIR;
    public static String CONTROLLER_DIR;
    public static String SHARE_DIR;

    public static String PRIVATE_COMMON_DIR;
    /**
     * 共享 .minecraft 目录。原为 `Environment.getExternalStorageDirectory() + "/FCL/.minecraft"`，
     * 但本 App 已不申请任何存储权限（见 {@link #loadPaths} 里 [M-02] 的说明），targetSdk 34 下
     * 该路径确定性不可写 —— 现与 {@link #PRIVATE_COMMON_DIR} 同样落在 App 专属的外部存储目录。
     * 真正的「跨 App 共享」需要 MANAGE_EXTERNAL_STORAGE，本 App 刻意不申请。
     * ★ 原先这里是**字段初始化器**，会在类加载期调 `Environment.getExternalStorageDirectory()`，
     *   让整个类在没有 Android 运行时的环境里（单测）连加载都失败。现改到 {@link #loadPaths} 内赋值。
     */
    public static String SHARED_COMMON_DIR;

    public static String AUTHLIB_INJECTOR_PATH;
    public static String LIB_PATCHER_PATH;
    public static String MIO_LAUNCH_WRAPPER;
    public static String LT_BACKGROUND_PATH;
    public static String DK_BACKGROUND_PATH;
    public static String LIVE_BACKGROUND_PATH;

    /**
     * 初始化全部路径并创建目录。
     *
     * @return 创建失败时的可读原因（成功为 null）。形态与
     *         {@code com.dsh.core.DshPaths.loadPaths} 一致 —— 调用方拿得到「目录已就绪」与
     *         「创建失败」的区分（[R31/s10-eh-01]；原实现返回 void，错误码在此永久丢失）。
     *         **不抛异常**：本方法在 `FCLApp.onCreate` 的冷启动路径上，抛 = 闪退循环。
     */
    public static String loadPaths(Context context) {
        FAILED_DIRS.clear();
        NATIVE_LIB_DIR = context.getApplicationInfo().nativeLibraryDir;

        // [M-02] 原为 /sdcard/FCL/log。存储权限已随本次变更移除（MANAGE_EXTERNAL_STORAGE /
        // READ|WRITE_EXTERNAL_STORAGE 全部删除），targetSdk 34 下该目录不可写，
        // Logging.start 的 catch(IOException) 会静默吞掉异常 —— fcl.log 永远建不出来，
        // 崩溃后无可取证文件；而 SplashActivity 的 KDoc 却声称"日志全在私有目录"，
        // 属文档级误导。迁到 App 私有目录，无需任何权限，随 App 卸载自动清理。
        LOG_DIR = context.getDir("log", 0).getAbsolutePath();
        CACHE_DIR = context.getCacheDir() + "/fclauncher";

        RUNTIME_DIR = context.getDir("runtime", 0).getAbsolutePath();
        JAVA_PATH = RUNTIME_DIR + "/java";
        JAVA_8_PATH = RUNTIME_DIR + "/java/jre8";
        JAVA_17_PATH = RUNTIME_DIR + "/java/jre17";
        JAVA_21_PATH = RUNTIME_DIR + "/java/jre21";
        JAVA_25_PATH = RUNTIME_DIR + "/java/jre25";
        JNA_PATH = RUNTIME_DIR + "/jna";
        LWJGL_DIR = RUNTIME_DIR + "/lwjgl";
        CACIOCAVALLO_8_DIR = RUNTIME_DIR + "/caciocavallo";
        CACIOCAVALLO_17_DIR = RUNTIME_DIR + "/caciocavallo17";

        MOD_RUNTIME_DIR = context.getDir("runtime_mod", 0).getAbsolutePath();

        FILES_DIR = context.getFilesDir().getAbsolutePath();
        PLUGIN_DIR = FILES_DIR + "/plugins";
        BACKGROUND_DIR = FILES_DIR + "/background";
        SKIN_DIR = FILES_DIR + "/skin";
        CONTROLLER_DIR = FILES_DIR + "/control";
        SHARE_DIR = FILES_DIR + "/share";
        // [R31/s10-eh-02] 原为 `Environment.getExternalStorageDirectory() + "/FCL/control|share"`。
        // 存储权限已删（同 :49-53 的 [M-02] 说明），外部存储根目录在 targetSdk 34 下确定性不可写，
        // 那两次 init 必然失败且无输出。两个目录在 dsh 分支**零读取点**（全仓 grep 仅剩本文件的
        // 声明/赋值/init 三处，属 MC 时代遗留），故改到 App 私有目录：创建成功即兑现注释里
        // 「目录已就绪」的承诺，也不再为两个没人读的路径制造静默失败。
        File externalFilesDir = context.getExternalFilesDir(null);
        if (externalFilesDir == null) {
            externalFilesDir = new File(Environment.getExternalStorageDirectory(), "Android/data/" + context.getPackageName() + "/files");
        }
        PRIVATE_COMMON_DIR = new File(externalFilesDir, ".minecraft").getAbsolutePath();
        // [R31/s10-eh-02] 与 PRIVATE_COMMON_DIR 同源、同卷、同样无权限需求（App 专属外部目录）。
        // 保留「共享」语义的字段名以免大范围改名，但不再指向 /sdcard/FCL/.minecraft 死路径。
        SHARED_COMMON_DIR = PRIVATE_COMMON_DIR;

        AUTHLIB_INJECTOR_PATH = PLUGIN_DIR + "/authlib-injector.jar";
        LIB_PATCHER_PATH = PLUGIN_DIR + "/MioLibPatcher.jar";
        MIO_LAUNCH_WRAPPER = PLUGIN_DIR + "/MioLaunchWrapper.jar";
        LT_BACKGROUND_PATH = BACKGROUND_DIR + "/lt.png";
        DK_BACKGROUND_PATH = BACKGROUND_DIR + "/dk.png";
        LIVE_BACKGROUND_PATH = BACKGROUND_DIR + "/live.mp4";

        init(LOG_DIR);
        init(CACHE_DIR);
        init(RUNTIME_DIR);
        init(MOD_RUNTIME_DIR);
        init(JAVA_8_PATH);
        init(JAVA_25_PATH);
        init(JAVA_17_PATH);
        init(JAVA_21_PATH);
        init(LWJGL_DIR);
        init(CACIOCAVALLO_8_DIR);
        init(CACIOCAVALLO_17_DIR);
        init(FILES_DIR);
        init(PLUGIN_DIR);
        init(BACKGROUND_DIR);
        init(SKIN_DIR);
        init(CONTROLLER_DIR);
        init(SHARE_DIR);
        init(PRIVATE_COMMON_DIR);
        init(SHARED_COMMON_DIR);
        return FAILED_DIRS.isEmpty() ? null : FAILED_DIRS.toString();
    }

    /**
     * 确保目录存在，失败**不再静默**（[R31/s10-eh-01]，评审 pat-16 合并 slice-04-eh-08）。
     *
     * 原实现 `if (!exists()) mkdirs();` 丢弃了 `mkdirs()` 的 boolean，且用 `exists()` 作判据 ——
     * 路径上蹲着一个同名**普通文件**时 `exists()` 为真，于是连 `mkdirs()` 都不调，方法照样静默返回。
     * 故障被推迟到第一次写文件，届时抛出的 FileNotFoundException 与根因（父目录不可写 / 空间耗尽）
     * 在调用栈上已隔多层，错误码永久丢失。
     *
     * 这里**不抛异常**：`loadPaths` 跑在 `FCLApp.onCreate` 的冷启动路径上，抛异常就是闪退循环。
     * 做法是让失败可诊断：登记到 {@link #FAILED_DIRS}（经 {@link #loadPaths} 的返回值交给调用方）
     * 并写一条 WARNING 到 logcat（与 `com.tungsten.fclcore.util.Logging` 同名 logger，日志文件
     * 就绪后同样收得到）。
     */
    private static void init(String path) {
        File dir = new File(path);
        // isDirectory() 而非 exists()：同名普通文件不算"就绪"。
        if (dir.isDirectory()) {
            return;
        }
        // mkdirs() 失败时再判一次 isDirectory()：并发/前一次已建好时不算失败。
        if (dir.mkdirs() || dir.isDirectory()) {
            return;
        }
        FAILED_DIRS.add(path);
        LOG.warning("FCLPath: 目录创建失败（后续写该目录会以 FileNotFoundException 暴露）: " + path);
    }

    public static File getLatestGameLog() {
        return new File(LOG_DIR, LATEST_GAME_LOG);
    }

}
