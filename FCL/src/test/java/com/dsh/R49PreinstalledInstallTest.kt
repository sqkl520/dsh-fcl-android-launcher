package com.dsh

import com.dsh.core.DshInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * round49 / BL-250 **修正版**：命中预装的安装必须仍然算成功。
 *
 * ## 这组测试的来历（读它才知道为什么必须有）
 *
 * 我第一版修 BL-250 时，把 `readInstalledVersion` 的目录来源从 `effectiveDshDir`
 * 直接换成 `ownDshDir`，理由是"effectiveDshDir 会回退预装，用它判'装好了没'会复活预装冒充"。
 * **那个理由对一半，换法全错**，R2 安全验收把它抓出来了：
 *
 * `setup-node-dsh.sh:142-156` 有一条**合法的成功结局**：
 * ```
 * case "$DSH_VERSION" in
 *   latest|"$PRE_VER")
 *     echo "[setup] DONE version=$PRE_VER source=preinstalled"
 *     exit 0                     # ← 实例目录里一个字节都不写
 * ```
 * 而 rootfs 预装版本（0.1.6-alpha.2）在下载页上是可点的（标着 "bundled in runtime,
 * no download needed"）。于是"用户点一个正经版本 ⇒ 脚本 exit 0 ⇒ 实例目录仍是空的
 * ⇒ ownDshDir 判不完整 ⇒ **BROKEN** ⇒ 弹'安装失败请重装'"。
 * 更糟：`planRepair` 用预装事实会判 READY ⇒ "安装→报失败→杀掉 App 重开→变好了"，
 * 且"重装"会重复同一结果。
 *
 * ## 第一版的测试为什么没抓到
 *
 * 原来那条 `readInstalledVersionReadsOwnTreeNotPreinstalledFallback` 是**源码文本断言**：
 * 断言函数体里出现 `DshPaths.ownDshDir(instance.id)` 这个词。它守的是**字面**，
 * 不是**行为** —— 恰恰证明"改成了 ownDshDir"，而那次改动本身是错的。
 * 这是一个标准的假绿：测试绿了，功能坏了。
 *
 * ## 现在这组测什么
 *
 * 1. **行为**：命中预装标记后，判据必须落在**预装树**（实例树是空的）
 * 2. **行为**：没命中预装标记时，判据必须落在**实例树**（不许回退预装）
 * 3. **协议**：那个标记字符串真的在 `setup-node-dsh.sh` 里出现了（两边不许漂）
 * 4. **反面**：不许只按 `[setup] DONE` 前缀匹配（真下载也打 DONE，会误判）
 */
class R49PreinstalledInstallTest {

    /**
     * 标记字符串必须与脚本里的一字不差。
     *
     * 这条是**协议断言**：`DshInstaller` 认 `source=preinstalled`，脚本打
     * `source=preinstalled`。任何一边改了而另一边没改，"命中预装"就会被判成失败，
     * 而症状（安装报 BROKEN）与这个字符串毫无关系 —— 没有这条断言会排查很久。
     */
    @Test
    fun preinstalledMarkerMatchesTheSetupScript() {
        val script = assetFile("dsh/scripts/setup-node-dsh.sh").readText()
        assertTrue(
            "setup-node-dsh.sh 里必须仍然打出 `source=preinstalled` —— " +
                "DshInstaller 靠这个字符串判断「这次安装走的是预装、跳过下载」。两边必须一致。",
            script.contains("source=preinstalled")
        )
        // 而且它必须与 `[setup] DONE` 同行（同一行里既有 DONE 又有 source=preinstalled）
        val doneLine = script.lines().firstOrNull { it.contains("source=preinstalled") }
        assertNotNull("找不到含 source=preinstalled 的行", doneLine)
        assertTrue(
            "那一行必须同时含 `[setup] DONE`（否则 DshInstaller 的 startsWith 判据匹配不到）。" +
                "实际行：<$doneLine>",
            doneLine!!.contains("[setup] DONE")
        )
    }

