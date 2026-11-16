package com.dsh

import com.dsh.core.DshInstances
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * round43 / SP-06：**`retryManifestLoad()` 里的 `repair()` 不能跑在主线程上**。
 *
 * ## 修的是什么
 *
 * 横幅「重试」按钮的点击回调 `DshInstancesUI.onManifestRetry()`（`DshInstancesUI.kt:688-691`）
 * 在**主线程**上直接调 `DshInstances.retryManifestLoad()`。该函数在读到清单之后原先**内联**跑
 * 一次 `repair()` —— 而 `repair()` 是 O(N) 的文件 IO：逐实例 `cleanupLegacyCredentialFiles()` +
 * 两次 `readInstallFacts()`（约 2N+3 次文件系统调用 + 最多 N 次 JSON 解析）。实例多、存储慢
 * （开机解锁 / 存储挂载收尾 / eMMC 抖动）时，这一次点击就可能把主线程按满 5s ⇒ **ANR**。
 *
 * 修法只有一句：把内联调用换成 `DshAppScope.scope.launch { repair() }`，与 `init()`（`:756`）
 * 的既有口径一致。`repair()` 返回 `Unit`，**不参与** `retryManifestLoad` 的返回值，所以
 * 改成异步对「重试成功/失败」的语义**零影响**（C-06-01 刚修好的文案分叉一个字都不用动）。
 *
 * ## 三条用例各自防什么（不是同一件事的三种写法）
 * | 用例 | 防的退化 |
 * |---|---|
 * | [repairIsDispatchedToIoNotRunInline] | 有人把 `launch { }` 去掉、退回内联调用（= 缺陷复发） |
 * | [retryReturnValueDoesNotDependOnRepair] | 有人把 `repair()` 的结论塞进返回值（异步化之后语义就变了） |
 * | [repairStaysIdempotentAndOffMainThreadInInitToo] | 有人只在 retry 里异步、把 init 的 `launch` 收回去 —— 那样"异步跑 repair"就不再是本文件的口径，下一个人会把两边一起改回同步 |
 *
 * ## ★ 为什么要剥注释再断言（本仓实测教训）
 *
 * 裸 `contains("repair()")` 会命中 KDoc / 行内注释里的同名词，于是**缺陷状态也能绿**。
 * 本仓已有先例：[R40InstallForkGuardTest.deleteJoinsOutsideTheInstallerLock] 就是被
 * `proot.run(` 出现在注释里坑过一次（见它的 KDoc 与 [R40InstallForkGuardTest] 里
 * `checkpointPrecedesFork` 的注释）。所以这里一律**先 `stripComments` 再断言**。
 *
 * 运行：`sh run-tests.sh`（自动扫描本类）。
 */
class R43ManifestRetryOffMainThreadTest {

    // ==================================================================
    // 一、核心判据：repair 必须被 launch 包住，且函数体里没有裸调用
    // ==================================================================

    /**
     * `retryManifestLoad` 函数体（剥注释后）里的 `repair` 调用**必须**是
     * `DshAppScope.scope.launch { repair() }` 形态，且**不许**有裸调用。
     */
    @Test
    fun repairIsDispatchedToIoNotRunInline() {
        val body = retryManifestLoadBody()
        val launched = Regex("DshAppScope\\s*\\.\\s*scope\\s*\\.\\s*launch\\s*\\{\\s*repair\\s*\\(\\s*\\)\\s*\\}")
        // ★ 先断言"没有裸调用"：这是本缺陷的**精确**失败信号（repair 没被 launch 包住）。
        //   把合法形态整段抹掉，剩下的若还出现 `repair(` 就是内联调用 —— 剥过注释，所以
        //   不可能是因为 KDoc 里同名词命中（那是假绿，不是这里的假红）。
        val leftover = launched.replace(body, "")
        assertTrue(
            "★ SP-06 复发：repair 没被 launch 包住 —— retryManifestLoad 里出现了裸的 `repair()` " +
                "调用（剥注释后仍可见，不是 KDoc 命中）。本函数由横幅「重试」在**主线程**直接调用，" +
                "内联跑 O(N) 文件 IO（逐实例 2 次 readInstallFacts + cleanup）在慢存储/多实例时会 ANR。" +
                "剩余片段：\n$leftover",
            !leftover.contains("repair(")
        )
        assertTrue(
            "★ SP-06 复发：retryManifestLoad 里必须用 `DshAppScope.scope.launch { repair() }` " +
                "把状态校准派到 IO 线程（与 init() 的既有口径一致）。剥注释后的函数体：\n$body",
            launched.containsMatchIn(body)
        )
    }

    // ==================================================================
    // 二、返回值语义没变：repair 返回 Unit，且 `return true` 不被它包裹
    // ==================================================================

