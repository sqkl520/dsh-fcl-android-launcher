package com.dsh

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * 资源格式化契约测试：把 `strings.xml` 里的 `dsh_*` 文案按**代码里实际传入的参数**
 * 走一遍 `String.format`，确保不会在运行时抛异常。
 *
 * ## 为什么需要这个测试（第五轮发现的一个 P0）
 * `dsh_instance_subtitle` 在 values 里写的是 `%2$d`（要一个数字），而
 * [com.dsh.ui.DshInstanceAdapter] 传进去的是**字符串**（端口号 `.toString()`，或"自动/auto"）。
 * `Resources.getString(id, vararg)` 内部就是 `String.format` —— `%d` 收到 String 会抛
 * `IllegalFormatConversionException`，于是**只要有任意一个实例，实例列表页一绑定就崩**
 * （也就是从这个启动器的唯一入口进去直接闪退）。静态审查三轮都没抓到，因为
 * "字符串参数"和"格式占位符"分处两个文件，编译期完全合法。
 *
 * 这个测试是**数据驱动**的：下面 [callSites] 登记了每个带占位符的 dsh 文案在代码里的参数类型。
 * 以后改了文案占位符、或改了调用点的参数类型，这里会先失败，而不是等到用户手机上崩。
 *
 * 注意：需要能读到 `FCL/src/main/res/values/strings.xml`（在仓库根或 FCL/ 目录下运行）；
 * 读不到时第二个测试会跳过（第一个会直接失败，避免"静默通过"）。
 */
class DshResourceFormatTest {

