package com.dsh

import com.dsh.core.hostPathForGuestDir
import com.dsh.core.resolveHomeRootfs
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * R27 / P0-A2：`HOME` 的解析规则 —— 决定 dsh 目录选择器**从哪儿起步**。
 *
 * ## 为什么这条必须钉住
 *
 * dsh 的目录选择器（`dsh-host-directory-picker-browse`）打开时用 `homedir()`（= `$HOME`）
 * 当初始目录，而它的 `list()` **只返回目录**（`dirent.isDirectory() || isSymbolicLink()`）。
 * 所以 `HOME` 一旦指向"存在但没有任何子目录"的位置，用户看到的就是**一个空框** ——
 * 症状是"选择工作区目录根本加载不出来""一直转圈"。
 *
 * 这个坑在本项目踩了**两次**，而且两次都没有任何测试变红（页面不报错、请求返回 200）：
 * - `HOME=/root`：rootfs 的 `/root` 只有 `.profile`/`.bashrc`，零子目录；
 * - `HOME=<instance>/workspace`（fix15）：目录确实是空的（只 `mkdirs()` 建了壳，从没写过东西）。
 *
 * 所以这条测试钉的不是"函数返回值长什么样"，而是**几个会重演该 bug 的输入**。
 */
class R27HomeDirTest {

    private val instanceDir = "/opt/dsh/instances/inst-1792497096061-1-cd79"

    /**
     * 默认：没有自定义值 ⇒ 用实例目录。
     *
     * 为什么是实例目录而不是 workspace：实例目录**天然含三个子目录**
     * （`home/`、`workspace/`、`node_modules/`）⇒ 列表非空，且 `workspace/` 就在第一屏。
     * 这正是修掉"空框"的关键 —— 换成任何空目录（包括 workspace 本身）都会退回原症状。
     */
    @Test
    fun defaultIsInstanceDirNotWorkspace() {
        assertEquals(
            "默认 HOME 必须是实例目录（它天然含 home/workspace/node_modules 三个子目录，列表才非空）",
            instanceDir,
            resolveHomeRootfs(instanceDir, null)
        )
        assertEquals("空串与 null 同档", instanceDir, resolveHomeRootfs(instanceDir, ""))
        assertEquals("纯空白同档", instanceDir, resolveHomeRootfs(instanceDir, "   "))
    }

    /** 自定义的绝对路径必须被采用（这是"设置里能改"这条功能的全部意义）。 */
    @Test
    fun customAbsolutePathIsHonored() {
        assertEquals(
            "用户填的 rootfs 内绝对路径必须生效（否则设置项是死开关）",
            "/opt/dsh/instances",
            resolveHomeRootfs(instanceDir, "/opt/dsh/instances")
        )
    }

    /**
     * 相对路径必须被**拒绝**并回落默认值。
     *
     * 理由：`homedir()` 拿到相对路径时会相对 proot 的**当前工作目录**（`-w`）解析，
     * 而这个目录随启动参数变化 —— 那种"看起来能用、换个启动方式就变"的配置不接受。
     * 静默接受它比拒绝更糟：用户会以为设好了，实际却随着启动方式漂移。
     */
    @Test
    fun relativePathFallsBackToDefault() {
        assertEquals("相对路径必须回落（它会随 proot 的 -w 漂移）", instanceDir, resolveHomeRootfs(instanceDir, "workspace"))
        assertEquals("裸目录名同理", instanceDir, resolveHomeRootfs(instanceDir, "foo/bar"))
        assertEquals("./ 开头同理", instanceDir, resolveHomeRootfs(instanceDir, "./workspace"))
    }

    /** 尾随斜杠要归一化，避免拼 `${HOME}/.config` 时出现双斜杠（日志/报错会变得难读）。 */
    @Test
    fun trailingSlashesAreNormalized() {
        assertEquals("/opt/dsh/instances", resolveHomeRootfs(instanceDir, "/opt/dsh/instances/"))
        assertEquals("/opt/dsh/instances", resolveHomeRootfs(instanceDir, "/opt/dsh/instances///"))
    }