    /**
     * 异步化的前提是「`repair()` 的结果**不**参与 `retryManifestLoad()` 的返回值」。
     *
     * 两条断言：
     * 1. 反射：`repair` 的返回类型是 `void`（Kotlin 的 `Unit` 落到 JVM 就是 `void`，
     *    所以它**不可能**携带"校准成功/失败"这个信息）；
     * 2. 源码：剥注释后的函数体里，`launch { repair() }` 之后**紧接**就是 `return true`
     *    （中间只有空白，最后是函数的收尾大括号）—— 也就是说 `return true` 没有被任何
     *    `if` / `when` / `runCatching` 条件包裹，它只取决于上面那行 `_manifestLoadFailed` 判断。
     */
    @Test
    fun retryReturnValueDoesNotDependOnRepair() {
        val returnType = DshInstances::class.java.getDeclaredMethod("repair").returnType
        assertEquals(
            "★ repair 的返回类型变了：它必须是 Unit（JVM void），才可能" +
                "「不影响 retryManifestLoad 的返回值」；一旦有人让它返回 Boolean，" +
                "把它改成异步就等于**丢掉了一个返回值**，语义不再等价",
            Void.TYPE,
            returnType
        )

        val body = retryManifestLoadBody()
        val launched = Regex("DshAppScope\\s*\\.\\s*scope\\s*\\.\\s*launch\\s*\\{\\s*repair\\s*\\(\\s*\\)\\s*\\}")
        val tail = launched.replace(body, "").trim()
        assertTrue(
            "★ retryManifestLoad 的结尾必须是**无条件**的 `return true`（紧跟 launch{repair()} 之后）：" +
                "一旦它被 if/when 之类包裹，就说明 repair 的结论被塞回了返回值 —— " +
                "此时把 repair 异步化会改变" +
                "调用方看到的结果（C-06-01 的文案分叉正是踩的这类语义错配）。实际结尾：<$tail>",
            Regex("return true\\s*\\}\\s*$").containsMatchIn(tail)
        )
    }

    // ==================================================================
    // 三、同构性钉子：init() 里本来就是 `DshAppScope.scope.launch { repair() }`
    // ==================================================================

    /**
     * `init()`（启动路径）里的 `repair()` 同样是 `DshAppScope.scope.launch { repair() }`。
     *
     * 为什么这条必须存在：本次改动的全部正当性来自"**与 init 的既有口径一致**"。
     * 如果哪天有人把 init 那条 launch 收回去（比如为了"启动顺序确定"改成同步），
     * "异步跑 repair" 就不再是本文件的既有写法，下一个人会把 retry 也改回来 ——
     * 缺陷静默复发。所以这条与第一条是互锁的两半，不是重复。
     */
    @Test
    fun repairStaysIdempotentAndOffMainThreadInInitToo() {
        val body = initBody()
        val launched = Regex("DshAppScope\\s*\\.\\s*scope\\s*\\.\\s*launch\\s*\\{\\s*repair\\s*\\(\\s*\\)\\s*\\}")
        assertTrue(
            "★ init() 里的状态校准必须仍然是 `DshAppScope.scope.launch { repair() }` —— " +
                "retryManifestLoad 改成异步正是为了与它同口径（AT-06 / BL-120 的既有决定：启动路径不阻塞）。" +
                "剥注释后的 init 函数体：\n$body",
            launched.containsMatchIn(body)
        )
        val leftover = launched.replace(body, "")
        assertTrue(
            "★ init() 里出现了裸的 `repair()` 调用（剥注释后仍可见）：启动路径上的 O(N) 文件 IO " +
                "跑回主线程同样是 ANR 风险。剩余片段：\n$leftover",
            !leftover.contains("repair(")
        )
    }

    // ==================================================================
    // 源码切片工具
    // ==================================================================

    private fun retryManifestLoadBody(): String = stripComments(
        sliceFun(instancesSource(), "fun retryManifestLoad(", "internal fun writeNow(")
    )

    private fun initBody(): String = stripComments(
        sliceFun(instancesSource(), "fun init() {", "internal fun resetForTest(")
    )

    private fun instancesSource(): String = mainSourceFile("core/DshInstances.kt").readText()

    /**
     * 剥掉行注释与块注释（**保留字符串字面量原样**，本仓没有含行注释符的字符串，够用）。
     *
     * 照抄 [R40InstallForkGuardTest] 里那份（同一段逻辑、同一批踩坑记录）：不剥注释的话，
     * 断言会命中 KDoc 里的同名词而**假绿** —— 那正是这类"结构钉子"最容易失效的方式。
     */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i += 2
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * 从源码里切出一个**函数体**：取 [header] 之后、[nextHeader] 之前的那一段。
     *
     * 不用"配对大括号"切片（理由同 [R40InstallForkGuardTest.sliceFun]：KDoc 里的单个 ASCII
     * 撇号会让"跳过字符字面量"的扫描器误吞后面所有括号）。这里用**下一个成员声明的行首锚点**。
     * 找不到任一头都直接 AssertionError（不静默切成空串 —— 空串会让 `contains` 断言假绿）。
     */
    private fun sliceFun(src: String, header: String, nextHeader: String): String {
        val start = src.indexOf(header)
        if (start < 0) throw AssertionError("找不到 $header")
        val end = src.indexOf(nextHeader, start + header.length)
        if (end < 0) throw AssertionError("找不到 $header 之后的下一个成员声明：$nextHeader")
        return src.substring(start, end)
    }

    /** 定位主源码文件（与 R39 / R40 的 `mainSourceFile()` 同口径：从 cwd 往上找几层） */
    private fun mainSourceFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            listOf(
                File(dir, "FCL/FCL/src/main/java/com/dsh/$rel"),
                File(dir, "FCL/src/main/java/com/dsh/$rel"),
                File(dir, "src/main/java/com/dsh/$rel")
            ).firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        throw AssertionError("找不到 $rel（请在仓库根或 FCL/ 目录下运行测试）")
    }
}
