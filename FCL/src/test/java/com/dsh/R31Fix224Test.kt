package com.dsh

import com.dsh.core.DshInstance
import com.dsh.fcl.androidlauncher.R
import com.dsh.ui.SizeCachePlan
import com.dsh.ui.actionTextColorFor
import com.dsh.ui.sizeCacheEntryUsable
import com.dsh.ui.sizeCacheKeysToEvict
import com.dsh.ui.sizeCachePlan
import com.dsh.ui.volumeCacheKey
import com.dsh.ui.setup.DshSetupState
import com.dsh.ui.setup.invalidatePreparedEnvironment
import com.dsh.ui.shell.ManifestRetryPlan
import com.dsh.ui.shell.awaitManifestWriteResult
import com.dsh.ui.shell.isDangerousActionTag
import com.dsh.ui.shell.manifestRetryFailureText
import com.dsh.ui.shell.manifestRetryPlan
import com.dsh.ui.DshInstanceSettingAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences

/**
 * round31 / 第 4 波 fix（`fix-r31-224`）：五条发现的证伪条件。
 *
 * ## 断言口径
 *
 * 本轮已多次证明"源码文本断言"形同虚设（改一处注释/换个写法就绿），所以这里**全部是值断言**：
 * 每条用例调一个生产路径上**真的被调用**的函数，用两个不同入参换出两个不同结果。
 * 唯一例外在 `dcF3` 的"接线"一条 —— 那是"函数写好了但没人调"这类既往事故的唯一可判形态，
 * 它断言的是**调用点存在**，并明确标注它不是行为证据（真正的行为证据是同一族的另两条）。
 *
 * ## 为什么这些函数都写成了顶层纯函数
 *
 * 本仓 harness 的 classpath 只有 stdlib/coroutines/gson/android.jar，**没有 androidx**
 * （`run-tests.sh:244` 的 `CP`，先例见 `R31Fix204Test` / `R31Fix221Test` 的注释）。
 * 页面/Adapter 类都继承 androidx 的基类，`Class.forName` 就会在父类链上炸。
 * 顶层函数无类初始化负担；`DshInstanceSettingAdapter.Row.Action` 是嵌套在 Adapter 里的
 * `data class`，实测能单独加载（见报告 §5 的探针）。
 */
class R31Fix224Test {

    // =========================================================================
    // slice-05-dc-f2：体积缓存 —— 键、作废范围、异步写回守卫
    // =========================================================================

    /**
     * 缺陷形态（审计 F2 / 裁决 UPHELD·MEDIUM）：键在**请求时刻**由绑定快照拼出
     * （`DshInstanceAdapter.kt:553`），异步回调**无条件**写表（`:561`）；全文件无失效点。
     *
     * 证伪条件 1（键本身）：`INSTALLING` 期不给键（`null`），其余三态各给一个键，
     * 且**版本不同 ⇒ 键不同**（同版本重装的键必须用以判断"回到旧键"这件事）。
     */
    @Test
    fun dcF2_installingHasNoKeyAndKeyCarriesVersionAndState() {
        assertNull(
            "INSTALLING 期必须不显示体积（BL-169），所以不该有键",
            sizeCachePlan("i1", "0.2.0", DshInstance.State.INSTALLING, emptyList()).key
        )
        val ready = sizeCachePlan("i1", "0.2.0", DshInstance.State.READY, emptyList()).key
        val readyV2 = sizeCachePlan("i1", "0.3.0", DshInstance.State.READY, emptyList()).key
        val broken = sizeCachePlan("i1", "0.2.0", DshInstance.State.BROKEN, emptyList()).key
        assertNotNull("READY 必须有键", ready)
        assertEquals("键必须含版本", "i1:0.2.0:READY", ready)
        assertNotEquals("换版本必须换键，否则会显示上一版的体积", ready, readyV2)
        assertNotEquals("换状态必须换键", ready, broken)
        // 同版本重装回到 READY：键**逐字**回到旧值 —— 这正是缺陷之所以成立的前提，
        // 也是"进入 INSTALLING 必须作废旧键"的唯一理由。
        assertEquals(
            "同版本重装后键必须与重装前相同（这是 dc-f2 的成立前提，不是 bug）",
            ready, sizeCachePlan("i1", "0.2.0", DshInstance.State.READY, emptyList()).key
        )
    }

