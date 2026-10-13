package com.dsh.ui.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Toast
import com.dsh.core.DshInstances
import com.dsh.core.DshLogBus
import com.dsh.core.DshRuntime
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.ActivityDshLogsBinding
import com.mio.dialog.ItemSelectionDialog
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import kotlinx.coroutines.launch

/**
 * 日志视图 —— 控制台式，**两种视角共用同一个页面类**。
 *
 * | 视角 | 谁用 | 看什么 |
 * |---|---|---|
 * | **App 级**（[instanceId] 为 null） | 设置页 → 「日志」子页 | 启动器自己打的全部行：解压底座、安装 dsh、凭据、运行时归因…… |
 * | **实例级**（给了 [instanceId]） | 实例详情页 → 「日志」标签 | **只有这个实例**发出的行（从启动到停止） |
 *
 * ## 为什么是一个类而不是两个
 * 两者的**视图、渲染、筛选、复制、滚动逻辑完全一样**，唯一差别是"订阅哪条流"。
 * 拆成两个类等于把这 150 行抄两份，然后慢慢漂移（一份改了筛选、另一份没改）。
 * 所以差异收敛成构造函数里的一个可空参数，在 [onCreate] 里一次性选择订阅源。
 *
 * ## 订阅源怎么选
 * - `instanceId == null` → [DshLogBus.snapshot]（全局）
 * - 否则 → [DshLogBus.snapshotFor]（该实例；**首次调用即创建**那条流）
 *
 * ⚠️ **不要在 collect 里判断 `instanceId`** —— 订阅哪条流在 `onCreate` 时就要定下来，
 * 中途换流会让 `lastRevision` 的语义错乱（两条流的 revision 各自独立递增，
 * 换个实例后 revision 可能"看起来没变"，于是新实例的日志不刷新）。
 *
 * ## 设计（参考常见日志查看器）
 * - **按级别着色**：ERROR 红 / WARN 琥珀 / OK 绿 / 普通浅灰、`[tag]` 前缀用蓝色，一眼能扫出问题行；
 * - **级别筛选**：全部 / 警告及以上 / 仅错误（[ItemSelectionDialog] 选择）；
 * - **自动滚动**：开关打开时始终钉在底部；关闭后**一行都不动**；向上翻看时出现「回到底部」；
 * - **信息行**：显示当前视角、展示行数 / 总行数与**该视角自己的落盘路径**。
 *
 * ## ★ 滚动为什么必须躲开「可选中文本」这条框架路径（曾经的核心缺陷）
 * 现象（真机）：日志滑到最底部后会**瞬间弹回最上部**，与「自动滚动」开关无关。
 *
 * 机制（对着 AOSP 14 的 `TextView` / `ScrollView` 逐行核过）：
 * 1. 本页正文原来写着 `android:textIsSelectable="true"`，编译期解析走
 *    `TextView.setTextIsSelectable(true)` —— 它除了置位 `mEditor.mTextIsSelectable`，还会
 *    `setFocusableInTouchMode(true)` + `setMovementMethod(ArrowKeyMovementMethod.getInstance())`；
 * 2. 而 `TextView.setText` 里有一句 `if (mMovement != null) mMovement.initialize(this, spannable)`，
 *    `ArrowKeyMovementMethod.initialize()` 的实现就是 `Selection.setSelection(text, 0)` ——
 *    于是**每一次重绘**（本页 200ms 一次）都把光标/选择重新钉回**首个字符**；
 * 3. 这个 Selection span 变化会走到 `TextView.spanChange()` → `registerForPreDraw()`，
 *    下一帧 `TextView.onPreDraw()` 里 `curs = getSelectionEnd(); if (curs >= 0) bringPointIntoView(curs)`；
 * 4. `bringPointIntoView()` 末尾有一句 `if (requestRectWithoutFocus || isFocused()) { …; requestRectangleOnScreen(mTempRect); }`
 *    —— 光标一旦**真的拿到焦点**（本控件就是 `focusableInTouchMode`，手点一下就会拿到），
 *    它就把"第 0 行"的矩形丢给父链；
 * 5. `ScrollView.requestChildRectangleOnScreen` → `computeScrollDeltaToGetChildRectOnScreen`，
 *    命中 `rect.top < screenTop && rect.bottom < screenBottom` 分支：
 *    `scrollYDelta -= screenTop - rect.top;`（rect.top = 0 ⇒ 正好 = -scrollY）
 *    → `smoothScrollBy(0, -scrollY)` → 滚到 **y = 0，也就是最顶部**。
 *
 * 所以"滑到最底"只是**用户注视的位置**、不是触发条件：只要 `scrollY > 0`（内容高于一屏）就会弹。
 * 内容不满一屏时 y = 0 本来就是底部，看不出来 —— 这正是"日志一多才开始跳、而且总在底部"的由来。
 * 也解释了为什么关掉「自动滚动」照样跳：这条链**完全在框架里**，与我们的滚动调用无关。
 *
 * 做法：正文一直保持**不可选中**（布局里不挂该属性，代码里再显式按掉一次）：
 * 于是 `mMovement` 为 null —— 上面第 2~5 步整条链先是断在"没有光标位置"，
 * 再断在"`onPreDraw` 改走 `bringTextIntoView()`、不再对父视图提任何要求"。
 * 另外挂一条焦点回调兜底：控件拿到焦点时把**没有选择**的光标清掉（第 4 步的前提就不成立）。
 * 代价：正文不能再长按选中/复制 —— 复制本来就有「复制全部」按钮（[copyAll]），功能没有丢。
 *
 * ⚠️ **不要把 `textIsSelectable` 加回去**，那会当场把上面这条链复活。
 *
 * ## 分级色为什么走颜色资源而不是写在代码里
 * 这 5 个色是**语义色**（错误 / 警告 / 成功 / 普通 / 来源），必须与日志底板保持稳定对比 ——
 * 而底板本身要跟着亮暗模式换（`@color/dsh_log_console_bg` 在 `values/` 与 `values-night/`
 * 各一套）。分级色同理：亮色模式下用深色系压在浅底上，暗色模式下换成一套提亮降饱和的。
 * 写成 `values-night` 里的同名覆盖，就是让系统在**资源解析期**替我们选对那一套，
 * 代码只管"这一行是错误级"这一件事。
 *
 * ⚠️ **不要把它们改回 `const val` 硬编码色**：硬编码的值无法参与 night 覆盖，
 * 换到暗色主题就会糊成一片（这正是改造前的问题）。
 *
 * 渲染策略沿用之前的性能处理：只在快照的 revision 变化时重建文本。
 */
