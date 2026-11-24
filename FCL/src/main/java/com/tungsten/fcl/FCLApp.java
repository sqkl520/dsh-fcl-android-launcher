package com.tungsten.fcl;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.StrictMode;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tungsten.fclcore.util.StringUtils;

import org.jetbrains.annotations.NotNull;

import java.lang.ref.WeakReference;

public class FCLApp extends Application implements Application.ActivityLifecycleCallbacks {
    private static FCLApp instance;
    private static WeakReference<Activity> currentActivity;

    /**
     * [R31 / s10-eh-03] 本次进程启动中**失败**的初始化步骤（`步骤名: 异常摘要`）。
     *
     * 为什么需要它：`onCreate` 原本是一条没有任何 try/catch 的直线，任一初始化抛异常就穿出
     * `Application.onCreate` —— 而 `handleBindApplication` 阶段的崩溃是**每次冷启动确定性复现**的
     * 闪退循环，且 `CrashReporter` 对这类栈明确拒绝弹错误页（见 `isStackTraceLikelyConflictive`），
     * 用户和开发者都拿不到任何界面线索。现在每一步都被单独兜住：失败 → 记进本列表 + 落盘，
     * 然后**继续执行后续步骤**（一步失败不跳过其余五步 —— 带病状态正是最需要日志的那次启动）。
     *
     * 刻意**只增不清**：清空意味着"下一次成功了"，而进程级单例没有"下一次"以外的成功定义；
     * 保留全量才能回答"这次启动到底哪几步没成"。
     */
    private static final java.util.List<String> failedInits =
            java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

    /** [R31 / s10-eh-03] 失败落盘文件名（放在 `getFilesDir()` 下，卸载时随 App 清理）。 */
    public static final String STARTUP_FAILURE_LOG = "startup-failures.log";

