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
            // 清哪个范围要跟视角一致：实例页里点"清空"只该清掉这个实例的日志，
            // 顺手把全局也清了会让人以为"别的东西的日志也丢了"（反之亦然）。
            if (instanceId == null) DshRuntime.clearLogs() else DshLogBus.clearFor(instanceId)
            toast(context.getString(R.string.dsh_logs_cleared))
        }
        binding.btnCopy.setOnClickListener { copyAll() }
        binding.btnFilter.setOnClickListener { pickFilter() }
        binding.btnJumpBottom.setOnClickListener {
            // 用户主动动作：**无条件**钉到底 —— 刻意不受「自动滚动」开关约束（理由见 [render]）。
            // 走"布局落定后再钉"而不是 `post { fullScroll }`：后者与"新文本测量完成"没有先后关系，
            // 按旧高度算出的位置随后会被布局 clamp 一次 —— 观感就是先跳一下再落位。
            pinToBottomAfterLayout()
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
        val filtered = lines.filter(::matches)
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
            val tag = TAG_RE.find(line)
            if (tag != null && tag.range.first == 0) {
                sb.setSpan(
                    ForegroundColorSpan(levelColors.tag), start, start + tag.value.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (i != shown.lastIndex) sb.append('\n')
        }
        binding.logText.text = sb

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
        if (forceScroll || binding.switchAutoScroll.isChecked || atBottom) {
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
     * ⚠️ **一次性**：回调里第一件事就是注销自己。理由不是省事 —— 常驻的话它会在**用户拖拽的
     *    每一帧**都把视图拉回底部，手根本拖不动；而"要不要继续跟随"这件事已经在 [render] 里
     *    按日志流（~5Hz）判断过了，比逐帧判断更合适。
     * ⚠️ 本方法只负责"滚到底"，**不做任何"该不该跟随"的判断**：那个判断只在 [render] 一处，
     *    否则第二次实现会漂移成"某些路径偷偷跟随"。
     */
    private fun pinToBottomAfterLayout() {
        if (pinPreDrawListener != null) return
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
        // 页面可能在"请求钉底"与"这一帧预绘制"之间被回收，落到旧 ViewTreeObserver 上的监听要一起摘掉
        removePinListener()
        super.onDestroy()
    }

    companion object {
        private val ERROR_RE =
            Regex("""error|failed|failure|fatal|exception|denied|错误|失败|异常|拒绝|不可用""", RegexOption.IGNORE_CASE)
        private val WARN_RE = Regex("""warn|警告|注意""", RegexOption.IGNORE_CASE)
        private val OK_RE = Regex("""\bok\b|ready|done|success|passed|通过|完成|就绪""", RegexOption.IGNORE_CASE)
        private val TAG_RE = Regex("""^\[[^\]]+\]""")
    }
}