class DshLogsUI(
    context: Context,
    /**
     * 要看的实例；`null` = App 级全局日志。
     *
     * 默认值 `null` 是给设置页那个子页用的（它只想看 App 级），
     * 实例详情页则显式传实例 id。默认值让"App 级"成为不写参数时的自然选择 ——
     * 毕竟绝大多数日志页都只是想看看出什么事了。
     */
    private val instanceId: String? = null
) : DshPageUI(context, R.layout.activity_dsh_logs) {

    private val binding = ActivityDshLogsBinding.bind(contentView)
    private var lastRevision = -1L
    private var lines: List<String> = emptyList()
    private var filter: Filter = Filter.ALL

    /**
     * 上一帧"过滤后"的行数（[render] 每次渲染后更新；`-1` = 还没渲染过第一帧）。
     *
     * BL-114：补偿的**回落口径**输入（锚定失败时用，见纯函数 [scrollCompensation]）。
     * 为什么是"过滤后"而不是原始行数：裁剪发生在过滤之后（`filtered.takeLast(maxLines)`），
     * 只有过滤后的行会进入正文、参与像素位移；原始行里被筛掉的部分与视觉无关。
     */
    private var lastFilteredCount = -1

    /**
     * 上一帧展示窗口（`shown`）首行的**引用** —— BL-114 位移补偿的首选锚。
     *
     * 为什么用引用而不是下标/行号：总线里每行 String 是独立对象、过滤与裁剪只搬运引用，
     * 所以"同一个对象"就是"同一行日志"的精确身份；换行/折行都不影响它。锚在旧窗口里
     * 恒为位置 0（`takeLast` 的起点），在新列表里用 `===` 找到它的新位置即知"窗口上移了几行"。
     *
     * ⚠️ 持有一个 String 的强引用无内存风险：它本来就被总线的环形缓冲（≤2000 行）持有。
     */
    private var lastShownFirst: String? = null

    /** BL-114：lineHeight 取 0 时只报一次英文日志（见 [render] 的补偿分支），避免每帧刷屏 */
    private var scrollCompensationWarned = false

    /** BL-114：待执行的补偿位移（px，负=上移）。同帧窗口内多次 render 会在 pre-draw 前累加。 */
    private var pendingCompensationPx = 0

    /** BL-114：补偿的一次性预绘制监听（与 [pinPreDrawListener] 同构，生命周期同管） */
    private var compensationListener: ViewTreeObserver.OnPreDrawListener? = null

    /** BL-113：本次钉底请求里预绘制回调已执行的次数（[pinToBottomAfterLayout] 清零；上限 [MAX_PIN_ATTEMPTS]） */
    private var pinAttempts = 0

    /** BL-113：本次钉底请求是否带"确认针"（「回到底部」的 fling(0) 收尾可能落在钉底帧的绘制里，需下一帧再确认一次） */
    private var pinConfirmPending = false

    /**
     * 5 个分级色，在 [onCreate] 里解析一次，并在**主题刷新时重新解析**。
     *
     * 为什么要缓存 + 重注册，而不是每次渲染现取：`render()` 里每一行都要判一次级别，
     * 一屏最多 1500 行 —— 每行都 `getColor()` 就是每帧上千次资源查找。
     *
     * 为什么要重注册：亮暗切换时 Activity **不会重建**（外壳的 `configChanges` 含 `uiMode`），
     * 所以"在 onCreate 里取一次"会永远停在旧配色上。必须挂 [ThemeEngine.registerEvent]，
     * 在回调里重新解析并重绘 —— 这跟控件自己的主题刷新是同一套机制。
     */
    private lateinit var levelColors: LevelColors

    /** 一屏渲染要用的全部颜色（值随亮暗模式变，所以只在主题刷新/首次进入时解析） */
    private class LevelColors(val error: Int, val warn: Int, val ok: Int, val info: Int, val tag: Int)

    private fun resolveColors() = LevelColors(
        error = context.getColor(R.color.dsh_log_error),
        warn = context.getColor(R.color.dsh_log_warn),
        ok = context.getColor(R.color.dsh_log_ok),
        info = context.getColor(R.color.dsh_log_info),
        tag = context.getColor(R.color.dsh_log_tag)
    )

    private enum class Filter { ALL, WARN, ERROR }

    /** 单次渲染的最大行数（超出只显示末尾，避免超长日志把界面拖垮） */
    private val maxLines = 1500

    /**
     * 正文获得焦点时清掉"框架自己塞进去的空光标"。
     *
     * 为什么需要它：`bringPointIntoView()` 只在子控件**已获得焦点**时才会去调
     * `View.requestRectangleOnScreen()` —— 也就是那条真正把父 ScrollView 拽走的路径。
     * 而"会不会拿到焦点"取决于控件是不是 `focusableInTouchMode`/可点击这些**跟系统版本、
     * 主题都有关的行为**，不归我们控制。与其赌它拿不到焦点，不如在拿到的那一刻把光标位置清掉：
     * 没有位置，`onPreDraw()` 里那句 `if (curs >= 0)` 就不成立，拽走父视图的请求根本发不出来。
     *
     * 为什么是**一个具名实例**而不是就地写的 lambda：本页会在 [onDestroy] 里反注册它
     * （正文是 `contentView` 的子视图，而页面会被 ViewPager2 回收重建）—— 具名引用是"注销得掉"的前提。
     */
    private val logTextFocusListener = View.OnFocusChangeListener { v, hasFocus ->
        val tv = v as? android.widget.TextView
        // `hasSelection()` 为真 = 用户真的拖出了一段，别去动它
        if (hasFocus && tv != null && !tv.hasSelection()) {
            val text = tv.text
            if (text is android.text.Spannable) android.text.Selection.removeSelection(text)
        }
    }

    /** 本帧的预绘制监听（只在 [pinToBottomAfterLayout] 挂上到预绘制回调之间非空，其余时间为 null） */
    private var pinPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null

    override fun onCreate() {
        super.onCreate()

        levelColors = resolveColors()
        // 亮暗切换不重建 Activity（外壳 configChanges 含 uiMode），必须自己挂主题刷新：
        // 否则分级色会停在进入这一页时的配色上。registerEvent 注册时会立即执行一次，
        // 所以上面那行赋值不是为了"先有个值"，而是为了 render() 在任何路径下都有值可用。
        ThemeEngine.getInstance().registerEvent(contentView) {
            levelColors = resolveColors()
            render()
        }

        binding.btnClear.setOnClickListener {
            // BL-078：清空是**破坏性**动作（本会话的排障上下文就此消失，且无撤销），
            // 与「删除实例」同级 —— 先确认再清。这是本页三个图标里唯一造成不可逆损失的操作。
            FCLAlertDialog.Builder(context)
                .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
                .setTitle(context.getString(R.string.dsh_action_clear))
                .setMessage(context.getString(R.string.dsh_logs_clear_confirm))
                .setPositiveButton(context.getString(R.string.dialog_positive)) {
                    // 清哪个范围要跟视角一致：实例页里点"清空"只该清掉这个实例的日志，
                    // 顺手把全局也清了会让人以为"别的东西的日志也丢了"（反之亦然）。
                    if (instanceId == null) DshRuntime.clearLogs() else DshLogBus.clearFor(instanceId)
                    toast(context.getString(R.string.dsh_logs_cleared))
                }
                .setNegativeButton(context.getString(R.string.dialog_negative), null)
                .create()
                .show()
        }
        binding.btnCopy.setOnClickListener { copyAll() }
        binding.btnFilter.setOnClickListener { pickFilter() }
        binding.btnJumpBottom.setOnClickListener {
            // 用户主动动作：**无条件**钉到底 —— 刻意不受「自动滚动」开关约束（理由见 [render]）。
            // 走"布局落定后再钉"而不是 `post { fullScroll }`：后者与"新文本测量完成"没有先后关系，
            // 按旧高度算出的位置随后会被布局 clamp 一次 —— 观感就是先跳一下再落位。
            //
            // ★ BL-113：只发静态 `scrollTo` 会被**进行中的惯性滚动（fling）夺回** ——
            //    按钮在滚动区之外（xml 里与 log_scroll 同级），点它不会向滚动区送 DOWN/UP，
            //    fling 的余速继续按它自己的 spline 推动 scrollY；一次性静态 scrollTo 只在
            //    下一帧短暂落底，随即被余速盖走（静止时点击才有效，所以症状是"有时无效"）。
            //    先调 `fling(0)` 用**零速度**接管滚动器：接管会替换 Scroller 的动画状态，
            //    旧惯性就此终止（静止点击=无位移的接管）。注意零速接管自身可能在**接管帧的
            //    draw** 里做一次"收尾写"（把位置写回接管时刻的 scrollY）——它可能恰落在本次
            //    钉底那一针之后；这是 [attachPinListener] 里"确认帧"存在的原因，两者配合才闭环。
            //    （为什么不选 `smoothScrollBy(0,0)` 兜底：它走 250ms 的零距动画，会在动画存续的
            //    每一帧把位置**反复**写回调用时刻的值，与钉底正面冲突，比"至多一次收尾写"糟得多。）
            //    confirmAfterFling=true 让钉底排一根"确认针"（见 [pinToBottomAfterLayout]）——
            //    零速接管的收尾写一旦晚于首针，确认针会把它盖回底部；收尾只有一次，不会连环夺回。
            //    先作废可能已排队的裁剪补偿：它与"钉到底"方向相反，留着只会同帧先补后钉白做。
            cancelPendingCompensation()
            runCatching { binding.logScroll.fling(0) }
            pinToBottomAfterLayout(confirmAfterFling = true)
        }

        // 向上翻看时显示「回到底部」，贴底时隐藏。
        // `!canScrollVertically(1)` 一个判据就够：「还能往下滚」为假 = 已经在底部，
        // 或者整段日志还不满一屏（本来就没有"回到底部"这回事）—— 两种情况都该收起按钮。
        // ⚠️ 本监听只负责按钮显隐，**不碰滚动位置**：否则"贴底时跟随"会有第二条隐形实现，
        //    两条路径迟早打架（例如用户上滑经过底部的那一瞬间被拉回去）。
        binding.logScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            val atBottom = !binding.logScroll.canScrollVertically(1)
            binding.btnJumpBottom.visibility = if (atBottom) View.GONE else View.VISIBLE
        }

        // ★ 正文不可选中（布局里也已去掉 `textIsSelectable`，这里再保险一次）：
        //   可选中 = `mMovement = ArrowKeyMovementMethod`，那条路径会在**每次重绘**把光标钉回
        //   第 0 个字符，进而在 `onPreDraw()` 里对父 ScrollView 请求"把第 0 行显示出来"
        //   → 整页弹回顶部。完整机制见类注释「滚动为什么必须躲开可选中文本」。
        //   保留这一行（而不是只在 XML 里删属性）：万一将来有人把属性加回布局，
        //   这里会把它当场按掉 —— 同一件事写两遍是因为后果（整页乱跳）比冗余更贵。
        binding.logText.setTextIsSelectable(false)
        // 手点一下正文仍可能拿到**焦点**（是不是可选中都可能）。焦点本身无害，但"焦点 +
        // 任何一次 `bringPointIntoView`"就是"父视图被拽走"的组合；这里只清掉"框架自己塞进去的
        // 空光标"（理由与边界见 [logTextFocusListener]）。
        binding.logText.setOnFocusChangeListener(logTextFocusListener)

        binding.runtimeStatus.text = scopeLabel()

        // ★ 订阅源在**这里**定下来（理由见类注释：中途换流会让 lastRevision 错乱）
        val source = instanceId?.let { DshLogBus.snapshotFor(it) } ?: DshLogBus.snapshot
        scope.launch {
            source.collect { snap ->
                if (snap.revision == lastRevision) return@collect
                lastRevision = snap.revision
                lines = snap.lines
                render()
            }
        }
        // 运行状态只对实例视角有意义：App 级日志页显示"某个实例在跑"是噪音
        if (instanceId == null) {
            scope.launch {
                DshRuntime.state.collect { st -> binding.runtimeStatus.text = describe(st) }
            }
        }
        render()
    }

    /** 顶部那行"现在看的是谁的日志"。实例名取不到时回落到 id —— 至少能对上落盘文件名 */
    private fun scopeLabel(): String = if (instanceId == null) {
        context.getString(R.string.dsh_logs_scope_app)
    } else {
        val name = DshInstances.byId(instanceId)?.name ?: instanceId
        context.getString(R.string.dsh_logs_scope_instance, name)
    }

    private fun describe(st: DshRuntime.State): String = when (st) {
        is DshRuntime.State.Idle -> context.getString(R.string.dsh_state_idle)
        is DshRuntime.State.Starting -> context.getString(R.string.dsh_state_starting, st.name)
        is DshRuntime.State.Running -> context.getString(R.string.dsh_state_running_short, st.name, st.port)
        is DshRuntime.State.Stopping -> context.getString(R.string.dsh_state_stopping, st.name)
        is DshRuntime.State.Failed -> context.getString(R.string.dsh_state_failed, st.reason)
        is DshRuntime.State.Exited -> context.getString(R.string.dsh_runtime_exited, st.code)
    }

    private fun pickFilter() {
        val labels = listOf(
            context.getString(R.string.dsh_logs_filter_all),
            context.getString(R.string.dsh_logs_filter_warn),
            context.getString(R.string.dsh_logs_filter_error),
        )
        ItemSelectionDialog(
            context, context.getString(R.string.dsh_logs_filter_title), labels, true, filter.ordinal
        ) { pos, _ ->
            filter = Filter.entries[pos]
            // `forceScroll = true` 是**刻意的**：换筛选条件是用户主动动作，内容当场换了一整套，
            // 停在原来的像素位置只会看到一段不相干的中段。所以这一次无视「自动滚动」开关直接钉底
            // —— 与「回到底部」按钮同一条理由，是"用户要求"而不是"替用户决定"。
            render(forceScroll = true)
            // 筛选按钮是图标（FCL 规范），无法在按钮上显示当前级别 → 用一次轻提示反馈
            Toast.makeText(context, labels[pos], Toast.LENGTH_SHORT).show()
        }.show()
    }

    /** 按级别决定整行颜色 */
    private fun levelOf(line: String): Int = when {
        ERROR_RE.containsMatchIn(line) -> levelColors.error
        WARN_RE.containsMatchIn(line) -> levelColors.warn
        OK_RE.containsMatchIn(line) -> levelColors.ok
        else -> levelColors.info
    }

    private fun matches(line: String): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.WARN -> ERROR_RE.containsMatchIn(line) || WARN_RE.containsMatchIn(line)
        Filter.ERROR -> ERROR_RE.containsMatchIn(line)
    }

    private fun render(forceScroll: Boolean = false) {
        val atBottom = !binding.logScroll.canScrollVertically(1)
        // K27 部分缓解（早退，**不改变语义**）：Filter.ALL 下 matches() 恒真，
        // `lines.filter(::matches)` 只是白白复制一份 ≤2000 元素的列表（每渲染一次一份）。
        // 直接复用快照里的列表 —— Snapshot 发布后不再被修改（flush 里传入的是防御性副本），
        // filtered 在本函数内只读，共享安全。
        val filtered = if (filter == Filter.ALL) lines else lines.filter(::matches)
        val shown = if (filtered.size > maxLines) filtered.takeLast(maxLines) else filtered

        val sb = SpannableStringBuilder()
        shown.forEachIndexed { i, line ->
            val start = sb.length
            sb.append(line)
            sb.setSpan(
                ForegroundColorSpan(levelOf(line)), start, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            // `[tag]` 前缀用另一种颜色，便于区分来源（setup / start-dsh / bootstrap / proot…）
            // K27 部分缓解（早退，**严格不改变语义**）：TAG_RE 以 `^` 锚定且未开 MULTILINE，
            // 只能在位置 0 匹配 —— 行首不是 `[` 时 find() 必然返回 null。先做一次
            // `startsWith("[")` 的字符比较，跳过绝大多数行（本页每行都带 `HH:mm:ss  ` 时间戳
            // 前缀，行首为 `[` 的占比很小）上的一次正则引擎调度。
            // ⚠️ 级别三正则的完整降本（级别在总线入库时预计算）会改 DshLogBus 的快照结构，
            //    是批 C 的 K27 重构，不在本波 —— 本波只做上面这类"免费"的早退（另见 render
            //    首行 Filter.ALL 的列表复用），收益估计见报告 K27 节。
            val tag = if (line.startsWith("[")) TAG_RE.find(line) else null
            if (tag != null && tag.range.first == 0) {
                sb.setSpan(
                    ForegroundColorSpan(levelColors.tag), start, start + tag.value.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (i != shown.lastIndex) sb.append('\n')
        }
        binding.logText.text = sb

        // ── BL-114：裁剪位移补偿 ──────────────────────────────────────────────
        // 长会话里展示窗口只含"过滤后"的末尾（`filtered.takeLast(maxLines)`）——每来一批新行，
        // 窗口顶部要么被 takeLast 裁掉、要么被总线环形缓冲挤掉，剩余内容在视口里整体**上移**
        // "移出的行数 × 行高"，而 scrollY 停在旧值不动（症状：读历史"被传送一段距离"）。
        // 补偿 = 在 setText 触发的布局落定后 `scrollBy(0, -k×行高)`，把同一段文字钉回视口里的
        // 同一像素位置（视口锚定）。
        //
        // k 怎么数（关键，两档；数学本体都是文件末尾的 public 纯函数，单测钉住）：
        // ① 锚定法（首选，[scrollCompensationAnchored]）：记上帧展示窗口首行的**引用**
        //    （[lastShownFirst]）——总线里每行 String 唯一、过滤/裁剪只搬运引用，所以身份比较
        //    （`===`）就能在新 filtered 里定位它。锚在旧窗口里位置恒为 0；窗口起点
        //    （trimmedAway = takeLast 裁掉的量）比锚的新位置大多少行，内容就上移了多少行。
        // ② 计数法回落（[scrollCompensation]）：锚被挤出总线环形缓冲时（一帧内输出突发把
        //    整段挤掉；锚定法找不到就落到这里），按"两帧窗口起点的差"估算。此时可能**低估**
        //    （总线已达 2000 行上限后就地挤头、过滤后行数不变），宁少补勿坏补。
        //    ⚠️ 只靠计数法会漏掉"总线已达上限"的稳态（行数不变、内容照样整体上移）——
        //    这正是锚定法必须存在的原因。
        //
        // 触发条件（与"要不要跟随"共用同一判据，避免出现第二份策略）：
        // · 本次会 pin（forceScroll / 开关开 / 贴底）时**不做** —— pin 落下后位置本来就对，
        //   补偿只会与它打架（且此刻会把上一帧可能排队的补偿一并作废，见下）；
        // · 不 pin 时才补偿（另有"上次 render 的 pin 还挂着"的小守卫，见下）。
        // 时序：scrollBy 必须发生在"新文本测量/布局完成"之后，否则按旧高度 clamp 会夹错。
        // 因此不放裸 `post`（post 与布局完成没有先后关系 —— 与 [pinToBottomAfterLayout]
        // 顶部注释同一条理由），而是挂一次性 OnPreDraw 监听：回调时布局已落定
        // （ViewRootImpl 的 测量 → 布局 → dispatchOnPreDraw 顺序保证）。
        // 行高：`logText.lineHeight`（TextView 的 public API，含 lineSpacingExtra）；
        // ≤0（控件还没被测量过）时跳过并只报一次英文日志 —— 不猜行高。
        val pinned = forceScroll || binding.switchAutoScroll.isChecked || atBottom
        if (pinned) {
            // pin 覆盖一切：上一帧排队的补偿与"钉到底"语义相反，作废之（见 [compensateAfterLayout]）
            cancelPendingCompensation()
        } else {
            val lineHeight = binding.logText.lineHeight
            val anchor = lastShownFirst
            var anchorIndex = -1
            if (anchor != null) {
                for (idx in filtered.indices) {
                    if (filtered[idx] === anchor) { anchorIndex = idx; break }
                }
            }
            val dy = when {
                // 首帧：没有"上一帧位置"可锚定，不猜
                anchor == null -> 0
                anchorIndex >= 0 -> scrollCompensationAnchored(anchorIndex, filtered.size, maxLines, lineHeight)
                else -> scrollCompensation(lastFilteredCount, filtered.size, maxLines, lineHeight)
            }
            // 守卫：上一次 render 决定的 pin 可能仍挂在预绘制队列里（同帧窗口内状态又变了才会
            // 出现）。pin 覆盖一切 —— 两个监听若同时挂着，回调之间没有顺序保证，补偿可能加在
            // 钉底之上把位置抬离底部。此时放弃这一帧补偿（钉到底已是正确答案，视口锚定的
            // 前提"用户不在底部"也不再成立）。
            if (dy != 0 && pinPreDrawListener == null) {
                // 同帧窗口内多次 render 时累加：监听一次、合并补一次（布局只认最终内容高度）
                pendingCompensationPx += dy
                compensateAfterLayout()
            }
            // 行高取不到而确实存在位移时留一行英文日志可查（只报一次防刷屏）。
            // 只看冷路径（lineHeight≤0 的帧）：这里用 rows 口径纯函数复算"本应补偿多少行"。
            if (lineHeight <= 0 && !scrollCompensationWarned) {
                val rows = when {
                    anchor == null -> 0
                    anchorIndex >= 0 -> scrollShiftRowsAnchored(anchorIndex, filtered.size, maxLines)
                    else -> scrollShiftRows(lastFilteredCount, filtered.size, maxLines)
                }
                if (rows > 0) {
                    scrollCompensationWarned = true
                    DshLogBus.append(
                        "[logs] trim compensation skipped: lineHeight=0 (view not measured yet)"
                    )
                }
            }
        }
        // ⚠️ 两个跟踪量在 pinned 与否都要更新：跳过补偿的帧若不同步基准，下一帧的对账
        //    会把"钉底那一帧的位移"错记到后面。
        lastFilteredCount = filtered.size
        lastShownFirst = shown.firstOrNull()

        val hidden = lines.size - filtered.size
        val truncated = filtered.size - shown.size
        binding.logInfo.text = buildString {
            append(context.getString(R.string.dsh_logs_info, shown.size, lines.size))
            if (hidden > 0) append(" · ").append(context.getString(R.string.dsh_logs_hidden, hidden))
            if (truncated > 0) append(" · ").append(context.getString(R.string.dsh_logs_truncated, truncated))
            // 落盘路径也按视角给：排障时"这个实例的日志写到哪了"是最先要问的问题，
            // 指到全局那个文件等于把人引到错的地方
            append(" · ").append(
                instanceId?.let { DshLogBus.instanceLogFile(it).absolutePath }
                    ?: com.dsh.core.DshPaths.LOG_FILE
            )
        }

        // ── 滚动策略（这里是"什么时候该跟随"的**唯一**实现处）──────────────────────
        // 1) 开关打开：始终钉底 —— "跟随"的语义由开关表达，用户关掉它就不再跟随；
        // 2) 开关关闭、且**本次刷新时**用户正贴在底部：跟随。这样关掉开关后，"正盯着尾巴看"
        //    的人不会被突然停下（日志不滚了反而像卡住），而一旦往上翻，位置立刻不再被抢；
        // 3) forceScroll：**用户主动动作**（换级别筛选、点「回到底部」），刻意无视开关 ——
        //    它们是"用户要求现在到底部"，不是"替用户决定"。
        //
        // ⚠️ `atBottom` 必须在 `logText.text = sb` **之前**取（见方法首行）：内容被
        //    `takeLast(maxLines)` 从顶部裁掉后，旧内容整体上移，赋值之后再问
        //    `canScrollVertically(1)` 会把"用户只是翻到了旧内容底部"误判成"已经贴底"，
        //    于是每来一行都把他往下拽一次 —— 正是"向上翻看被抢走位置"的形状。
        //    同一段"要不要跟随"的判断里，两个判据必须量的是**同一个时刻**的内容。
        // ⚠️ 这里刻意**不**用 `fullScroll(FOCUS_DOWN)`：它按调用时刻的旧高度算完 scrollY，
        //    紧接着的布局再把它 clamp 一次，于是先跳一下才落位。改成"布局落定后再钉"，一次到位。
        if (pinned) {
            pinToBottomAfterLayout()
        }
    }

    /**
     * 把视图钉到最底部 —— **等这一帧的测量/布局落定之后再钉**。
     *
     * 为什么不能直接 `post { fullScroll(FOCUS_DOWN) }`：`post` 只保证"晚于当前这一次调用"，
     * 与"新文本测量完成"之间**没有任何先后关系**。若它赶在测量之前执行，`ScrollView` 量到的
     * 还是旧高度，算出的 scrollY 随后被布局 clamp 一次 —— 表现为先跳一下再落位。
     *
     * 这里挂在 `OnPreDrawListener` 上：`ViewRootImpl.performTraversals()` 里的顺序是
     * **测量 → 布局 → `dispatchOnPreDraw()` → 绘制**，所以回调触发时新内容的真实高度已经确定
     * （`logText.bottom` 就是它），一次就能滚到正确位置。
     *
     * ⚠️ **有界的一次性**：回调里第一件事就是注销自己。理由不是省事 —— 常驻的话它会在**用户
     *    拖拽的每一帧**都把视图拉回底部，手根本拖不动；而"要不要继续跟随"这件事已经在 [render]
     *    里按日志流（~5Hz）判断过了，比逐帧判断更合适。BL-113 的重试/确认（见下）也遵守
     *    [MAX_PIN_ATTEMPTS] 硬上限 —— "有界的一次性"，不是常驻。
     * ⚠️ 本方法只负责"滚到底"，**不做任何"该不该跟随"的判断**：那个判断只在 [render] 一处，
     *    否则第二次实现会漂移成"某些路径偷偷跟随"。
     *
     * ★ BL-113：一次钉底请求最多执行 [MAX_PIN_ATTEMPTS]（2）次预绘制钉，两种续针理由：
     *   1. **同帧复查**：钉完后 `canScrollVertically(1)` 仍为真 = 布局在预绘制里又变化 /
     *      同帧内容二次增长 —— 立即再挂一针，下一帧再钉；
     *   2. **确认针**（只在对 [confirmAfterFling] = true 的请求启用）：下一帧无条件再钉一次。
     *      存在的理由：点击「回到底部」时发的 `fling(0)` 用零速接管滚动器，其"收尾"可能落在
     *      **本帧的绘制阶段**（预绘制之后），把 scrollY 写回接管时刻 —— 同帧复查看不见它，
     *      必须下一帧再确认。收尾是一次性的（写完即 finished），确认针之后不会再被夺回。
     */
    private fun pinToBottomAfterLayout(confirmAfterFling: Boolean = false) {
        if (pinPreDrawListener != null) {
            // 已有待发钉底：把它升级为"带确认针"即可（针已排好，不重复排）
            pinConfirmPending = pinConfirmPending || confirmAfterFling
            return
        }
        pinAttempts = 0
        pinConfirmPending = confirmAfterFling
        attachPinListener()
    }

    /**
     * 挂上钉底监听（[pinToBottomAfterLayout] 的首发与续针共用）。预绘制回调里：
     * 1) 先摘自己；2) `scrollTo` 钉底；3) 按"同帧复查 + 确认针"两个理由决定是否续挂
     *    （续挂受 [MAX_PIN_ATTEMPTS] 约束，见方法注释）。
     */
    private fun attachPinListener() {
        pinAttempts++
        // 每次现取 viewTreeObserver：它在视图 detach/attach 之间会被替换，
        // 缓存下来的那个旧对象上调用 remove 是无效的（监听永远摘不掉）。
        val observer = binding.logScroll.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                removePinListener()
                // 直接 `scrollTo` 而不是 `fullScroll`：后者经 `doScrollY` 可能启动一个
                // 250ms 的平滑滚动动画（距上次滚动超过 ANIMATED_SCROLL_GAP 时），
                // 而"贴底跟随"要的是一次到位的静态位置。
                // 这里给的是内容的完整高度；即使传大了也不用担心 —— `ScrollView.scrollTo`
                // 内部会按 `childHeight - viewportHeight` 夹紧到最大值。
                binding.logScroll.scrollTo(0, binding.logText.bottom)
                val confirm = pinConfirmPending
                pinConfirmPending = false
                // 同帧复查：仍可下滚 = 没钉到底；或本请求带确认针（fling 收尾可能在本帧
                // 绘制里写回）—— 两种都再钉一针。到上限即止（防常驻，理由见方法注释）。
                // ★ R14-V2C N1：确认针**不计预算** —— 同帧复查若先把 MAX_PIN_ATTEMPTS
                //   耗尽，按钮承诺的"收尾确认"会被静默丢弃（逻辑不严）。确认针最多补一次
                //   （pinConfirmPending 一次性），单独给它一次续针机会。
                if (binding.logScroll.canScrollVertically(1)) {
                    if (pinAttempts < MAX_PIN_ATTEMPTS) attachPinListener()
                } else if (confirm) {
                    attachPinListener()
                }
                return true
            }
        }
        pinPreDrawListener = listener
        observer.addOnPreDrawListener(listener)
    }

    /** 注销待执行的钉底监听（幂等；视图未 attach 时 viewTreeObserver 取不到已注册的监听也无妨） */
    private fun removePinListener() {
        val listener = pinPreDrawListener ?: return
        pinPreDrawListener = null
        // 视图已 detach 时 `getViewTreeObserver()` 会返回一个新的、空的对象，
        // 直接 remove 是安全的（不存在则什么都不做）；仍然包一层兜底，避免极端时序下抛异常
        // 把一次普通的日志刷新变成崩溃。
        runCatching { binding.logScroll.viewTreeObserver.removeOnPreDrawListener(listener) }
    }

    /**
     * BL-114：把 [pendingCompensationPx] 里的补偿位移在**布局落定后**一次性应用（预绘制时
     * `scrollBy`），随后清零。挂监听与摘监听和钉底同构（见 [removePinListener] 的时序说明）。
     * 幂等：已有待发监听时不重复挂（新累积的量由已挂监听统一消费）。
     */
    private fun compensateAfterLayout() {
        if (compensationListener != null) return
        val observer = binding.logScroll.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                // 顺序：先摘监听（只动监听引用），后取/清零待发量 —— 反过来的话
                // 摘监听顺带清零会把这次要应用的位移抹掉（值读出来永远是 0）。
                removeCompensationListener()
                val dy = pendingCompensationPx
                pendingCompensationPx = 0
                if (dy != 0) binding.logScroll.scrollBy(0, dy)
                return true
            }
        }
        compensationListener = listener
        observer.addOnPreDrawListener(listener)
    }

    /** 注销待执行的补偿监听（幂等；**不动** [pendingCompensationPx] —— 由调用方决定清除语义） */
    private fun removeCompensationListener() {
        val listener = compensationListener ?: return
        compensationListener = null
        runCatching { binding.logScroll.viewTreeObserver.removeOnPreDrawListener(listener) }
    }

    /** 作废一切未应用的补偿（摘监听 + 清零）—— pin 到来时用，避免两个方向的滚动同帧打架 */
    private fun cancelPendingCompensation() {
        removeCompensationListener()
        pendingCompensationPx = 0
    }

    private fun copyAll() {
        // 同"清空"：复制范围与视角一致（实例页里复制出来的是这个实例的日志）
        val text = DshLogBus.export(instanceId)
        if (text.isEmpty()) {
            toast(context.getString(R.string.dsh_logs_empty))
            return
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dsh log", text))
        toast(context.getString(R.string.dsh_logs_copied))
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        // 本页用 contentView 当主题刷新的 key，销毁时注销（页面的 contentView 会被复用/丢弃，
        // 不注销就留下一条指向它的回调）
        ThemeEngine.getInstance().unregisterEvent(contentView)
        // 正文的焦点监听由本页注册；页面在 ViewPager2 里会被回收重建，这里反向解掉，
        // 免得被回收的页面仍挂在还活着的视图上。预绘制监听同理（见 [removePinListener] 的时序说明）。
        binding.logText.setOnFocusChangeListener(null)
        // 页面可能在"请求钉底"与"这一帧预绘制"之间被回收，落到旧 ViewTreeObserver 上的监听要一起摘掉。
        // BL-114 的补偿监听同理；顺带丢弃未应用的补偿量（页面都回收了，没有"视口锚定"可言）。
        removePinListener()
        cancelPendingCompensation()
        super.onDestroy()
    }

    companion object {
        /**
         * BL-113：一次钉底请求最多执行几次预绘制钉底（首发 1 次 + 复查未到底再补 1 次）。
         * 有硬上限的理由：`canScrollVertically(1)` 在极端内容/时序下可能一直为真，
         * 无上限重试会把"一次性监听"悄悄变成常驻监听 —— 那就回到了"每帧拽回底部、手拖不动"的老路。
         */
        private const val MAX_PIN_ATTEMPTS = 2

        private val ERROR_RE =
            Regex("""error|failed|failure|fatal|exception|denied|错误|失败|异常|拒绝|不可用""", RegexOption.IGNORE_CASE)
        private val WARN_RE = Regex("""warn|警告|注意""", RegexOption.IGNORE_CASE)
        private val OK_RE = Regex("""\bok\b|ready|done|success|passed|通过|完成|就绪""", RegexOption.IGNORE_CASE)
        private val TAG_RE = Regex("""^\[[^\]]+\]""")
    }
}