    /**
     * 证伪条件 2（作废范围）：进入 `INSTALLING` 必须作废**本实例**的全部旧键，
     * 且**不得**波及别的实例的条目（否则删一个实例会把全表清掉）。
     */
    @Test
    fun dcF2_installingEvictsOnlyThisInstancesKeys() {
        val cached = setOf(
            "i1:0.2.0:READY",
            "i1:0.2.0:BROKEN",
            "i1:0.1.0:READY",
            "i2:0.2.0:READY"
        )
        val plan = sizeCachePlan("i1", "0.2.0", DshInstance.State.INSTALLING, cached)
        assertEquals(
            "进入 INSTALLING 必须作废本实例的全部旧键（含同版本那一格）",
            setOf("i1:0.2.0:READY", "i1:0.2.0:BROKEN", "i1:0.1.0:READY"),
            plan.keysToEvict
        )
        assertFalse(
            "不许动别的实例的缓存",
            plan.keysToEvict.contains("i2:0.2.0:READY")
        )
        // 非安装态**不**作废：无差别地每次绑定都清，等于把缓存整个废掉 ——
        // 卡片每次重绑都要 walk 一遍 300MB，正是该文件"绝不每次绑定都去 walk"要防的事。
        assertEquals(
            "非安装态不许清缓存（否则等于取消缓存，每次重绑都 walk 300MB）",
            emptySet<String>(),
            sizeCachePlan("i1", "0.2.0", DshInstance.State.READY, cached).keysToEvict
        )
        assertEquals(
            "BROKEN 同理：状态变了但磁盘没被重写，已测的数字仍有效",
            emptySet<String>(),
            sizeCachePlan("i1", "0.2.0", DshInstance.State.BROKEN, cached).keysToEvict
        )
    }

    /**
     * 证伪条件 3（异步写回守卫）：回调**只能写回"仍是该实例当前键"的那一格**。
     *
     * 这是缺陷的第二条渠道：测量在 IO 线程跑，跑完时状态可能已经翻页（安装开始/结束、换版本、
     * 实例被删）—— 无条件落表就会把"安装期间测到的中间值"永久钉在某个键上。
     */
    @Test
    fun dcF2_asyncResultOnlyLandsOnTheStillCurrentKey() {
        val requested = volumeCacheKey("i1", "0.2.0", DshInstance.State.READY)
        assertTrue(
            "状态没变：这次读数必须落表（否则每次绑定都重新 walk 300MB）",
            sizeCacheEntryUsable(requested, volumeCacheKey("i1", "0.2.0", DshInstance.State.READY))
        )
        assertFalse(
            "读数期间开始了安装：这次读数属于「安装前的 READY」，不能写进 INSTALLING 那一格",
            sizeCacheEntryUsable(requested, volumeCacheKey("i1", "0.2.0", DshInstance.State.INSTALLING))
        )
        assertFalse(
            "读数期间换了版本：旧版本的读数对新版本无意义",
            sizeCacheEntryUsable(requested, volumeCacheKey("i1", "0.3.0", DshInstance.State.READY))
        )
        assertFalse(
            "实例已被删（当前键为 null）：读数无处可去，必须丢弃而不是凭空造一个键",
            sizeCacheEntryUsable(requested, null)
        )
    }

    /**
     * 作废范围不许跨实例（前缀必须带 `:` 终止符）。
     *
     * 实例 id 之间可以是前缀关系（`i1` / `i10`）：只按 `id` 匹配会让 `i1` 的作废动作
     * 把 `i10` 的条目一起删掉 —— 而 `i10` 那一帧已经绑过了，它不会因此重新测量，
     * 卡片上的体积就永久停在"计算中"。
     */
    @Test
    fun dcF2_evictionScopeDoesNotLeakAcrossIdPrefixes() {
        val cached = setOf("i1:0.2.0:READY", "i10:0.2.0:READY", "i1x:0.2.0:READY")
        assertEquals(
            "i1 的作废动作只能带走 i1 自己的键（不许波及 i10 / i1x）",
            setOf("i1:0.2.0:READY"),
            sizeCacheKeysToEvict("i1", cached)
        )
        assertEquals(
            "i10 同理",
            setOf("i10:0.2.0:READY"),
            sizeCacheKeysToEvict("i10", cached)
        )
    }

