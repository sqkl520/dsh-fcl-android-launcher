package com.tungsten.fcl;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.StrictMode;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import java.lang.ref.WeakReference;

public class FCLApp extends Application implements Application.ActivityLifecycleCallbacks {
    private static FCLApp instance;
    private static WeakReference<Activity> currentActivity;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // 在 attachBaseContext 赋值，早于任何 Activity/ContentProvider，保证全局可用
        instance = this;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        this.registerActivityLifecycleCallbacks(this);
        // [M-01] FCLPath 是所有 Activity（含 com.dsh.*）与 ThemeEngine 的隐式前置。
        // 原先它在 FCLActivity.onCreate 里被 hasPermission 门控调用；本次删除存储权限后
        // 该分支恒不可达（isExternalStorageManager() 恒 false、checkSelfPermission 恒 DENIED），
        // 于是全仓库只剩 SplashActivity 一条路径会初始化它 —— 通知栏 PendingIntent
        // 冷启动（DshRuntimeService -> DshInstancesActivity）会绕过启动页。
        // 上提到 Application 层，保证任何入口（Activity / Service / PendingIntent / 测试）
        // 在首次读取 FCLPath.* 之前就已就绪。
        //
        // 注意：loadPaths() 幂等（纯赋值 + exists()/mkdirs()），重复调用安全；
        // 调用点唯一性由 SplashActivity.kt 中已删除的对应调用保证 —— 不要在那里加回去。
        com.tungsten.fclauncher.utils.FCLPath.loadPaths(this);
        // DeepSeek Harness 启动器：初始化路径与实例仓库。
        // 只用 App 私有目录（filesDir/cacheDir），不需要任何存储权限；幂等。
        com.dsh.core.DshPaths.loadPaths(this);
        com.dsh.core.DshInstances.init();
    }

    @NotNull
    public static Context getAppContext() {
        if (instance == null) {
            throw new IllegalStateException("FCLApp is not initialized");
        }
        return instance;
    }

    public static Activity getActivity() {
        if (currentActivity != null) {
            return currentActivity.get();
        }
        return null;
    }

    private void enabledStrictMode() {
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectNetwork()
                .detectCustomSlowCalls()
                .detectDiskReads()
                .detectDiskWrites()
                .detectAll()
                .penaltyLog()
                .build());

        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .detectAll()
                .penaltyLog()
                .build());
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle bundle) {
        currentActivity = new WeakReference<>(activity);
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        currentActivity = new WeakReference<>(activity);
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {

    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {

    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {

    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle bundle) {

    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        if (currentActivity != null && currentActivity.get() == activity) {
            currentActivity = null;
        }
    }
}