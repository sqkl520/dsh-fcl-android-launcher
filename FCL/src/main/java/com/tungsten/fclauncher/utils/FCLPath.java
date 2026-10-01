package com.tungsten.fclauncher.utils;

import android.content.Context;
import android.os.Environment;

import java.io.File;

public class FCLPath {

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
    public static String SHARED_COMMON_DIR = Environment.getExternalStorageDirectory().getAbsolutePath() + "/FCL/.minecraft";

    public static String AUTHLIB_INJECTOR_PATH;
    public static String LIB_PATCHER_PATH;
    public static String MIO_LAUNCH_WRAPPER;
    public static String LT_BACKGROUND_PATH;
    public static String DK_BACKGROUND_PATH;
    public static String LIVE_BACKGROUND_PATH;

    public static void loadPaths(Context context) {
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
        CONTROLLER_DIR = Environment.getExternalStorageDirectory().getAbsolutePath() + "/FCL/control";
        SHARE_DIR = Environment.getExternalStorageDirectory().getAbsolutePath() + "/FCL/share";
        File externalFilesDir = context.getExternalFilesDir(null);
        if (externalFilesDir == null) {
            externalFilesDir = new File(Environment.getExternalStorageDirectory(), "Android/data/" + context.getPackageName() + "/files");
        }
        PRIVATE_COMMON_DIR = new File(externalFilesDir, ".minecraft").getAbsolutePath();

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
    }

    private static void init(String path) {
        if (!new File(path).exists()) {
            new File(path).mkdirs();
        }
    }

    public static File getLatestGameLog() {
        return new File(LOG_DIR, LATEST_GAME_LOG);
    }

}