    // =========================================================================
    // slice-05-dc-f3：「重新开始」必须同时复位 KEY_SETUP_DONE
    // =========================================================================

    /**
     * 缺陷形态（审计 F3 / 裁决 UPHELD·LOW）：`restartFromScratch` 删掉 `rootfs/version`、
     * `scripts/version` 后直接 `beginPrepare`，**从不**把 `KEY_SETUP_DONE` 置回 false。
     * 于是"持久化已完成"与"磁盘事实"分叉 —— 解压是分钟级任务，期间进程被杀就按前者放行。
     *
     * 证伪条件（主）：预置标记为 true，跑一次"重新开始"的磁盘清除动作后，
     * `isCompleted(ctx)` 必须为 **false**。
     */
    @Test
    fun dcF3_restartClearsTheCompletedMark() {
        val prefs = InMemoryPreferences(mapOf("dsh_setup_completed" to true))
        val ctx = fakeContext(prefs)
        assertTrue("前置：标记必须先是 true，否则这条用例测不到东西", DshSetupState.isCompleted(ctx))

        var deleted = false
        invalidatePreparedEnvironment(ctx) { deleted = true }

        assertTrue("前置：磁盘清除动作必须真的被执行", deleted)
        assertFalse(
            "删掉磁盘工件却不复位标记 ⇒ 冷启会按「已完成」放行一个没有运行环境的机器",
            DshSetupState.isCompleted(ctx)
        )
    }

    /**
     * 证伪条件（顺序）：磁盘清除动作必须在置假**之前**跑 —— 先置假再删，会留一个
     * "标记说没准备好、磁盘还有证据"的窗口（安全但不诚实，且会让"重新开始失败"的
     * 现场无法从标记上看出来）。这里用副作用顺序钉住它。
     */
    @Test
    fun dcF3_evidenceIsRemovedBeforeTheMarkFlips() {
        val prefs = InMemoryPreferences(mapOf("dsh_setup_completed" to true))
        val ctx = fakeContext(prefs)
        val seenWhileDeleting = ArrayList<Boolean>()
        invalidatePreparedEnvironment(ctx) {
            // 删除动作运行时，标记必须**还是 true**（说明复位发生在其后）
            seenWhileDeleting += DshSetupState.isCompleted(ctx)
        }
        assertEquals(
            "磁盘清除必须先于置假：删的过程中标记仍应是原值",
            listOf(true),
            seenWhileDeleting
        )
        assertFalse("删除完必须置假", DshSetupState.isCompleted(ctx))
    }

    /**
     * 接线守卫：`restartFromScratch` 的确认分支必须真的走 `invalidatePreparedEnvironment`
     * （既往事故的形态就是"纯函数抽好了、调用点没改"，见 `R28CallSiteContractTest` 的由来）。
     *
     * **诚实标注**：这一条是**调用点存在性**断言（源码级），不是行为证据 ——
     * 行为证据是上面两条。它存在的理由：没有它，把 `invalidatePreparedEnvironment(...)`
     * 那一段整体删掉，上面两条仍然全绿（它们直接调函数，不经过页面）。
     */
    @Test
    fun dcF3_restartFromScratchActuallyCallsTheInvalidator() {
        val text = mainSource("ui/setup/DshSetupActivity.kt")
        assertNotNull("找不到 DshSetupActivity.kt 源码（请在仓库根或 FCL/ 目录下运行测试）", text)
        val body = text!!.substringAfter("private fun restartFromScratch()")
        assertTrue(
            "restartFromScratch 必须调用 invalidatePreparedEnvironment（否则标记永不复位）",
            body.contains("invalidatePreparedEnvironment(")
        )
        assertTrue(
            "复位必须发生在 beginPrepare 之前（解压一旦开始就有自己的成败分支）",
            body.indexOf("invalidatePreparedEnvironment(") < body.indexOf("beginPrepare(forceExtract = true)")
        )
    }

