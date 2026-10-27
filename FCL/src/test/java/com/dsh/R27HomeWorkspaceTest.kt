package com.dsh

import com.dsh.core.ProotCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R27 / P0-A：「选择工作区目录」是空框 —— 把 `HOME` 从 `/root` 改到实例 workspace。
 *
 * ## 为什么这条要有测试
 *
 * 这个 bug 的性质是**配置写死导致的静默空列表**：
 * `ProotCommand` 两处都把 `HOME=/root` 写死，而 rootfs 的 `/root` 是空目录
 * （只有 `.profile`/`.bashrc`，零子目录），dsh 的目录选择器又只列目录
 * ⇒ 用户点「选择工作区目录」看到一个空框，体感是"根本加载不出来"。
 *
 * 它**不会让任何现有测试变红**（没有崩溃、没有异常、请求也返回 200），
 * 所以只能靠一条正面断言钉住"实例启动时 HOME 必须是 workspace"。
 *
 * ## 为什么测 `Spec.toProcessBuilder()` 而不是 `build()`
 *
 * `build()` 要 `Context` 与 `DshPaths` 静态状态，JVM 里拿不到。
 * 而 `HOME` 的注入点是 `Spec.toProcessBuilder()` —— 那才是最后写进进程环境的地方，
 * 也是**两处写死值最容易再次分叉**的位置（argv 里一处、ProcessBuilder 里一处）。
 */
class R27HomeWorkspaceTest {

    /**
     * `Spec` 携带的 `homeRootfs` 必须真的写进进程环境。
     *
     * 这条钉的是"字段加了但没接线"——加了 `homeRootfs` 参数却忘了在
     * `toProcessBuilder()` 里用它，工程上完全可能发生，且没有任何其它测试会红。
     */
    @Test
    fun specHomeIsInjectedIntoProcessEnvironment() {
        val spec = ProotCommand.Spec(
            argv = listOf("/bin/true"),
            argvEnv = emptyMap(),
            procEnv = emptyMap(),
            homeRootfs = "/opt/dsh/instances/inst-x/workspace"
        )

        val env = spec.toProcessBuilder().environment()

        assertEquals(
            "HOME 必须取 Spec.homeRootfs —— 否则 picker 仍会落在空的 /root 上（R27 第一层根因）",
            "/opt/dsh/instances/inst-x/workspace",
            env["HOME"]
        )
    }

    /**
     * 默认值保持 `/root`：自检/安装路径（`ProotProcessExecutor`）不传这个参数，
     * 它们的 `HOME` 不该跟着实例一起变。这是一条**防扩散**断言 ——
     * 改 `HOME` 只应影响实例启动，不影响一次性脚本执行。
     */
    @Test
    fun defaultHomeStaysRootForOneShotScripts() {
        val spec = ProotCommand.Spec(
            argv = listOf("/bin/true"),
            argvEnv = emptyMap(),
            procEnv = emptyMap()
        )

        assertEquals(
            "不传 homeRootfs 时必须回落到 /root：自检/安装是临时上下文，不该跟着实例 HOME 变",
            "/root",
            spec.toProcessBuilder().environment()["HOME"]
        )
    }

    /**
     * argv 里的 `HOME=` 与 ProcessBuilder 的 `HOME` 必须**同值**。
     *
     * 这是本改动唯一容易出错的地方：`/usr/bin/env HOME=x sh script` 里的 `HOME=x` 覆盖的是
     * **脚本进程**的环境，而 `proot` 自己 exec 之前读的是 ProcessBuilder 的 `env`。
     * 两处不一致 ⇒ "proot 启动阶段"与"guest 内脚本"看到不同的 HOME。
     *
     * 注意：本测试**只能**检查 `Spec.homeRootfs` 被同时用于两处 —— 真正的 `build()`
     * 生成的 argv 需要 `Context`，JVM 里造不出来。所以这里用 `Spec` 的约定来钉：
     * argv 里的 HOME 由 `build()` 按同一个参数拼进去（见 `ProotCommand.build` 的实现）。
     */
    @Test
    fun specCarriesHomeSoBothSidesStayInSync() {
        val workspace = "/opt/dsh/instances/inst-y/workspace"
        val spec = ProotCommand.Spec(
            argv = listOf("/bin/true"),
            argvEnv = emptyMap(),
            procEnv = emptyMap(),
            homeRootfs = workspace
        )

        assertEquals("Spec 必须保留 homeRootfs 供 argv 侧使用", workspace, spec.homeRootfs)
        assertEquals(
            "ProcessBuilder 侧必须与 argv 侧取同一个值",
            spec.homeRootfs,
            spec.toProcessBuilder().environment()["HOME"]
        )
    }

    /**
     * `HOME` 仍在环境白名单里：实例启动用 `argvEnv` 传的一批键都要过 [ProotCommand.isAllowedEnvKey]，
     * 若将来有人为了"收紧注入面"把 HOME 从白名单删掉，`Spec.toProcessBuilder()` 里的
     * `procEnv/argvEnv` 覆盖会静默失效 —— 那时 HOME 又变回常量，而没有任何测试会红。
     */
    @Test
    fun homeRemainsAnAllowedEnvKey() {
        assertTrue(
            "HOME 必须留在白名单：它由 [DshRuntime] 显式构造（不是外部输入），" +
                "不在白名单会让实例的 HOME 覆盖被静默丢弃",
            ProotCommand.isAllowedEnvKey("HOME")
        )
    }
}