/**
 * BL-114 纯函数：计数法补偿量（px）—— 展示窗口起点从 [oldCount] 到 [newCount] 的位移。
 *
 * 数学推导 —— `render()` 里 `filtered.takeLast(maxLines)` 只展示"过滤后"的末尾窗口：
 * ```
 * trimmedAway(n) = max(0, n - maxLines)         // n 行过滤结果里被从顶部裁掉的行数
 * k = trimmedAway(newCount) - trimmedAway(oldCount)   // 两帧之间展示窗口起点的位移行数
 * dy = -k * lineHeight                          // 内容整体上移 k 行 ⇒ scrollY 同步上移补偿
 * ```
 * 例：maxLines=1500，上一帧 1500 行（无裁剪），这一帧 1520 行（裁 20）——
 * 展示窗口起点从第 1 行（1-based）移到第 21 行：视口里同一段文字整体上移 20×行高，
 * scrollY 不减 20×行高的话用户读到哪就被推走多远（症状：读历史"传送一段距离"）。
 *
 * ⚠️ 语义边界：这条路只覆盖"过滤后行数变化"引起的位移；总线已达环形上限（2000 行）
 *    后就地挤头的稳态靠**锚定法**（[scrollCompensationAnchored]），本函数会低估 → 0。
 *    这是刻意的降级（宁少补勿坏补），见 render() 的注释。
 *
 * 边界（单测钉住）：
 * - 不裁剪（两帧都 ≤ maxLines）→ 0；
 * - 首帧（[oldCount] < 0 = 还没渲染过）→ 0（没有"上一帧位置"可锚定，不猜）；
 * - [lineHeight] ≤ 0（控件未测量）→ 0（宁可不动，不拿错的行高乱滚）；
 * - 内容被清空 / 缩短（k ≤ 0）→ 0（不向上追溯，只补偿"从顶部裁掉"的那部分）。
 *
 * ★ 必须 public：单测在独立模块编译（见任务硬性规则 5）。
 */