    /** 落盘上限：只保留最后 64KB，避免启动失败反复发生把文件写爆（同一次失败通常也就几 KB）。 */
    private static final long STARTUP_FAILURE_LOG_MAX = 64L * 1024;

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
        //
        // ★ [R31 / s10-eh-03] 每一步都单独包进 initStep(...)：这里**绝对不能**让异常穿出去。
        //   `handleBindApplication` 阶段的崩溃每次冷启动都复现（闪退循环），而且 CrashReporter
        //   对这类栈会跳过错误页直接走默认 handler —— 现场只剩 logcat 环形缓冲，重启即失。
        //   六步逐一兜底（不是一个 try 包全部）：FCLPath 失败时仍要让 DshAppContextHolder /
        //   DshTasks 起来，否则"带病启动"会连首页的任务聚合都一起没有。
        initStep("fclPath", () -> com.tungsten.fclauncher.utils.FCLPath.loadPaths(this));
        // DeepSeek Harness 启动器：初始化路径与实例仓库。
        // 只用 App 私有目录（filesDir/cacheDir），不需要任何存储权限；幂等。
        initStep("dshPaths", () -> com.dsh.core.DshPaths.loadPaths(this));
        // R17：把"本进程是谁"写进日志流首（launcher=0.1.6-SNAPSHOT-fixN (code=N)）——
        // R16/R17 两轮真机排查都卡在"不知道用户装的是哪个包"，这行横幅让任何一份贴出来的
        // 日志第一行就能定位包与代次。幂等（同进程只写一次；重复出现即"进程重启过"）。
        initStep("versionBanner", com.dsh.core.DshLogBus::writeVersionBannerIfNeeded);
        initStep("dshInstances", com.dsh.core.DshInstances::init);
        // 任务聚合（首页"进行中任务"区）需要 Application Context 来取 installer 实例
        initStep("appContext", () -> com.dsh.core.DshAppContextHolder.init(this));
        // ★ L1 + L2（R49 架构）：把两项"core 需要、只有 UI 侧才知道"的能力注册给 core。
        //   详见 registerUiCapabilitiesForCore 的注释（**关键**：这里刻意不用 initStep）。
        //
        // ★ R49 验收补：外面**仍然必须有兜底**。理由不是"它可能抛"（当前实现确实抛不了：
        //   两次 register 都只是给 @Volatile var 赋值、传入的 lambda 此刻不求值），
        //   而是**防护等级一致性**：这是 Application.onCreate 里唯一没有 try 的步骤，
        //   将来若有人往这两行里加了会抛的东西，异常会直接穿出 onCreate ⇒
        //   handleBindApplication 崩溃 ⇒ 闪退循环（R31 的注释正是为此而写）。
        //   套 runCatching 不改变 initStep 的条数（R31 的闸门仍看到 6），
        //   也保留了"绝不穿出去"这条更重要的性质。
        //   ⚠️ 必须留日志：真出问题时"注册没成功"要在日志里看得见，否则下游
        //   退到 PM 的 LAUNCHER 入口时完全无痕，排障会以为注册成功了。
        try {
            registerUiCapabilitiesForCore();
        } catch (Throwable t) {
            com.dsh.core.DshLogBus.append(
                "[app] registerUiCapabilitiesForCore failed (core will fall back to "
                    + "PM launcher entry / AppContext): " + t);
        }
        initStep("dshTasks", com.dsh.core.DshTasks::startDefault);
    }

    /**
     * ★ L1 + L2（R49 架构）：core 对 UI 的两处依赖，全部在这一处反转成注入。
     *
     * ## L1：外壳入口
     * `DshRuntimeService` 原先是 `Intent(context, DshMainActivity::class.java)` —— 全仓**唯一**
     * 一条 `core → ui` 的编译期依赖。有它在，"core 可以独立于 ui 存在"这条分层前提就不成立。
     * 现在 core 只认 `com.dsh.core.DshShellEntry`（一个 fun interface），"哪个类当外壳"
     * 由本行（App 组装层）回答。**没有**改成"把 Activity 类名当字符串 / 反射"——
     * 那只是把编译期依赖藏进运行时，问题原样保留。
     *
     * 未注册/注册失败也不崩：`DshRuntimeService.resolveShellIntent` 有三层退化
     * （注册实现 → PM 的 LAUNCHER 入口 → 通知不挂点击目标），`R49LayeringFixTest` 逐个钉住。
     *
     * ## L2：取词 Context
     * `DshUiText.contextOrNull()` 原先直接读 `FCLApp.getActivity()`。现在 core 只认
     * `com.dsh.core.DshStringProvider`，由这里注入。返回 null = 此刻没有前台 Activity，
     * core 会退到 `DshAppContextHolder.context` —— 与改造前"没有前台 Activity"同一条退路，
     * 所以即便注入缺席也不会让任何文案比改动前更差。
     *
     * ## 为什么**不**用 initStep 包起来（与上面六步不同）
     * `initStep` 的职责是"这一步可能抛，失败要登记并继续启动"。
     * 这里两件事都**不可能抛**：
     * 1. `register(...)` 的实现体是一个 `@Volatile` 引用赋值；
     * 2. 传进去的 lambda **此刻不求值** —— `new Intent(...)` 与 `getActivity()` 都要等到
     *    真正构建通知/取词时才会执行（而它们各自的调用点已有 runCatching 与退化）。
     * 反过来，如果套上 `initStep`，`FCLApp.onCreate` 的受保护步骤就从 6 步变 8 步 ——
     * 而 `R31StartupChainTest.everyInitStepIsGuardedIndividually` 用**硬编码 6** 钉住那条链
     * （它的意图是"六个真步骤一个都不能漏"，不是"只许有六个"）。为一个不可能抛的动作去改
     * 那条回归闸门的常数，等于用"放宽闸门"换"多包一层没用的 try"。
     *
     * ⚠️ 若将来这两处注册真的变成可能抛（例如在注册时就去解析某个资源），
     * 正确的做法是**把它们拆成真正的 initStep 步骤并同步更新上面那条测试的期望值**，
     * 而不是在这里加 try/catch —— 那会绕过"失败要落盘登记"这条取证要求。
     */
    private void registerUiCapabilitiesForCore() {
        // L1：通知点回 App 的目标。**刻意不用** DshMainActivity.intentForShell：那个入口带
        // FLAG_ACTIVITY_REORDER_TO_FRONT（给 WebView 页"返回外壳"用），而通知的语义是
        // "把 App 拉到前台"，改造前就是裸 Intent + NEW_TASK|SINGLE_TOP（后两个标志由
        // DshRuntimeService 统一补）。借那个入口等于顺手改了通知的启动语义。
        com.dsh.core.DshNavigation.register(
                context -> new Intent(context, com.dsh.ui.shell.DshMainActivity.class));
        // L2：取词用的 Context（前台 Activity 优先，拿不到由 core 退回 App Context）。
        com.dsh.core.DshStrings.register(() -> getActivity());
    }

    /**
     * [R31 / s10-eh-03] 执行一个初始化步骤，失败时登记 + 落盘 + 继续 —— **绝不向上抛**。
     *
     * 捕获 `Throwable` 而不只是 `Exception`：`NoClassDefFoundError` / `ExceptionInInitializerError`
     * （某个静态初始化器炸了，例如缺一个可选依赖）在冷启动路径上与 RuntimeException 后果相同，
     * 但 `catch (Exception)` 接不住它们。
     */
    private static void initStep(String name, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            java.io.File dir = null;
            try {
                // getFilesDir() 本身也可能抛（存储未挂载时 IllegalStateException）——
                // 它若抛在 catch 块里，就等于把"记录失败"变成了新的闪退源。宁可丢落盘、只留内存登记。
                dir = instance == null ? null : instance.getFilesDir();
            } catch (Throwable ignored) {
            }
            recordStartupFailure(dir, name, t);
        }
    }

    /**
     * [R31 / s10-eh-03] 登记一次启动失败并**落盘**（持久化，重启后可取证）。
     *
     * 为什么必须落盘：`System.err` / `Log.e` 在 Android 上只进 logcat 环形缓冲，进程崩溃重启后
     * 取不回 —— 而"崩了要取证"正是这条路径存在的唯一理由。
     *
     * **本方法自身绝不抛**：它在 catch 块里被调用，若这里再抛一次，"记录失败"就成了新的闪退源
     * （比原缺陷更糟）。`filesDir` 为 null（`attachBaseContext` 之前 / 测试）或不可写时，
     * 只保留内存登记（`getFailedInits()` 仍可读）。
     *
     * @param filesDir 落盘目录；null 时跳过落盘
     * @param step     失败的步骤名（见 `onCreate` 里的字面量）
     * @param error    原始异常
     */
    public static void recordStartupFailure(java.io.File filesDir, String step, Throwable error) {
        failedInits.add(step + ": " + error);
        if (filesDir == null) return;
        try {
            java.io.File log = new java.io.File(filesDir, STARTUP_FAILURE_LOG);
            // 超限就截断（而不是无限追加）：启动失败可能反复发生，文件必须有上界
            if (log.isFile() && log.length() > STARTUP_FAILURE_LOG_MAX) log.delete();
            String text = "--- " + new java.util.Date() + " / step=" + step + " ---\n"
                    + StringUtils.getStackTrace(error) + "\n";
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(log, true)) {
                out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            // 落盘失败只剩内存登记；这里再抛就违背了本方法的全部意义
        }
    }

    /**
     * [R31 / s10-eh-03] 本次进程启动中失败的初始化步骤（只读快照，空列表 = 全部成功）。
     *
     * 这是缺陷要求的「可被调用方感知的失败信号」：任何界面/诊断页都能读它，
     * 不必去翻 logcat。返回副本，调用方拿不到内部列表。
     */
    public static java.util.List<String> getFailedInits() {
        synchronized (failedInits) {
            return new java.util.ArrayList<>(failedInits);
        }
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