    /**
     * ★ 核心行为断言：命中预装时判据必须读**预装树**。
     *
     * 用 `DshInstaller` 暴露的测试 seam 直接驱动（`noteInstallOutputForTest` →
     * `installedDirForTest`）。这两个 seam 的存在就是为了让"读哪棵树"这个决定**可以在
     * JVM 上被测**——否则只能靠源码文本断言，而那正是第一版出问题的地方。
     */
    @Test
    fun preinstalledMarkerSwitchesVerdictToPreinstalledTree() {
        val id = "inst-r49-preinstalled-1"
        DshInstaller.resetInstallHintsForTest()

        // 未收到标记前：必须读实例自己的树（不许回退预装）
        assertEquals(
            "★ 没命中预装时，判据必须落在**实例树**（ownDshDir）—— " +
                "回退预装会让「从没装过东西」的实例被判「安装完整」",
            DshInstaller.ownTreeTagForTest,
            DshInstaller.installedDirTagForTest(id)
        )

        // 收到脚本那行（命中预装、exit 0、实例目录空）之后：必须改读预装树
        DshInstaller.noteInstallOutputForTest(id, "[setup] DONE version=0.1.6-alpha.2 source=preinstalled")

        assertEquals(
            "★ 命中预装（setup 脚本 exit 0 且实例目录为空）时，判据必须落在**预装树** —— " +
                "否则安装成功会被判成 BROKEN，用户看到「安装失败请重装」而其实已经能用了。" +
                "这正是 BL-250 第一版修复引入的回归。",
            DshInstaller.preinstalledTreeTagForTest,
            DshInstaller.installedDirTagForTest(id)
        )
    }

    /**
     * 反面：**真下载**成功（DONE 但**没有** source=preinstalled）不许切到预装树。
     *
     * 如果判据写成"只匹配 `[setup] DONE` 前缀"，这条会红 —— 而后果是：
     * 实例树里明明下载了新版，却去读预装树 → 报的版本是预装的旧版本。
     */
    @Test
    fun plainDoneLineDoesNotSwitchToPreinstalledTree() {
        val id = "inst-r49-preinstalled-2"
        DshInstaller.resetInstallHintsForTest()

        DshInstaller.noteInstallOutputForTest(id, "[setup] DONE version=0.2.1-alpha.1")

        assertEquals(
            "★ 真下载成功（DONE 里**没有** source=preinstalled）必须仍读实例树。" +
                "若按 DONE 前缀就切预装树，报出来的版本会是预装那个旧的。",
            DshInstaller.ownTreeTagForTest,
            DshInstaller.installedDirTagForTest(id)
        )
    }

    /**
     * 标记必须**按实例**：A 命中预装不许影响 B 的判定。
     *
     * 不加这条的话，用 `@Volatile var usedPreinstalled: Boolean` 也能让上面两条绿 ——
     * 但装 A（命中预装）之后装 B（真下载残缺），B 会被 A 的标记带着去读预装树，
     * 于是 B 的残缺产物被判"完整"。
     */
    @Test
    fun preinstalledFlagIsPerInstance() {
        val a = "inst-r49-preinstalled-a"
        val b = "inst-r49-preinstalled-b"
        DshInstaller.resetInstallHintsForTest()

        DshInstaller.noteInstallOutputForTest(a, "[setup] DONE version=0.1.6-alpha.2 source=preinstalled")

        assertEquals("A 命中预装 ⇒ 读预装树", DshInstaller.preinstalledTreeTagForTest, DshInstaller.installedDirTagForTest(a))
        assertEquals(
            "★ B 没命中预装 ⇒ 必须仍读实例树。按实例分开存，不许用一个全局布尔" +
                "（否则装 A 成功的假象会让 B 的残缺产物被误判完整）",
            DshInstaller.ownTreeTagForTest,
            DshInstaller.installedDirTagForTest(b)
        )
    }

    // ===== 辅助 ==========================================================

    private fun assetFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            listOf(
                File(dir, "FCL/FCL/src/main/assets/$rel"),
                File(dir, "FCL/src/main/assets/$rel"),
                File(dir, "src/main/assets/$rel")
            ).firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        throw AssertionError("找不到 assets/$rel（请在仓库根或 FCL/ 目录下运行测试）")
    }
}
