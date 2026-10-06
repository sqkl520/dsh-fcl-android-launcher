package com.dsh.ui.shell

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * 临时页覆盖层（导航栈）—— 挂在**单个多页 UI 内部**的二级页面栈。
 *
 * ## 这是什么
 * 一个「压栈 / 弹栈」的轻量导航栈：调用方把一整棵页面 View 压进 [overlay]（覆盖在内容区之上的
 * 一个 `FrameLayout`），返回键优先弹栈。典型下钻链：实例列表 → 实例详情 → 详情里的某个子页。
 *
 * ## 为什么 per-UI 一份，而不是外壳级一份
 * 这是 FCL 的既定语义（`FCLMultiPageUI` 每个实例各持一个 `tempPageStack`）：
 * **临时页属于"当前 tab 的上下文"**。切 tab 时该 UI 会 `dismissAll()`，切回来栈已清空 ——
 * 用户看到的是"我离开了这个 tab，回来就回到这个 tab 的根页"，而不是"回来时还停在刚才那一层"。
 *
 * 代价是同一份栈逻辑会被多个 UI 各持一个实例，所以它必须**完全自包含**：
 * 只依赖 `android.view.*` / `android.widget.*`，不引用项目内任何类，也不要求调用方配合任何生命周期。
 *
 * ## 与 FCL 的关系
 * 行为复刻 FCL `FCLMultiPageUI` 的那一份，**不是** `DownloadUI` 里自己抄的那份 ——
 * 后者用 `withEndAction` 等动画结束再移除 View，动画一旦被取消回调就不执行，
 * 临时页整棵 View 树残留在 overlay 上累积泄漏。本实现一律**先 `removeView`，再对露出来的下一层播淡入**。
 *
 * 相对 FCL 的两处增强：
 * 1. 补了 [popTo] / [popToRoot]（FCL 没有；它最深那页靠调用方连调 3 次返回兜底）；
 * 2. 弹栈立即移除视图（见上）。
 *
 * ## 分工
 * 栈只负责**压 / 弹 / 上报**（上报标题、上报"某页被摘掉了"）。
 * `overlay` 由调用方创建（或经 [attach] 代建）、已 `addView` 到 `content` 之上并初始 `GONE`；
 * 页面 View 的创建与销毁、"返回键要不要交给栈消费"（[canReturn]）由调用方决定 ——
 * 栈把"页面没了"报出去（[setOnPageDismissed]），销毁动作仍归调用方。
 *
 * ⚠️ 公开 API 已冻结（与 `DshMultiPageUI` 的调用方约定）：可以改内部实现、可以**加法**新增，
 * **不要改已有签名**。
 *
 * @param overlay 承载临时页的覆盖层。必须是 `content` 的**同级兄弟且在其之上**，
 *   否则临时页会被内容区盖住。本类不改动它自身的父级关系。
 * @param content 被覆盖的内容区（本项目里是内层 `ViewPager2`）。压栈时隐藏、栈空时恢复。
 */