    // =========================================================================
    // C-06-01：写失败重试的「成败」必须来自异步写结果，且文案说的是「写」
    // =========================================================================

    /**
     * 缺陷形态（审计 C-06-01 / 裁决 UPHELD·MEDIUM）：`onManifestRetry` 把
     * `saveAsync()` 的 Boolean（"是否已排队"）当成"重试是否成功"，而该支路的前提
     * （`manifestLoadFailed == false`）让 `saveAsync()` **恒**返回 true ⇒
     * 「重试失败」提示不可达、写盘再次失败时用户点了没有任何新反馈。
     *
     * 证伪条件 1（等的确实是那一次的异步结果）：`false`（写成功）到达 ⇒ 返回 true；
     * 流一直停在 `true`（写仍失败）⇒ 超时返回 false。
     */
    @Test
    fun c0601_writeResultComesFromTheAsyncFlowNotTheQueuedBoolean() = runBlocking {
        val succeeded = MutableStateFlow(true)
        succeeded.value = false // 写成功：saveAsync 的异步路径会把位清掉
        assertTrue(
            "看到 manifestWriteFailed 变 false ⇒ 这次重试算成功",
            awaitManifestWriteResult(succeeded, 2_000L)
        )

        val stillFailing = MutableStateFlow(true)
        assertFalse(
            "写一直失败（位停在 true）⇒ 必须超时返回 false —— 否则「重试失败」永远提示不出来",
            awaitManifestWriteResult(stillFailing, 300L)
        )
    }

    /**
     * 证伪条件 2：**订阅前的一次状态快照也要认**。
     *
     * 协程真正开跑时那次写可能已经完成了（`saveAsync()` 只是"排队"，写盘可能在
     * `scope.launch` 抢到线程之前就结束）。只订阅后续发射会把"已经成功"误判成超时 ——
     * 那正是"每次点重试都弹一句失败"的反向缺陷。
     */
    @Test
    fun c0601_alreadyClearedFlagCountsAsSuccess() = runBlocking {
        assertTrue(
            "位在订阅前就已经是 false（写早已成功）时必须返回 true",
            awaitManifestWriteResult(MutableStateFlow(false), 300L)
        )
    }

    /**
     * 证伪条件 3（文案）：写失败分支说的必须是「写」。改前两条支路共用
     * `dsh_manifest_retry_failed`（"仍然**读不到**，请重启 App"），而写失败支路的前提
     * 恰恰是"清单读得动"—— 那句话在用户眼前是假的。
     */
    @Test
    fun c0601_rewriteFailureSaysWriteNotRead() {
        assertEquals(
            "读失败支路：清单真的读不出来",
            R.string.dsh_manifest_retry_failed,
            manifestRetryFailureText(ManifestRetryPlan.RELOAD)
        )
        assertEquals(
            "写失败支路必须说「写」（与此刻横幅上那句话同一个 key）",
            R.string.dsh_manifest_write_failed,
            manifestRetryFailureText(ManifestRetryPlan.REWRITE)
        )
        assertNotEquals(
            "两条支路的失败提示不能是同一句话 —— 那正是 C-06-01 的缺陷形态",
            manifestRetryFailureText(ManifestRetryPlan.RELOAD),
            manifestRetryFailureText(ManifestRetryPlan.REWRITE)
        )
    }

    /** 支路判据与失败位同源：`loadFailed == true` 走重读，否则走重写。 */
    @Test
    fun c0601_planFollowsTheLoadFailedFlag() {
        assertEquals(ManifestRetryPlan.RELOAD, manifestRetryPlan(true))
        assertEquals(ManifestRetryPlan.REWRITE, manifestRetryPlan(false))
    }

    // =========================================================================
    // C-06-02：Row.Action.dangerous 必须真的被读、且删除行真的传
    // =========================================================================