fun scrollCompensation(oldCount: Int, newCount: Int, maxLines: Int, lineHeight: Int): Int {
    val k = scrollShiftRows(oldCount, newCount, maxLines)
    return if (lineHeight > 0 && k > 0) -k * lineHeight else 0
}

/**
 * BL-114 纯函数：计数法"本帧内容上移了几行"（≥0）。与 [scrollCompensation] 同源
 * （后者只是乘上行高并取负）—— 单测同时钉住两个口径，防止漂移。
 */
fun scrollShiftRows(oldCount: Int, newCount: Int, maxLines: Int): Int {
    if (oldCount < 0 || maxLines <= 0) return 0
    return (scrollTrimmedAway(newCount, maxLines) - scrollTrimmedAway(oldCount, maxLines))
        .coerceAtLeast(0)
}

/**
 * BL-114 纯函数：**锚定法**补偿量（px）—— 上一帧窗口首行（锚）在新 `filtered` 里的下标为
 * [anchorIndex]，本帧窗口起点为 trimmedAway(newFilteredCount)。
 *
 * 几何：锚在旧窗口里位于位置 0（takeLast 的起点）。本帧窗口起点右移 (trimmedAway 减锚的新
 * 下标) 行 ⇒ 锚（以及它下方的所有内容）在视口里上移同样多的行；scrollY 需同步减掉
 * `行数 × [lineHeight]` 以保持锚的屏幕位置不变。
 *
 * 覆盖计数法够不着的场景：总线已达环形上限后就地挤头 —— 过滤后行数两帧相同（计数法差为 0），
 * 但锚的绝对下标照样前移，锚定法给出的行数依然准确。
 *
 * 边界（单测钉住）：锚不在新列表（[anchorIndex] < 0）/ 行高≤0 / maxLines≤0 → 0；
 * 锚未被移出窗口（下标 ≥ 窗口起点）→ 0（内容没有"被推走"，不动）。
 *
 * ★ 必须 public：单测在独立模块编译（见任务硬性规则 5）。
 */
fun scrollCompensationAnchored(
    anchorIndex: Int,
    newFilteredCount: Int,
    maxLines: Int,
    lineHeight: Int
): Int {
    val rows = scrollShiftRowsAnchored(anchorIndex, newFilteredCount, maxLines)
    return if (lineHeight > 0) -rows * lineHeight else 0
}

/** BL-114 纯函数：锚定法"本帧内容上移了几行"（≥0）。与 [scrollCompensationAnchored] 同源。 */
fun scrollShiftRowsAnchored(anchorIndex: Int, newFilteredCount: Int, maxLines: Int): Int {
    if (anchorIndex < 0 || maxLines <= 0) return 0
    return (scrollTrimmedAway(newFilteredCount, maxLines) - anchorIndex).coerceAtLeast(0)
}

/**
 * BL-114 纯函数：`takeLast(maxLines)` 从顶部裁掉的行数（≥0）。
 * 与上层各函数共用同一口径本体 —— 单测同时钉住，防止口径漂移。
 */
fun scrollTrimmedAway(count: Int, maxLines: Int): Int {
    if (maxLines <= 0) return 0
    return if (count > maxLines) count - maxLines else 0
}