class DshTempPageStack(
    private val overlay: FrameLayout,
    private val content: ViewGroup
) {

    /** 栈上的一层：页面 View + 它对外上报的标题 */
    private class Entry(val view: View, val title: CharSequence?)

    /**
     * 栈底在 index 0、栈顶在末尾。
     * 用 Kotlin 的 [ArrayDeque]（默认导入，不需要 import 语句）：两端增删 O(1)，
     * 且 [ArrayDeque.removeLastOrNull] 让"空栈弹栈"这条分支不用先判空再取。
     */
    private val stack = ArrayDeque<Entry>()

    /**
     * 标题上报回调。**语义是"上报"，不是"联动"**。
     *
     * 临时页**不改外壳标题**：tab 栏与 Activity 标题仍由外壳按当前 tab 决定，
     * 临时页压上来不影响它们 —— 因为临时页是"当前 tab 内部的一次下钻"，
     * 用户心里的位置感还是这个 tab，标题跟着跳反而会让人以为换了页面。
     * 这里回调出去的只是"当前栈顶的标题"，外壳默认不渲染；
     * 将来若要显示（比如给外壳加一条可选的二级标题），再从这条通道取，而不是反过来让栈去改标题。
     */
    private var titleListener: ((CharSequence?) -> Unit)? = null

    /**
     * 页面被摘掉的上报回调，见 [setOnPageDismissed]。
     *
     * 与 [titleListener] 同一种"上报"思路：栈不认识页面类型，只把事实说出去。
     */
    private var pageDismissListener: ((View) -> Unit)? = null

    /** [destroy] 之后栈即作废（理由见 [destroy]）。用标志位挡住误用，不靠调用方自觉。 */
    private var destroyed = false

    // ---------------------------------------------------------------- 压栈

    /**
     * 压入一个临时页。
     *
     * @param page 整棵页面 View（调用方负责它的创建与销毁）
     * @param title 上报给 [setOnTitleChanged] 的标题；调用方不需要标题就传 null
     */
    fun show(page: View, title: CharSequence? = null) {
        if (destroyed) return

        // 同一实例重复压栈会踩两个坑，这里一次清掉：
        // ① 栈里已经有一条指向同一个 View 的记录 —— 不摘掉的话同一棵树会在栈上留两条记录，
        //    弹到下面那条时 removeView 拿到的是已经分离的 View，栈深就和实际可见层数对不上了；
        // ② ViewGroup 会抛 IllegalStateException: The specified child already has a parent ——
        //    最常见的来源是这棵页面 View 此刻正挂在 ViewPager2 的 holder 里，压栈前必须先从那儿摘下来。
        val duplicate = stack.indexOfFirst { it.view === page }
        if (duplicate >= 0) stack.removeAt(duplicate)
        (page.parent as? ViewGroup)?.removeView(page)

        // 旧栈顶隐藏：它仍留在 overlay 里，只是不显示，弹栈时再露出来。
        // 先 cancel 掉它可能正在跑的淡入，免得被 GONE 之后动画还在后台改它的 alpha。
        stack.lastOrNull()?.let {
            it.view.animate().cancel()
            it.view.visibility = View.GONE
        }

        // 内容区一起隐藏 —— 这行的理由必须留在这里，否则后人会"顺手"把它删掉：
        // overlay 是**透明**的 FrameLayout，它只决定绘制顺序，不遮挡任何像素。
        // 内容区不 GONE 的话，底下那页仍会参与绘制、也仍会命中触摸事件 ——
        // 用户会看到两层叠在一起，并且能点到被盖住的那一页。
        // （FCL 原版靠 overlay 自己的不透明背景盖住内容区；我们靠 GONE：
        //   少一次整屏绘制，也不依赖 overlay 的 background 不被别人改掉。）
        content.visibility = View.GONE

        // 新页从全透明开始再淡入。顺序是"先置 alpha 再 addView"，
        // 这样 addView 触发的那次布局里它已经是透明的，不会先闪一帧不透明。
        page.animate().cancel()
        page.alpha = 0f
        overlay.visibility = View.VISIBLE
        overlay.addView(
            page,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        page.animate().alpha(1f).setDuration(FADE_DURATION_MS).start()

        stack.addLast(Entry(page, title))
        notifyTitleChanged()
    }

    // ---------------------------------------------------------------- 弹栈

    /**
     * 弹出栈顶。
     *
     * @return true 表示消费了这次返回（调用方的返回链应到此为止）；
     *   false 表示栈本来就是空的 —— **此时不改动任何状态**，让调用方继续走下一级返回链。
     */
    fun dismissCurrent(): Boolean {
        val entry = stack.removeLastOrNull() ?: return false

        // ⚠️ 关键：**立即**把 View 从 overlay 移除。绝不写成
        //   entry.view.animate().alpha(0f).withEndAction { overlay.removeView(entry.view) }
        // FCL 源码里那条血泪注释说的就是这个：动画一旦被取消（用户快速连按返回、页面被回收、
        // 或者这个 View 又被别的 animate() 操作），withEndAction 的回调**不会执行**，
        // 于是这棵 View 树永远留在 overlay 上 —— 每弹一次漏一棵，累积泄漏。
        // 正确顺序：先取消动画 → 再 removeView → 最后才对"露出来的下一层"播淡入。
        entry.view.animate().cancel()
        overlay.removeView(entry.view)
        // 摘掉即上报：此刻这棵 View 已不在树上，调用方可以安全销毁它（例如取消页面级协程作用域）。
        // 放在 removeView **之后**、播下一层淡入 **之前** —— 顺序无所谓，但要和"隐藏 ≠ 移除"这条
        // 区分开：上面 show() 里把旧栈顶置 GONE 时**不发**这个回调，那一页还在栈上、还活着。
        notifyDismissed(entry.view)

        val next = stack.lastOrNull()
        if (next != null) {
            // 露出的下一层重新淡入。先 cancel 它自己可能残留的动画，再 alpha 0 → 1。
            // 不直接"瞬间显示"是为了和压栈对称：返回时视觉上是"退回去"，不是"跳一下"。
            next.view.animate().cancel()
            next.view.visibility = View.VISIBLE
            next.view.alpha = 0f
            next.view.animate().alpha(1f).setDuration(FADE_DURATION_MS).start()
        } else {
            // 栈空 = 临时页全退完，回到内容区。
            // overlay 置 GONE 后既不参与绘制也不接收触摸，content 恢复 VISIBLE 顶上。
            overlay.visibility = View.GONE
            content.visibility = View.VISIBLE
        }

        notifyTitleChanged()
        return true
    }

    /** 全部弹出。等价于"连按返回直到退干净"，所以直接复用 [dismissCurrent]，不另写一份循环体逻辑。 */
    fun dismissAll() {
        while (canReturn()) dismissCurrent()
    }

    /**
     * 一直弹到栈深等于 [depth]（[depth] 是**保留**的临时页数量，不是要弹掉的层数）。
     *
     * **这是我们相对 FCL 的增强**：FCL 没有 `popTo`，它最深的那条下钻链是靠调用方写死
     * "连调 3 次返回"来兜底的 —— 层数一变就得回头改调用方。有了 `popTo`，调用方只需在
     * 下钻前记住"进来时是几层"，回去时弹到那个数即可，与中间下钻了几层解耦。
     *
     * [depth] `<= 0` 等价于 [popToRoot]（保留 0 层 = 清空），下面先把目标归一化，只留一条循环。
     *
     * @return 是否**真的**弹过。当前深度已经 `<= depth` 时返回 false 且**什么都不做**
     *   （不改状态、不发标题回调），调用方据此判断"这次返回我消费不了"。
     */
    fun popTo(depth: Int): Boolean {
        val target = depth.coerceAtLeast(0)
        if (stack.size <= target) return false
        var popped = false
        // 每次 dismissCurrent 都会重算新的栈顶并发一次标题回调。
        // 中途那几次是"过路"的，最后一次的标题才是调用方真正想要的终态。
        while (stack.size > target && dismissCurrent()) {
            popped = true
        }
        return popped
    }

    /** 清空全部临时页，回到内容区。就是 [dismissAll]。 */
    fun popToRoot() {
        dismissAll()
    }

    // ---------------------------------------------------------------- 查询

    /** 返回链用：栈上还有临时页时返回 true（这次返回该由本栈消费）。 */
    fun canReturn(): Boolean = stack.isNotEmpty()

    /** 当前栈上的临时页数量。 */
    fun depth(): Int = stack.size

    /**
     * 注册标题上报回调。[show] / [dismissCurrent] / [dismissAll] / [popTo] / [popToRoot] 之后各触发一次，
     * 参数是**当前栈顶的标题**，栈空时为 null。
     *
     * 再次调用即替换旧回调；不注册就什么也不做。**注册本身不触发回调**（只有栈操作才触发）。
     * 再强调一次：这只是上报，**不改外壳标题** —— 理由见 [titleListener] 的注释。
     */
    fun setOnTitleChanged(l: (CharSequence?) -> Unit) {
        titleListener = l
    }

    /**
     * 注册「页面被移除」上报回调，见 [pageDismissListener]。
     *
     * 调用时机：`dismissCurrent` / `dismissAll` / `popTo` / `popToRoot` / `destroy` 里真正
     * `removeView` 之后。**不是**在"压栈时被隐藏"时 —— 隐藏的页仍在栈上，还没被销毁。
     *
     * 为什么需要它：栈收的是 [View]，但真实页面是带协程作用域的页面对象（`DshPageUI`）。
     * 弹栈后如果没人通知调用方，那个作用域就会一直活着（页面没了、订阅还在跑）。
     * 栈自己不认识页面类型（它只依赖 `android.view.*`），所以只能把"这个 View 被摘掉了"这件事**上报**出去，
     * 由调用方决定怎么销毁 —— 这与 [setOnTitleChanged] 是同一种"上报"思路。
     *
     * 传 null 即注销。注册本身不触发回调。
     */
    fun setOnPageDismissed(l: ((View) -> Unit)?) {
        pageDismissListener = l
    }

    // ---------------------------------------------------------------- 销毁

    /**
     * 销毁：宿主页面被回收时调用。
     *
     * 与 [dismissCurrent] 的区别是**不播任何动画**（页面都要没了，动画只是白跑几帧），
     * 直接 `removeView`。另外会把 [content] 恢复成 `VISIBLE`：栈都销毁了，
     * 内容区再留着 `GONE` 就是一个谁也点不到、也不显示的黑洞。
     *
     * 可重入：调两次不炸（第二次直接返回）。
     *
     * ⚠️ 销毁之后本实例**不可复用**：栈里的 View 已被摘走、两个回调也清掉了，
     * 再 [show] 只会让调用方以为压上了一页、实际什么都不会发生 —— 所以用 [destroyed] 挡掉。
     * 宿主页面重新创建时应该 new 一个新实例，而不是复活旧的。
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true

        for (entry in stack) {
            // cancel 是必要的：直接 removeView 不会停掉已经排队的淡入动画，
            // 那动画还会握着这棵树的引用跑完，白白拖住一次 GC。
            entry.view.animate().cancel()
            overlay.removeView(entry.view)
            // 销毁路径也要上报：页面都要没了，调用方比平时更需要在此时取消页面级作用域。
            notifyDismissed(entry.view)
        }
        stack.clear()

        // 兜底清场：上面那个循环只摘得掉"栈记得的"View。
        // 若 overlay 上还挂着别的东西（例如某个 View 在压栈后被外部 addView 进来、
        // 或者某次异常路径没走完），本类无从判断它是不是临时页，留着就是泄漏 —— 宁可全清。
        overlay.removeAllViews()
        overlay.visibility = View.GONE
        content.visibility = View.VISIBLE

        titleListener = null
        // 回调也要清掉：栈已作废，留着它会让调用方继续收到已死栈的通知，
        // 而且它通常闭包持有着调用方的页面 —— 不清就等于栈（一个本该被回收的对象）
        // 反过来把页面钉在内存里。
        pageDismissListener = null
    }

    // ---------------------------------------------------------------- 内部

    /** 把当前栈顶的标题报给调用方；没注册回调就什么也不做。 */
    private fun notifyTitleChanged() {
        titleListener?.invoke(stack.lastOrNull()?.title)
    }

    /**
     * 把"这个 View 已从栈上摘掉"报给调用方。**所有 removeView 的唯一出口** ——
     * 集中成一处是为了不让"某个弹栈分支忘了上报"这种事发生（漏一处就是一个泄漏的页面作用域）。
     *
     * [runCatching] 是刻意的：回调体在调用方那边（通常要去 `destroy()` 一个页面、取消协程作用域），
     * 它抛异常绝不能把弹栈打断 —— 本类的前提是"栈必须永远能弹掉"，
     * 一旦在 removeView 之后抛出，栈内部状态已经改了、overlay 的显隐却还没走完，
     * 会留下一个既不在栈上、又还挂着的半死状态。宁可吞掉调用方的异常，也要让弹栈走完。
     */
    private fun notifyDismissed(view: View) {
        val l = pageDismissListener ?: return
        runCatching { l(view) }
    }

    companion object {
        /**
         * 在 [container] 里建覆盖层、挂上栈，返回栈实例。
         *
         * 覆盖层是 `FrameLayout`，**加在 [content] 之后**（同一父容器里后加的孩子绘制在上层，
         * 这就是"覆盖"的全部机制）；初始 `GONE`，由栈在压栈时置 `VISIBLE`。
         *
         * 本方法与 `DshMultiPageUI` 里那段手搓代码是同一件事 —— 之所以留两份不合并，
         * 是因为 `DshMultiPageUI` 还要把 overlay 的可见性/生命周期与它自己的 pager、tab 栏绑在一起，
         * 合并会让那个类的 setupPages 更难读。**两边行为必须一致**：改这里请同步看那边。
         *
         * @param content 会被压栈时隐藏、弹空时恢复的内容区
         */
        @JvmStatic
        fun attach(context: Context, container: ViewGroup, content: View): DshTempPageStack {
            // 先验类型、再动容器：`require` 失败时不能已经在 container 里留下一个没人认领的
            // 覆盖层 —— 那正是这个函数存在的意义（避免泄漏），自己先漏一个就本末倒置了。
            // 也不用 `as ViewGroup` 硬转：ClassCastException 看不出是"传错了什么"。
            require(content is ViewGroup) {
                "覆盖层的内容区必须是 ViewGroup（本项目里是 ViewPager2 / 页面容器）"
            }

            // 覆盖层必须在 content 之后 addView —— 这个"后加者在上"的顺序就是覆盖机制本身，
            // 不是风格问题；顺序反了临时页会被内容区盖住。
            val overlay = FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                visibility = View.GONE
            }
            container.addView(overlay)

            return DshTempPageStack(overlay, content)
        }

        /**
         * 压栈 / 弹栈的淡入时长，与 FCL `FCLMultiPageUI` 的 200ms 一致。
         *
         * 必须写成 `private const val`：`const val` 会被内联成宿主类上的一个 **static 字段**，
         * 只写 `const val` 的话它会变成 public 字段漏到公开 API 上 —— 冻结的签名里没有它。
         */
        private const val FADE_DURATION_MS = 200L
    }
}