    /** 根目录 `/` 本身是合法值（用户可能就想从整个 rootfs 根开始翻）。 */
    @Test
    fun rootPathIsValid() {
        assertEquals("根目录是合法的起点", "/", resolveHomeRootfs(instanceDir, "/"))
        assertEquals("多斜杠也归一成 /", "/", resolveHomeRootfs(instanceDir, "///"))
    }

    /** 前后空白要 trim —— 用户从别处粘贴路径时很容易带上空格。 */
    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals(
            "粘贴的路径常带首尾空格，必须 trim 后再用",
            "/opt/dsh/instances",
            resolveHomeRootfs(instanceDir, "  /opt/dsh/instances  ")
        )
    }

    /**
     * R28（锐评 C2-8）：含 `..` / `.` 的路径必须被拒绝并回落默认值。
     *
     * 这不是"防黑客"（用户填的是自己的 home，没有权限边界），而是**防歧义**：
     * `/opt/dsh/../etc` 与 `/opt/etc` 是同一个位置，但日志、报错、界面回显会给出两条不同的
     * 字符串 —— 出问题时用户和我看到的东西对不上。而且 dsh 的 picker 会把 path 原样回显在
     * 面包屑里，含 `..` 的 crumb 点回去会跳来跳去。
     */
    @Test
    fun dotSegmentsAreRejected() {
        assertEquals("含 .. 的路径必须回落", instanceDir, resolveHomeRootfs(instanceDir, "/opt/dsh/../etc"))
        assertEquals("含 . 的路径必须回落", instanceDir, resolveHomeRootfs(instanceDir, "/opt/./dsh"))
        assertEquals("中间含 .. 同样回落", instanceDir, resolveHomeRootfs(instanceDir, "/opt/a/../../b"))
    }
}

/**
 * R28（锐评 C2-5）：guest 内路径 → 宿主路径的换算。
 *
 * 用途：用户自定义的「启动目录」若不存在，启动时要在宿主侧把它建出来 ——
 * 否则 dsh 的 picker `opendir` 失败，用户看到的是同一行 12px 小字 + 空框（第三次踩同一个坑）。
 *
 * 换算依据：proot 把 `$GUEST_ROOT`（`/opt/dsh`）bind 到宿主 `DshPaths.ROOT_DIR`。
 * 只有这一段在宿主侧有真实对应；rootfs 内部路径返回 null（不往解压出来的 rootfs 里写东西）。
 */
class R28GuestPathTest {

    private val hostRoot = File("/host/root")
    private val guestRoot = "/opt/dsh"

    @Test
    fun guestRootItselfMapsToHostRoot() {
        assertEquals(hostRoot, hostPathForGuestDir("/opt/dsh", hostRoot, guestRoot))
        assertEquals("尾随斜杠也认", hostRoot, hostPathForGuestDir("/opt/dsh/", hostRoot, guestRoot))
    }

    @Test
    fun nestedGuestPathMapsUnderHostRoot() {
        assertEquals(
            "实例目录必须正确换算（这是最常用的情形）",
            File("/host/root/instances/inst-x"),
            hostPathForGuestDir("/opt/dsh/instances/inst-x", hostRoot, guestRoot)
        )
    }

    /**
     * rootfs 内部的路径返回 null —— 我们不往解压出来的 rootfs 里写东西。
     *
     * 这条同时是一个**安全边界**：如果这里返回了某个宿主路径，`mkdirs()` 就会在
     * 意料之外的位置建目录。
     */
    @Test
    fun pathOutsideGuestRootReturnsNull() {
        assertEquals("rootfs 内部路径不归我们管", null, hostPathForGuestDir("/etc", hostRoot, guestRoot))
        assertEquals("前缀相似但不是子路径", null, hostPathForGuestDir("/opt/dshx/foo", hostRoot, guestRoot))
        assertEquals("根目录", null, hostPathForGuestDir("/", hostRoot, guestRoot))
    }
}
