package com.dsh

import com.dsh.ui.changedVersions
import com.dsh.ui.computeNewIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix2 / W3B1 回归：实例卡与版本页的**纯逻辑**部分（BL-120 / BL-121）。
 *
 * 覆盖两个顶层纯函数（均为 public，无 Android 依赖 —— 见各自在
 * `DshInstanceAdapter.kt` / `DshVersionAdapter.kt` 顶部的注释）：
 *
 * - [computeNewIds]：入场动画"这一帧新出现的 id"判定。它的**消费制**用法
 *   （submit 时 `pendingAnimIds += computeNewIds(current, pendingAnimIds + 旧items)`，
 *   onBind 命中即 remove）保证：首次加载全列表滑入；安装进度/体积/运行态引起的重绑
 *   不重播；行播过后任何重绑都不再命中。这里把判定函数的边界全钉死。
 * - [changedVersions]：版本页 submitInstalling 的"归属变化"对称差。若它退化成
 *   "新集合全体"（或空集），BL-121 就会以静默的方式回来（全列表重滑 / 状态不刷新）。
 *
 * 其余 UI 行为（payload 局部刷新、ObjectAnimator 补间、failed 色 —— 均为 View 层）
 * 注明不可测：JVM 单测无 android.animation / android.view 实现（默认 stub 抛异常）。
 *
 * 运行：./gradlew.bat --no-daemon :FCL:testFordebugUnitTest --tests "com.dsh.W3B1Test"
 */
class W3B1Test {

    // --- BL-120：computeNewIds（入场动画去重的新 id 判定） ------------------------------

    /** 首次加载（已见集为空）：全部 id 都是"新出现" → 整列表播一次入场动画（现观感保留）。 */
    @Test
    fun newIdsOnFirstLoadAreAllCurrent() {
        val ids = listOf("a", "b", "c")
        assertEquals(setOf("a", "b", "c"), computeNewIds(ids, emptySet()))
    }

    /** 已知 id 的重绑（安装进度 tick / 体积回调 / 运行态变化）：空差集 → 不重播。 */
    @Test
    fun noNewIdsWhenAllSeen() {
        val ids = listOf("a", "b", "c")
        assertTrue(computeNewIds(ids, ids.toSet()).isEmpty())
    }

    /** 只多出一个新实例：差集恰好是它，其余行不受牵连。 */
    @Test
    fun onlyFreshIdIsNew() {
        assertEquals(setOf("new"), computeNewIds(listOf("a", "new", "b"), setOf("a", "b")))
    }

    /**
     * 消费制的"已见"全集拼法（代码：`pendingAnimIds + 旧 items`，即任务单的
     * "不在当前 items、且不在 pendingAnimIds"两个判据合判）：
     *   · a —— 上一帧已播并被消费（不在待播集），但仍欠判据一票：不被再判"新出现"（防每帧重滑）；
     *   · b —— 上一帧新出现、还没被绑定消费（在待播集里）：本帧不算 fresh（判据二），
     *     但并入结果集后仍留在待播集里（欠账不丢，它还没播）；
     *   · new —— 本帧真正的新 id：进差集。
     */
    @Test
    fun seenSetMustCombineOldItemsAndPending() {
        val current = listOf("a", "b", "new")
        val oldItems = listOf("a", "b")
        val pending = mutableSetOf("b") // b 欠着动画（新出现但绑定还没轮到它）
        val fresh = computeNewIds(current, pending + oldItems)
        assertEquals(setOf("new"), fresh)
        pending += fresh
        assertEquals(setOf("b", "new"), pending) // b 的欠账保留，new 并入
    }

    /** 差集判定不受顺序影响（RecyclerView 位置随时在变，id 才是身份）。 */
    @Test
    fun orderDoesNotMatter() {
        assertEquals(setOf("x"), computeNewIds(listOf("b", "a", "x"), setOf("a", "b")))
    }

    /** 空列表：空差集（列表清空的那一帧，没有任何行需要播）。 */
    @Test
    fun emptyCurrentYieldsNothing() {
        assertTrue(computeNewIds(emptyList(), setOf("a")).isEmpty())
    }

    // --- BL-121：changedVersions（版本页 submitInstalling 的归属变化集） ---------------

    /** 开始安装：next 多出版本 v → 恰好通知 v 这一行（不是全列表）。 */
    @Test
    fun startInstallNotifiesOnlyThatVersion() {
        assertEquals(setOf("0.1.6"), changedVersions(emptySet(), setOf("0.1.6")))
    }

    /** 安装结束（集合清条）：prev 有 v、next 没有 → 仍要通知 v（按钮要从"安装中"回到"安装"）。 */
    @Test
    fun finishInstallNotifiesOnlyThatVersion() {
        assertEquals(setOf("0.1.6"), changedVersions(setOf("0.1.6"), emptySet()))
    }

    /** 归属没变（同集合重复提交，StateFlow 去重失效的兜底场景）：空差集 → 零通知。 */
    @Test
    fun noChangeYieldsNoNotifications() {
        val s = setOf("a", "b")
        assertTrue(changedVersions(s, s).isEmpty())
    }

    /** 对称差逐项验证：重叠的 "a" 不动；离开的 "c" 与进入的 "d" 都要通知。 */
    @Test
    fun onlySymmetricDifference() {
        val changed = changedVersions(setOf("a", "c"), setOf("a", "d"))
        assertEquals(setOf("c", "d"), changed)
        assertTrue("重叠项不该出现在差集里", "a" !in changed)
    }
}
