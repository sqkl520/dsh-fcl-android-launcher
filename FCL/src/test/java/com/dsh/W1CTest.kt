package com.dsh

import com.dsh.core.DshInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * fix2 / W1Cb 的纯 JVM 单测（不需要真机/Android）。
 *
 * 覆盖 [DshInstaller.resolveFailVersion]（F2-8 / BL-048 的纯判断内核）：
 * `fail()` 写进 `Progress.Failed` 的版本号"stored（本次目标版本）优先、快照兜底"。
 * 下载页的 VM 拿这个版本号清「安装中」标记（`p.version?.let { … }`），
 * 只要它是非空就能清 —— null 正是"新建实例失败后标记卡死"那条路径的根因。
 *
 * 说明（任务单要求注明）：`installVersions` map 的 put/remove 生命周期挂在
 * `install()` 的协程任务体里（需要 DshAppScope + Context + proot，单测构造不出来），
 * 无法纯测，跳过；其行为靠入口/finally 的代码审查 + 真机日志门验证。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.W1CTest"
 *   （或仓库的 run-tests.sh，自动扫描本类）
 */
class W1CTest {

    /** stored 优先：本次安装的目标版本存在时碾压（可能为 null 的）实例快照 */
    @Test
    fun failVersionPrefersStoredTargetVersion() {
        assertEquals("0.2.1-alpha.1", DshInstaller.resolveFailVersion("0.2.1-alpha.1", null))
        assertEquals("0.2.1-alpha.1", DshInstaller.resolveFailVersion("0.2.1-alpha.1", "0.1.5"))
    }

    /** 新建实例场景：快照为 null、stored 就是唯一来源（BL-048 的直接靶点） */
    @Test
    fun failVersionUsesStoredWhenSnapshotIsNull() {
        assertEquals("0.2.0-rc.2", DshInstaller.resolveFailVersion("0.2.0-rc.2", null))
    }

    /** stored 缺失（Entry 清理后的异常路径 / 旧实例）时回退到实例快照 */
    @Test
    fun failVersionFallsBackToSnapshot() {
        assertEquals("0.1.5", DshInstaller.resolveFailVersion(null, "0.1.5"))
    }

    /** 两者都没有 → null（调用方即 VM 对 null 有防御：跳过清理，不崩） */
    @Test
    fun failVersionIsNullWhenNothingKnown() {
        assertNull(DshInstaller.resolveFailVersion(null, null))
    }

    /**
     * 空白串视为"没有"：绝不把 "" 当版本号送进 Progress.Failed
     * （那会让 VM 去 `-= ""`——集合里永远不会存在空串，标记照旧卡死）。
     */
    @Test
    fun failVersionTreatsBlankAsAbsent() {
        assertEquals("0.1.5", DshInstaller.resolveFailVersion("", "0.1.5"))
        assertEquals("0.1.5", DshInstaller.resolveFailVersion("   ", "0.1.5"))
        assertNull(DshInstaller.resolveFailVersion("", null))
        assertNull(DshInstaller.resolveFailVersion(null, "  "))
        assertNull(DshInstaller.resolveFailVersion("", ""))
    }

    /**
     * 钉住 pid 文件名（F2-6 跨文件契约）：改名会同时改变"孤儿认领"的目标文件与用户诊断时
     * 手查的文件名，不允许无声漂移（与 dsh.pid / dsh-node.pid 同一目录、同一命名风格）。
     */
    @Test
    fun installPidFileNameIsStable() {
        assertEquals("dsh-install.pid", DshInstaller.INSTALL_PID_FILE_NAME)
    }
}