    /** 文案名 → 代码里实际传入的参数（类型必须与占位符一致；顺序即参数顺序） */
    private val callSites: Map<String, Array<Any>> = mapOf(
        // DshInstanceAdapter：端口被转成字符串（"3080" 或 dsh_port_auto）
        "dsh_instance_subtitle" to arrayOf<Any>("0.1.5-rc.2", "3080", "READY"),

        // DshLogsActivity / DshRuntimeService
        "dsh_state_starting" to arrayOf<Any>("dsh 0.1.5"),
        "dsh_state_stopping" to arrayOf<Any>("dsh 0.1.5"),
        "dsh_state_running_short" to arrayOf<Any>("dsh 0.1.5", 3080),
        "dsh_state_failed" to arrayOf<Any>("boom"),

        // ===== 运行时底座（DshBootstrap / ProotCommand）=====
        // 这一组是**全程 user-facing** 的一条链：准备页进度与失败提示、首页任务区、
        // 启动失败对话框、实例卡片错误行、自检对话框。带占位符的每一条都在这里登记。

        // %1$s = 任务归属者标识（"setup" 这类内部字符串，不是给人看的名字）
        "dsh_bootstrap_busy" to arrayOf<Any>("setup"),
        // %1$s = 创建失败的目录路径
        "dsh_bootstrap_data_dir_failed" to arrayOf<Any>("/data/user/0/com.tungsten.fcl/files/dsh/rootfs"),
        // %1$s = 拼好的库名列表（分隔符来自 @string/dsh_list_separator：英文 ", "、中文"、"）；
        // 下面那条 %1$s 是库名本身（带不带"无执行位"这句由它承载，好让中英文各自决定括号用全角还是半角）
        "dsh_bootstrap_missing_artifacts" to arrayOf<Any>("libproot.so, libproot-loader.so"),
        "dsh_bootstrap_artifact_no_exec" to arrayOf<Any>("libproot.so"),
        // 两条空间不足各自两个参数，都是 DshPaths.formatSize 的文本。
        // ★ 分成两条（而不是"共用一条 + 一条可选的尾注"）是有意的：升级时那句解释
        //   （"升级期间新旧两份 rootfs 会同时存在"）是**为什么需要双倍空间**的理由，
        //   塞进一个可选参数里，英文就没有自然的安放位置了。
        "dsh_bootstrap_no_space_extract" to arrayOf<Any>("2.20 GB", "312.4 MB"),
        "dsh_bootstrap_no_space_replace" to arrayOf<Any>("3.85 GB", "1.20 GB"),
        // %1$s = 内层自检细节；下面那条 %1$d 是 proot 退出码
        "dsh_bootstrap_verify_failed_prefix" to arrayOf<Any>("proot exited with code 1"),
        "dsh_bootstrap_verify_proot_failed" to arrayOf<Any>(1, "dsh-probe: node = FAIL"),
        // ProotCommand.preflight：%1$s 是宿主侧脚本的绝对路径
        "dsh_preflight_missing_script" to arrayOf<Any>("/data/user/0/com.tungsten.fcl/files/dsh/scripts/start-dsh.sh"),

        // DshSettingsActivity / 实例详情页
        "dsh_settings_version" to arrayOf<Any>("0.1.5-rc.2"),
        "dsh_disk_usage" to arrayOf<Any>("312.4 MB"),
        // Key 相关文案整组已删（批次 5）：Key 归 dsh 自己管，启动器既不再读它、也不再
        // 注进子进程环境，于是对应的登记一并消失。新增的 dsh_key_first_run_* 没有占位符，
        // 按本测试的规则不需要登记。

        // DshSettingsUI（关于页）：%1$s = BuildConfig.VERSION_NAME
        "dsh_about_version" to arrayOf<Any>("0.1.0-SNAPSHOT"),
        "dsh_about_desc" to arrayOf<Any>("0.1.0-SNAPSHOT"),

        // DshRuntime（注意 dsh_reason_start_timeout 传的是 Long：%d 接受 Long）
        "dsh_runtime_exited" to arrayOf<Any>(143),
        "dsh_reason_exited_early" to arrayOf<Any>(1),
        "dsh_reason_start_timeout" to arrayOf<Any>(180L),

        // DshInstancesActivity
        "dsh_delete_message" to arrayOf<Any>("dsh 0.1.5"),
        "dsh_install_started" to arrayOf<Any>("0.1.5-rc.2"),
        "dsh_install_started_message" to arrayOf<Any>("dsh 0.1.5"),

        // DshDownloadActivity / DshDownloadViewModel
        "dsh_download_error" to arrayOf<Any>("timeout"),
        "dsh_download_warning" to arrayOf<Any>("timeout"),
        "dsh_version_size" to arrayOf<Any>("48 KB"),
        "dsh_install_done" to arrayOf<Any>("0.1.5-rc.2"),
        "dsh_install_skipped" to arrayOf<Any>("0.1.5-rc.2"),
        "dsh_install_failed" to arrayOf<Any>("npm ERR! code ENETUNREACH"),
        // DshInstaller：失败原因会被翻译成这几条专门文案（%1$s = 错误码；其余几条无占位符，
        // 按本测试的规则不需要登记），以及起 npm 之前的空间检查（两个参数都是 formatSize 的文本）
        "dsh_install_error_npm" to arrayOf<Any>("EAI_AGAIN"),
        "dsh_install_no_space" to arrayOf<Any>("600.0 MB", "1.20 GB"),

        // 安装阶段：脚本报 key，启动器翻文案（见 DshInstaller.stageText）。
        // 这几条带 %1$s，参数是版本号 / Node 版本号。
        "dsh_stage_node_ready" to arrayOf<Any>("v22.23.3"),
        "dsh_stage_installing_node" to arrayOf<Any>("22"),
        "dsh_stage_using_preinstalled_dsh" to arrayOf<Any>("0.1.6-alpha.2"),
        "dsh_stage_installing" to arrayOf<Any>("@deepseek-ai/dsh@0.2.1-alpha.1"),

        // 通知栏与任务行的端口/状态文案（参数都是字符串 —— 状态本身已经是本地化过的文本）
        "dsh_task_port" to arrayOf<Any>("3080"),
        "dsh_notify_text_port" to arrayOf<Any>("dsh 0.2.1", "3080", "Running"),
        "dsh_notify_text" to arrayOf<Any>("dsh 0.2.1", "Running"),
        "dsh_install_timeout" to arrayOf<Any>(30),

        // DshWebViewActivity / DshLogsActivity
        "dsh_webview_error" to arrayOf<Any>("net::ERR_CONNECTION_REFUSED"),
        "dsh_webview_http_error" to arrayOf<Any>(401),
        "dsh_logs_path" to arrayOf<Any>("/data/user/0/com.tungsten.fcl/files/dsh/logs/runtime.log"),

        // DshLogsUI（控制台式日志页）：信息行与筛选统计，参数都是 Int
        "dsh_logs_info" to arrayOf<Any>(120, 300),
        "dsh_logs_hidden" to arrayOf<Any>(180),
        "dsh_logs_truncated" to arrayOf<Any>(40),

        // 实例详情页 / 日志范围标示：%1$s 都是实例名（用户起的名字，什么都可能有）
        "dsh_logs_scope_instance" to arrayOf<Any>("dsh 0.1.5"),
        "dsh_instance_detail_title" to arrayOf<Any>("dsh 0.1.5"),

        // DshInstancesUI（任务区）：%1$s = 任务标题（"实例名 · 阶段"，实例名是用户起的）
        "dsh_task_cancelled_suffix" to arrayOf<Any>("dsh 0.1.5 · 安装中…")
    )

