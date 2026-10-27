package com.dsh

import android.content.Context
import android.content.ContextWrapper
import com.dsh.core.DshDownloadViewModel
import com.dsh.core.DshInstaller
import com.dsh.core.ProotExecutor
import com.dsh.core.reconcileInstalling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * R26 / P3：下载页"安装中"标记的**状态迁移内核**与**接线**。
 *
 * ## 为什么这个文件必须存在（E6 §2.1~§2.3 的三条实测结论）
 * 1. **438 条里零条碰 `_installingVersions`**（那三条 grep 命中全是注释/失败消息字符串）。
 *    所以"抽个纯函数 + 单测它"在 R26 之前是**假绿**：调用点没接线、或接线时传错集合，
 *    纯函数用例照样全绿。
 * 2. 现码 `when` 有 `else -> {}`，而 `rebuild()` 在 when **之外** —— `Log` / `Stage` 事件
 *    也会触发重建。本文件用「Log 不清集合」把这条行为钉住（**不改** `rebuild()` 的位置：
 *    那会同时改变行为并放过一个已知性能问题，见 E6 §2.2）。
 * 3. `Dispatchers.Main` 在 JVM 里不存在（`FCL/build.gradle.kts` 没有 `kotlinx-coroutines-test`）。
 *    所以本文件**不**让 init 里的协程在 Main 上跑：`scope` 传 `Dispatchers.Unconfined`，
 *    并把 collect 体抽成 `internal fun onProgress(p)` 直接调用（这正是 E6 §2.3 建议的结构改造）。
 *
 * ## 三层覆盖（越往下越接近真实生产路径）
 * | 层 | 用例 | 证明了什么 |
 * |---|---|---|
 * | 纯函数 | done/failed/cancelled/log/stage（6 条） | `reconcileInstalling` 的迁移表 |
 * | VM 方法 | [onProgressIsActuallyWired] 等（3 条） | 事件 ⇒ `installingVersions` 真的变了 |
 * | init 收集器 | [initCollectorRoutesProgressThroughOnProgress]（1 条） | `init` 的 collect 体**真的**调了 `onProgress` |
 *
 * 第三层是唯一能杀掉"抽了但没接线"的用例：它把一条 `Progress` 推进 `installer.progress`
 * 这个 StateFlow，断言 VM 的公开 `installingVersions` 跟着变 —— 中间经过的正是生产代码那段
 * `installer.progress.drop(1).collect { p -> onProgress(p) }`。
 *
 * ## 运行
 * `sh run-tests.sh`（自动扫描类名）或 `./gradlew :FCL:testDebugUnitTest --tests "com.dsh.R26TerminalEventTest"`。
 */
class R26TerminalEventTest {

    private var scope: CoroutineScope? = null

    @After
    fun tearDown() {
        // init 里的两个 collect 都是**永不结束**的（StateFlow）。不取消的话，Gradle 的 test JVM
        // 会因为这些挂起的协程不肯退出（MiniRunner 末尾是 System.exit，脚本侧看不出来 —— 正是
        // E6 §2.3 点名的「脚本绿、Gradle 挂住」那种不对称）。
        scope?.cancel()
        scope = null
    }

    // ------------------------------------------------------------------
    // 一、纯函数：reconcileInstalling 的迁移表（6 条）
    // ------------------------------------------------------------------

    /**
     * `Done(version)` 必须移除**那一个**版本。
     * 这是安装成功的正常终态；不清标记 = 按钮永久"安装中"。
     */
    @Test
    fun doneClearsThatVersion() {
        val next = reconcileInstalling(setOf("1.0.0", "2.0.0"), DshInstaller.Progress.Done("1.0.0"))
        assertEquals("Done(v) 只移除 v，不能连带清掉同时在装的 2.0.0", setOf("2.0.0"), next)
    }

    /** `Failed(reason, version)` 与 Done 同一口径：只移除失败的那个版本。 */
    @Test
    fun failedClearsThatVersion() {
        val next = reconcileInstalling(
            setOf("1.0.0", "2.0.0"),
            DshInstaller.Progress.Failed("npm error EAI_AGAIN", "1.0.0")
        )
        assertEquals("Failed(reason, v) 同样只移除 v", setOf("2.0.0"), next)
    }

    /**
     * ★ E6-9 的坑：`Failed(version = null)` **什么都不清**。
     *
     * 历史：`fail()` 曾把新建实例的 `instance.dshVersion`（快照为 null）塞进 Failed，
     * 界面 `version?.let { ... }` 跳过清理 → 那一行永久卡在"安装中"、按钮点不动。
     * 反过来，若把 null 当成"清空整个集合"，A 的失败会顺带解禁 B 的按钮 → 用户连点 → 并发 npm。
     * 两个方向都是真机事故，所以这条用例钉死"null = 不动"。
     */
    @Test
    fun failedWithNullVersionClearsNothing() {
        val current = setOf("1.0.0", "2.0.0")
        val next = reconcileInstalling(current, DshInstaller.Progress.Failed("unknown", null))
        assertEquals("Failed(version=null) 必须原样返回 —— 既不清自己也不清别人", current, next)
    }