    /**
     * 缺陷形态（审计 C-06-02 / 裁决 UPHELD·LOW）：`Row.Action.dangerous` 是公开 API 参数
     * （KDoc 承诺"删除这类破坏性操作"会被区别对待），全仓**零读取点**，删除行连传都没传。
     *
     * 证伪条件 1：判据函数必须对两个入参给出**不同**的颜色。恒选一支（或删掉读取）必红。
     */
    @Test
    fun c0602_dangerousSelectsTheSemanticColor() {
        val normal = 0x11
        val danger = 0x22
        assertEquals(
            "dangerous=true 必须用语义色",
            danger,
            actionTextColorFor(dangerous = true, dangerColor = danger, normalColor = normal)
        )
        assertEquals(
            "dangerous=false 必须用普通色（行是复用的，不写回就会继承上一行的红）",
            normal,
            actionTextColorFor(dangerous = false, dangerColor = danger, normalColor = normal)
        )
    }

    /**
     * 接线守卫（C-06-02）：绑定分支必须把 `row.dangerous` 传进判据函数。
     *
     * **诚实标注**：源码级调用点断言，不是行为证据 —— 上面 [c0602_dangerousSelectsTheSemanticColor]
     * 与下面 [c0602_deleteRowCarriesDangerousTrue] 才是行为证据。本条存在的唯一理由：
     * 把绑定处那个实参改成 `dangerous = false`（实测变异 M9），上面两条**都不会红**
     * —— 判据函数本身没坏、删除行也确实传了 true，坏的是"中间那根线"。
     * 这条缺陷的原始形态（"参数零读取点"）恰好就在这根线上，所以它必须有守卫。
     */
    @Test
    fun c0602_bindSitePassesTheRowsDangerousFlag() {
        val text = mainSource("ui/DshInstanceSettingAdapter.kt")
        assertNotNull("找不到 DshInstanceSettingAdapter.kt 源码", text)
        assertTrue(
            "绑定时必须把 row.dangerous 传给 actionTextColorFor —— " +
                "传字面 false 会让整个参数重新变回零读取点（变异 M9 实测不被其它用例覆盖）",
            text!!.contains("dangerous = row.dangerous,")
        )
        assertTrue(
            "删除行必须真的把 dangerous=true 传进 Row.Action",
            mainSource("ui/shell/DshInstanceSettingPage.kt")
                ?.contains("dangerous = isDangerousActionTag(") == true
        )
    }

    /** 证伪条件 2：维护段里**只有** DELETE 是不可撤销动作。 */
    @Test
    fun c0602_onlyDeleteIsDangerous() {
        assertTrue(
            "删除必须判为危险动作（它连 home/ 里的会话与凭据一起删）",
            isDangerousActionTag(DshInstanceSettingAdapter.Tag.DELETE)
        )
        assertFalse(
            "重装是可逆动作（同版本再装一遍即可），不该染成危险色",
            isDangerousActionTag(DshInstanceSettingAdapter.Tag.REINSTALL)
        )
        assertFalse(
            "运行段的动作更不该染红",
            isDangerousActionTag(DshInstanceSettingAdapter.Tag.START_STOP)
        )
    }

    /**
     * 证伪条件 3：删除行真的把这个值传下去（默认值是 false，不传就永远是普通色）。
     *
     * 断言的是**值**：构造出来的 `Row.Action` 的 `dangerous` 必须是 true。
     * 这里直接构造（而不是反射解析页面源码）—— 嵌套 `data class` 能被 JVM 单独加载，
     * 这条用例因此钉的是"这个类型的构造结果"，不是"某段文字长什么样"。
     */
    @Test
    fun c0602_deleteRowCarriesDangerousTrue() {
        // 与生产构造点同一形状（DshInstanceSettingPage.configRows 的删除行）
        val row = DshInstanceSettingAdapter.Row.Action(
            R.string.dsh_action_delete,
            R.string.dsh_settings_desc_delete,
            R.string.dsh_action_delete,
            DshInstanceSettingAdapter.Tag.DELETE,
            dangerous = isDangerousActionTag(DshInstanceSettingAdapter.Tag.DELETE)
        )
        assertTrue("删除行必须传 dangerous=true（判据来自 isDangerousActionTag）", row.dangerous)

        // 反方向：默认值必须仍是 false —— 否则"没传"这个缺陷形态会在别的行上重新出现
        val plain = DshInstanceSettingAdapter.Row.Action(
            R.string.dsh_action_reinstall,
            R.string.dsh_settings_desc_reinstall,
            R.string.dsh_action_reinstall,
            DshInstanceSettingAdapter.Tag.REINSTALL
        )
        assertFalse("没传 dangerous 的行必须是普通色（默认值语义不变）", plain.dangerous)
    }

