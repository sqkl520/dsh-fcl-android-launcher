package com.dsh

import com.dsh.core.DshInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安装进度阶梯映射单测（BL-018，纯 JVM，不需要真机/Android）。
 *
 * 依据：npm 以 `CI=true` 运行不输出百分比（见 `DshInstaller.runInstall` 的 argvEnv），
 * 唯一可采的进度信号是 `setup-node-dsh.sh` 的 `[setup] STAGE=<key>[ args]` 行；
 * key 与该脚本的 `stage` 调用一一对应（node-ready / adding-npm / installing-node /
 * using-preinstalled-dsh / installing / verifying-install）。
 *
 * 映射函数是 `DshInstaller.stageFraction`（companion 纯函数）：把 STAGE 整段
 * （`key[ args]`）译成 0..1 阶梯值，认不出返回 null（界面保持无限动画）。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.DshInstallProgressTest"
 *   （或仓库的 run-tests.sh，自动扫描本类）
 */
class DshInstallProgressTest {

    /** BL-018 任务单定的台阶：准备 5% 在 runInstall 固定发，这里钉 STAGE 段的四个关键台阶 */
    @Test
    fun keyStagesMapToLadder() {
        assertEquals(0.20, DshInstaller.stageFraction("node-ready v22.23.3")!!, 0.0)
        assertEquals(0.40, DshInstaller.stageFraction("installing @deepseek-ai/dsh@0.2.0-rc.2")!!, 0.0)
        assertEquals(0.90, DshInstaller.stageFraction("verifying-install")!!, 0.0)
        assertEquals(1.00, DshInstaller.stageFraction("using-preinstalled-dsh 0.1.6-alpha.2")!!, 0.0)
    }

    /** 可选的补装段（底座缺 Node/npm 时脚本才发的两个 STAGE）落在准备(5%)与就绪(20%)之间 */
    @Test
    fun optionalNodeBootstrapStagesBetweenPrepareAndReady() {
        val adding = DshInstaller.stageFraction("installing-node 22")!!
        val npm = DshInstaller.stageFraction("adding-npm")!!
        assertTrue("installing-node 应 > 准备 0.05", adding > 0.05)
        assertTrue("adding-npm 应 > 准备 0.05", npm > 0.05)
        assertTrue("installing-node 应 < node-ready 0.20", adding < 0.20)
        assertTrue("adding-npm 应 < node-ready 0.20", npm < 0.20)
    }

    /** 主路径单调递增：node-ready → installing → verifying-install（进度条不倒退） */
    @Test
    fun mainPathIsMonotonic() {
        val ready = DshInstaller.stageFraction("node-ready v22.23.3")!!
        val installing = DshInstaller.stageFraction("installing @deepseek-ai/dsh@0.2.0-rc.2")!!
        val verifying = DshInstaller.stageFraction("verifying-install")!!
        assertTrue("installing 应 > node-ready", installing > ready)
        assertTrue("verifying-install 应 > installing", verifying > installing)
    }

    /** 带参数的 STAGE 行只看 key：参数是版本/包名，不影响映射 */
    @Test
    fun argsDoNotAffectMapping() {
        assertEquals(
            DshInstaller.stageFraction("installing"),
            DshInstaller.stageFraction("installing @deepseek-ai/dsh@0.2.0-rc.2")
        )
        assertEquals(
            DshInstaller.stageFraction("node-ready"),
            DshInstaller.stageFraction("node-ready v22.23.3")
        )
    }

    /**
     * 未知 key 返回 null（与 stageText"未知 key 原样显示"对应：界面保持无限动画，不猜数字）。
     * 来路：App 与 rootfs 脚本升级顺序错开、或将来加了新 key 而启动器还没跟上。
     */
    @Test
    fun unknownKeyReturnsNull() {
        assertNull(DshInstaller.stageFraction("something-new arg"))
        assertNull(DshInstaller.stageFraction(""))
    }

    /** 全部返回值域：非 null 的都在 (0, 1] 内（"准备 5%"由 runInstall 固定发，不在 STAGE 映射里） */
    @Test
    fun allFractionsInRange() {
        listOf(
            "installing-node 22", "adding-npm", "node-ready v22.23.3",
            "using-preinstalled-dsh 0.1.6-alpha.2",
            "installing @deepseek-ai/dsh@0.2.0-rc.2", "verifying-install"
        ).forEach { raw ->
            val f = DshInstaller.stageFraction(raw)!!
            assertTrue("$raw 应在 (0,1] 内，实际 $f", f > 0.0 && f <= 1.0)
        }
    }
}