    /** `Cancelled(version)` 与 Done/Failed 同属终态：取消必须解禁按钮（否则只能重启 App）。 */
    @Test
    fun cancelledClearsThatVersion() {
        val next = reconcileInstalling(setOf("1.0.0", "2.0.0"), DshInstaller.Progress.Cancelled("1.0.0"))
        assertEquals("Cancelled(v) 也是终态，必须移除 v（且只移除 v）", setOf("2.0.0"), next)
        // 取消时拿不到版本号（Cancelled(null)）同样不能乱清：与 Failed(null) 一致
        assertEquals(
            "Cancelled(null) 同样什么都不清",
            setOf("1.0.0"),
            reconcileInstalling(setOf("1.0.0"), DshInstaller.Progress.Cancelled(null))
        )
    }

    /**
     * `Log` 事件不改变集合。
     *
     * 这是"终态不被后续日志吞掉"的最小可证形式：安装失败/完成后 proot 仍可能吐几行收尾输出，
     * 那些 `Log` 事件绝不能把已经清掉的标记又"复活"（也不可能——纯函数返回同一个集合），
     * 更不能把仍在装的标记清掉。
     */
    @Test
    fun logEventDoesNotChangeSet() {
        val current = setOf("1.0.0")
        val next = reconcileInstalling(current, DshInstaller.Progress.Log("npm http fetch GET 200 ..."))
        assertEquals("Log 不是状态迁移，必须原样返回", current, next)
    }

    /** `Stage` 事件（"正在解析依赖…"这类阶段文案）同样不改变集合。 */
    @Test
    fun stageEventDoesNotChangeSet() {
        val current = setOf("1.0.0")
        val next = reconcileInstalling(current, DshInstaller.Progress.Stage("正在安装依赖"))
        assertEquals("Stage 不是状态迁移，必须原样返回", current, next)
    }

    // ------------------------------------------------------------------
    // 二、接线：直接调 VM 的 onProgress（3 条）—— 这是 R26 的关键
    // ------------------------------------------------------------------

    /**
     * ★ 接线证据 1：`onProgress(Done)` 真的把 `installingVersions` 改了。
     *
     * 探针办法：先把 VM 私有集合种成 `{"1.0.0"}`，调完断言它是空的。若生产代码把 collect 体
     * 抽成了纯函数却**没调用**（或调用时传了 `emptySet()` / 传错参数顺序），这条会红。
     */
    @Test
    fun onProgressIsActuallyWired() {
        val (vm, _) = newVm()
        seedInstalling(vm, setOf("1.0.0", "2.0.0"))

        vm.onProgress(DshInstaller.Progress.Done("1.0.0"))

        assertEquals(
            "onProgress 必须把 Done 的版本从 installingVersions 里摘掉（接线断了这条就红）",
            setOf("2.0.0"),
            vm.installingVersions.value
        )
    }

    /**
     * ★ 接线证据 2：`onProgress(Log)` **不**清标记。
     *
     * 与纯函数用例 5 的区别：这条走的是 VM 的方法（含 `rebuild()` 调用）。它同时守住
     * E6 §2.2 的行为约束 —— `rebuild()` 在事件分支之外，所以 Log 也会重建，但**不会**动集合。
     */
    @Test
    fun onProgressLogDoesNotClear() {
        val (vm, _) = newVm()
        seedInstalling(vm, setOf("1.0.0"))

        vm.onProgress(DshInstaller.Progress.Log("npm http fetch GET 200 https://registry.npmjs.org/..."))

        assertEquals("Log 事件不得触碰 installingVersions", setOf("1.0.0"), vm.installingVersions.value)
    }

    /**
     * ★ 接线证据 3：一条完整安装序列 `Stage → Log → Log → Done` 之后，集合里不再有该版本。
     *
     * 这条钉的是"终态不被后续/穿插的日志打断"，也钉住"中间事件不会提前清标记"：
     * 每一步都断言一次，因此若把 `Stage`/`Log` 误接成"清除"，会在**第二步**就红，而不是等到最后。
     */
    @Test
    fun onProgressSequenceEndsWithTerminal() {
        val (vm, _) = newVm()
        seedInstalling(vm, setOf("9.9.9"))

        vm.onProgress(DshInstaller.Progress.Stage("正在解析依赖"))
        assertEquals("Stage 之后标记必须还在", setOf("9.9.9"), vm.installingVersions.value)

        vm.onProgress(DshInstaller.Progress.Log("npm http fetch GET 200 .../a.tgz"))
        assertEquals("第一条 Log 之后标记必须还在", setOf("9.9.9"), vm.installingVersions.value)

        vm.onProgress(DshInstaller.Progress.Log("npm http fetch GET 200 .../b.tgz"))
        assertEquals("第二条 Log 之后标记必须还在", setOf("9.9.9"), vm.installingVersions.value)

        vm.onProgress(DshInstaller.Progress.Done("9.9.9"))
        assertFalse(
            "Done 之后 installingVersions 不得再含 9.9.9（按钮必须重新可点）",
            vm.installingVersions.value.contains("9.9.9")
        )
        assertTrue("Done 之后集合应为空", vm.installingVersions.value.isEmpty())
    }