    // =========================================================================
    // 工具
    // =========================================================================

    private fun assertNull(message: String, value: Any?) {
        if (value != null) throw AssertionError("$message (was <$value>)")
    }

    private fun assertNotEquals(message: String, unexpected: Any?, actual: Any?) {
        if (unexpected == actual) throw AssertionError("$message (both were <$actual>)")
    }

    /** 从 cwd 向上找 `FCL/src/main/java/com/dsh/<rel>`（与 R28/R31 各测试同款）。 */
    private fun mainSource(rel: String): String? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            listOf(
                File(dir, "FCL/src/main/java/com/dsh/$rel"),
                File(dir, "src/main/java/com/dsh/$rel")
            ).firstOrNull { it.isFile }?.let { return it.readText() }
            dir = dir.parentFile
            depth++
        }
        return null
    }

    /**
     * 只认 `getSharedPreferences` 的 Context。用 `Unsafe.allocateInstance` 跳过构造函数分配
     * （`ContextWrapper` 的构造体会走 `Stub!`），这是本仓 `R31TraditionalChineseIndexTest` /
     * `R31DateTimeFormatterLocaleTest` 已确立的手法。
     */
    private class FakeContext : ContextWrapper(null) {
        @Suppress("unused")
        private val prefs: SharedPreferences? = null

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs!!
    }

    private fun fakeContext(prefs: SharedPreferences): Context {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe: Field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafe.get(null)
        val allocate: Method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val ctx = allocate.invoke(unsafe, FakeContext::class.java) as Context
        val f = FakeContext::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        f.set(ctx, prefs)
        return ctx
    }

    /**
     * 内存版 SharedPreferences：写入**推迟到 `apply()`**（否则"忘了 apply"会假通过）。
     * 未实现的方法一律抛 `UnsupportedOperationException`，让"以后有人在这条链上加了新调用"立刻暴露。
     */
    private class InMemoryPreferences(initial: Map<String, Any?>) : SharedPreferences {
        private val committed = HashMap<String, Any?>(initial)

        override fun getAll(): MutableMap<String, *> = HashMap(committed)

        override fun getString(key: String?, defValue: String?): String? =
            (committed[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (committed[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = (committed[key] as? Int) ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = (committed[key] as? Long) ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (committed[key] as? Float) ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (committed[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = committed.containsKey(key)

        override fun edit(): SharedPreferences.Editor {
            val pending = HashMap<String, Any?>()
            val handler = InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "putBoolean" -> {
                        pending[args[0] as String] = args[1]
                        proxy
                    }
                    "putString" -> {
                        pending[args[0] as String] = args[1]
                        proxy
                    }
                    "remove" -> {
                        pending[args[0] as String] = REMOVED
                        proxy
                    }
                    "clear" -> {
                        pending.clear()
                        committed.keys.toList().forEach { pending[it] = REMOVED }
                        proxy
                    }
                    "apply", "commit" -> {
                        pending.forEach { (k, v) -> if (v === REMOVED) committed.remove(k) else committed.put(k, v) }
                        pending.clear()
                        true
                    }
                    "toString" -> "InMemoryPreferences.Editor"
                    else -> throw UnsupportedOperationException("Editor.${method.name} 未实现")
                }
            }
            @Suppress("UNCHECKED_CAST")
            return Proxy.newProxyInstance(
                InMemoryPreferences::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
                handler
            ) as SharedPreferences.Editor
        }

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = throw UnsupportedOperationException("registerOnSharedPreferenceChangeListener 未实现")

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = throw UnsupportedOperationException("unregisterOnSharedPreferenceChangeListener 未实现")
    }

    private companion object {
        /** 哨兵：`Editor.remove` 用（绕开泛型 `Map<String, Any?>` 无法区分"值为 null"）。 */
        val REMOVED = Any()
    }
}