    @Test
    fun dshStringFormatsAcceptTheArgsPassedInCode() {
        val files = LOCALES.mapNotNull { resolve(it) }
        assertTrue(
            "找不到 strings.xml：请在仓库根目录或 FCL/ 目录下运行测试",
            files.isNotEmpty()
        )
        files.forEach { file ->
            val strings = parseStrings(file)
            assertTrue("${file.path} 解析不出任何 <string>", strings.isNotEmpty())
            val locale = file.parentFile?.name ?: file.path
            callSites.forEach { (name, args) ->
                val format = strings[name]
                    ?: throw AssertionError("@string/$name 在 $locale 中不存在")
                val rendered = try {
                    String.format(Locale.US, format, *args)
                } catch (t: Throwable) {
                    // 就是这一条会在真机上变成闪退：%d 收到 String / 占位符数量对不上
                    throw AssertionError(
                        "$locale 的 @string/$name 无法用代码里的参数格式化：$t\n" +
                            "  format = \"$format\"\n  args   = ${args.toList()}",
                        t
                    )
                }
                assertTrue(
                    "$locale 的 @string/$name 渲染后仍残留占位符：\"$rendered\"（format=\"$format\"）",
                    !LEFTOVER.containsMatchIn(rendered)
                )
            }
        }
    }

    /**
     * 带占位符的 dsh 文案必须在 [callSites] 里登记。
     * 否则新增文案时本测试帮不上忙——而"没登记的文案 + 类型不匹配"正是上面那个 P0 的成因。
     */
    @Test
    fun everyDshStringWithPlaceholdersIsDeclared() {
        val file = resolve("values/strings.xml") ?: return
        val undeclared = parseStrings(file)
            .filter { (name, body) -> name.startsWith("dsh_") && body.contains('%') && !body.contains("%%") }
            .keys
            .filterNot { callSites.containsKey(it) }
            .toList()
        assertTrue(
            "这些 dsh 文案带占位符但没在本测试登记（请补进 callSites）：$undeclared",
            undeclared.isEmpty()
        )
    }

    // --- 工具 ---------------------------------------------------------------

    private companion object {
        val LOCALES = listOf("values/strings.xml", "values-zh/strings.xml")

        /** 渲染后不应该再出现的 %N$x 形式占位符 */
        val LEFTOVER = Regex("""%\d+\$[a-zA-Z]""")

        val STRING_RE = Regex("""<string name="([^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

        fun parseStrings(file: File): Map<String, String> =
            STRING_RE.findAll(file.readText()).associate { m ->
                val name = m.groupValues[1]
                // \' 是 Android 资源里的转义写法，还原后再做格式化断言
                val body = m.groupValues[3].replace("\\'", "'")
                name to body
            }

        /** 从测试运行目录向上找 `[FCL/[FCL/]]src/main/res/<rel>` */
        fun resolve(rel: String): File? {
            System.getProperty("dsh.res")?.let { base ->
                val f = File(base, rel)
                if (f.isFile) return f
            }
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            var depth = 0
            while (dir != null && depth < 8) {
                val candidates = listOf(
                    File(dir, "FCL/FCL/src/main/res/$rel"),
                    File(dir, "FCL/src/main/res/$rel"),
                    File(dir, "src/main/res/$rel")
                )
                candidates.firstOrNull { it.isFile }?.let { return it }
                dir = dir.parentFile
                depth++
            }
            return null
        }
    }
}