    // ------------------------------------------------------------------
    // 三、端到端接线：init 的 collect 体真的把事件交给了 onProgress（1 条）
    // ------------------------------------------------------------------

    /**
     * ★★ 这一条才是"抽了但没接线"的真正杀手。
     *
     * 前三条直接调 `vm.onProgress(...)`，它们能证明"方法本身对"，但**证明不了 `init` 会调它**
     * —— 恰好就是 E6 §2.2 的假绿形态。这里改从**生产入口**驱动：把一条 `Progress` 推进
     * `installer.progress` 这个 StateFlow，然后断言 VM 的公开只读状态 `installingVersions` 变了。
     *
     * 中间经过的正是生产代码：
     * ```
     * scope.launch { installer.progress.drop(1).collect { p -> onProgress(p) } }
     * ```
     * `scope` 是 `Dispatchers.Unconfined`（JVM 没有 Main），所以发射会在本线程同步续跑 —— 不需要
     * `runTest` / 轮询 / sleep；`drop(1)` 吃掉的是订阅瞬间的初始 `null`，所以第一条真实事件
     * **必然**被处理。
     */
    @Test
    fun initCollectorRoutesProgressThroughOnProgress() {
        val (vm, installer) = newVm()
        seedInstalling(vm, setOf("3.1.4"))

        emitInstallProgress(installer, DshInstaller.Progress.Done("3.1.4"))

        assertFalse(
            "init 的 collect 体必须把事件交给 onProgress —— 抽了函数却没接进 init 时，这条是唯一会红的",
            vm.installingVersions.value.contains("3.1.4")
        )
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /**
     * 造一个"能在 JVM 里构造"的 VM。
     *
     * `DshInstaller` 的构造只要 `Context` + [ProotExecutor]，构造函数体**不碰** Context
     * （`context` 只在 `runInstall` 的 wake lock / preflight 里用，见 `DshInstaller.kt:963,989`），
     * 所以这里的假 Context 只要"是个 Context 实例"即可。
     *
     * ★ 为什么不能 `ContextWrapper(null)`：`android.jar` 的方法是 `throw new RuntimeException("Stub!")`，
     *   `ContextWrapper(Context)` 的构造会调 `super()` → 直接抛 `Stub!`（E6 §2.3 实测，本轮复核仍如此）。
     *   而 `Context` 有 103 个抽象方法，手写子类不现实。这里用 `sun.misc.Unsafe.allocateInstance`
     *   **跳过构造函数**分配一个 `ContextWrapper` 实例 —— 它 `is Context`，且我们从不调用它的任何
     *   方法（`onProgress` 路径不读 Context）。
     */
    private fun newVm(): Pair<DshDownloadViewModel, DshInstaller> {
        val s = CoroutineScope(Dispatchers.Unconfined)
        scope = s
        val installer = DshInstaller(fakeContext(), NoopProot)
        return DshDownloadViewModel(s, installer) to installer
    }

    private fun fakeContext(): Context {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe: Field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafe.get(null)
        val allocate: Method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val o = allocate.invoke(unsafe, ContextWrapper::class.java)
        return o as Context
    }

    /** `_installingVersions` 是 private，没有测试入口 —— 反射种值（只在测试里，生产不受影响）。 */
    private fun seedInstalling(vm: DshDownloadViewModel, values: Set<String>) {
        val f = DshDownloadViewModel::class.java.getDeclaredField("_installingVersions")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = f.get(vm) as MutableStateFlow<Set<String>>
        flow.value = values
    }

    /**
     * 把一条事件推进 `DshInstaller.progress`（生产入口）。
     * `_progress` 是 private 且没有公开 emit 方法（只有一个 UI 读的只读 `StateFlow`），
     * 这里反射拿到那个 `MutableStateFlow` —— 这是唯一不新增生产 API 就能模拟"安装器发事件"的办法。
     */
    private fun emitInstallProgress(installer: DshInstaller, p: DshInstaller.Progress) {
        val f = DshInstaller::class.java.getDeclaredField("_progress")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = f.get(installer) as MutableStateFlow<DshInstaller.Progress?>
        flow.value = p
    }

    /**
     * 永不执行的执行器：本文件所有用例都只碰"事件 → 集合"，不跑 proot。
     *
     * ★ 默认实现抛 `AssertionError`（而不是 `Exception`）：万一将来有人误在用例里触发安装，
     *   我们希望**测试立刻红并指出"这里不该跑 proot"**，而不是拿到一个含混的失败。
     */
    private object NoopProot : ProotExecutor {
        override fun run(script: String, env: Map<String, String>, onLine: (String) -> Unit): Int =
            throw AssertionError("R26TerminalEventTest 不跑 proot：onProgress 路径不需要 ProotExecutor")
    }
}
