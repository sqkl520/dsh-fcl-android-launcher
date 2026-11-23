package com.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R49 / W1-E：Android 资源与字符串的定点回归测试（源码文本断言，不需要 Robolectric）。
 *
 * 覆盖本轮 E-1 / E-2 / E-4 / E-5 / E-8 五条。E-3 / E-6 / E-7 涉及 .kt 改动，未在本轮落地，
 * 因此这里不给它们写"应当如此"的红灯断言（那会让主线程的测试永远是红的）；
 * 它们的现状与改法见 `docs/reports/round49/fixes/W1-E.md`。
 *
 * ## 为什么这些断言用"读文件 + 正则"而不是 Resources
 * 本项目单测 harness 是 `android.jar` 桩 + MiniRunner（见 `DshResourceFormatTest` 类注释），
 * 没有资源编译产物可用 —— `android.jar` 里的 `Resources` 全是 `throw new RuntimeException("Stub!")`。
 * 但本轮每个缺陷都**纯由 XML 源文件内容决定**（缺一条 string、两个颜色同值、drawable 里有没有
 * 字面量 8dp），所以对源文件做断言是完备的：改了 XML 就一定会改变断言结果。
 *
 * ## 每条断言都必须"修复前必红"
 * 下面每条都注明了修复前的实测状态，避免写成"永远绿"的空断言。
 */
class R49ResourceFixTest {

    // ------------------------------------------------------------------
    // E-1：繁体中文必须有 dialog_positive / dialog_negative
    // ------------------------------------------------------------------

    /**
     * 修复前：`values-zh-rTW/strings.xml` **没有**这两条，繁体下所有确认框的正/负按钮
     * 回落成英文 "OK / Cancel"（用点：`dialog_alert.xml:79/88/98/107`、`dialog_edit.xml:33/41`、
     * `dialog_color_picker.xml:77/87`、`dialog_item_selection.xml:29`）。
     */
    @Test
    fun traditionalChineseDefinesDialogButtons() {
        val tw = parseStrings(requireRes("values-zh-rTW/strings.xml"))
        assertEquals("繁中 dialog_positive 必须是「確定」（修复前缺失，回落英文 OK）", "確定", tw["dialog_positive"])
        assertEquals("繁中 dialog_negative 必须是「取消」（修复前缺失，回落英文 Cancel）", "取消", tw["dialog_negative"])
    }

    /**
     * 三语都不许把 dialog_positive / dialog_negative 留成英文。
     *
     * 这条与上面那条分工不同：上面钉"繁中有没有"，这里钉"三语都不能漏"——
     * 将来若有人把简体那条也删掉，上面仍然绿，这条会红。
     */
    @Test
    fun allThreeLocalesDefineDialogButtons() {
        val expected = mapOf(
            "values/strings.xml" to ("OK" to "Cancel"),
            "values-zh/strings.xml" to ("确认" to "取消"),
            "values-zh-rTW/strings.xml" to ("確定" to "取消"),
        )
        for ((rel, pair) in expected) {
            val m = parseStrings(requireRes(rel))
            assertEquals("$rel 的 dialog_positive", pair.first, m["dialog_positive"])
            assertEquals("$rel 的 dialog_negative", pair.second, m["dialog_negative"])
        }
    }

    // ------------------------------------------------------------------
    // E-2：colorOnPrimary / colorOnSecondary 不能与 colorPrimary 同值
    // ------------------------------------------------------------------

    /**
     * 修复前三份 themes.xml 里 `colorOnPrimary` / `colorOnSecondary` 都等于
     * `@color/default_theme_color`，也就是 `colorPrimary` 本身 —— 文字与背景完全同色，
     * 实测 WCAG 对比度 **1.00:1**（背景 #7797CF，前景同值）。这不是"对比度偏低"，是"字没了"。
     *
     * 断言口径：三份主题都必须把 on 色指向 `@color/on_theme_color`，且**不得**再指向
     * `@color/default_theme_color`（后者是与 colorPrimary 同值的那条，指回去就复发了）。
     */
    @Test
    fun onColorsAreNotTheSameAsPrimaryInAllThemes() {
        val themes = listOf(
            "values/themes.xml",
            "values-night/themes.xml",
            "values-v31/themes.xml",
        )
        for (rel in themes) {
            val text = requireRes(rel).readText()
            for (attr in listOf("colorOnPrimary", "colorOnSecondary")) {
                val re = Regex("""<item name="$attr">\s*([^<\s]+)\s*</item>""")
                val value = re.find(text)?.groupValues?.get(1)
                assertTrue("$rel 必须定义 $attr", value != null)
                assertEquals("$rel 的 $attr 必须指向 @color/on_theme_color", "@color/on_theme_color", value)
            }
        }
    }

    /**
     * `on_theme_color` 本身必须与 `colorPrimary` 的取值**不同**，且必须真的存在。
     *
     * 这是 E-2 的核心：把 on 色改成另一个**资源名**但值还一样，是最容易发生的假修复。
     * 所以这里直接比对字面值，而不是只看引用名。
     */
    @Test
    fun onThemeColorResolvesToADifferentValueThanPrimary() {
        val colors = requireRes("values/colors.xml").readText()
        val primary = Regex("""<color name="default_theme_color">([^<]+)</color>""")
            .find(colors)?.groupValues?.get(1)
        val on = Regex("""<color name="on_theme_color">([^<]+)</color>""")
            .find(colors)?.groupValues?.get(1)

        assertTrue("values/colors.xml 必须有 default_theme_color", primary != null)
        assertTrue("values/colors.xml 必须有 on_theme_color（E-2 新增）", on != null)
        assertTrue(
            "on_theme_color 必须与 default_theme_color 取值不同，否则对比度仍是 1:1（实测 on=$on, primary=$primary）",
            !on!!.equals(primary!!, ignoreCase = true)
        )
    }

    /**
     * `on_theme_color` 必须在 values-night 里**不被覆盖**。
     *
     * 为什么单独钉这条：`default_theme_color` 在 values-night 里没有覆盖（仍是 #7797CF），
     * 所以 on 色也不能翻成白色 —— 否则暗色模式下又变成白字压蓝底（实测 2.95:1，连大字号 3:1 都不到）。
     * 修复过程中我一度把它写成 `@color/primary_text`，而后者在 night 里是 #FFFFFF，正是这个坑。
     */
    @Test
    fun onThemeColorIsNotOverriddenInNightMode() {
        val night = requireRes("values-night/colors.xml").readText()
        assertTrue(
            "values-night/colors.xml 不得覆盖 on_theme_color（否则暗色下白字压蓝底，2.95:1）",
            !Regex("""<color name="on_theme_color">""").containsMatchIn(night)
        )
        // 反向对照：确认该文件确实覆盖了 primary_text —— 证明"没覆盖 on_theme_color"是特意的，
        // 而不是这个文件根本没做覆盖（那样上一条断言会空转）。
        assertTrue(
            "values-night/colors.xml 应当覆盖 primary_text（用于证明上一条断言不是空转）",
            Regex("""<color name="primary_text">""").containsMatchIn(night)
        )
    }

    // ------------------------------------------------------------------
    // E-4：被删的 42 个死 key 不许复活，且 world_time 必须保留
    // ------------------------------------------------------------------

    /**
     * E-4 删掉的 42 个 key，三语都不许再出现。
     *
     * 注意 `world_time` **不在**这个清单里：它是唯一被误判为"零引用"的 —— 引用方式是
     * `LocaleUtils.java:173` 的 `getIdentifier("world_time", "string", …)`，grep 静态扫描看不到。
     * 这条断言反过来钉住"别把 world_time 也删了"（见 [worldTimeSurvivesBecauseItIsLookedUpDynamically]）。
     */
    @Test
    fun deletedDeadKeysStayDeleted() {
        val deleted = listOf(
            "message_copy", "close", "dsh_action_configure", "dsh_action_refresh", "dsh_action_save",
            "dsh_action_start_anyway", "dsh_disk_usage", "dsh_download_error", "dsh_download_title",
            "dsh_download_warning", "dsh_install_skipped", "dsh_instance_gone",
            "dsh_instance_detail_title", "dsh_instances_title", "dsh_logs_path", "dsh_logs_title",
            "dsh_saved", "dsh_settings_model", "dsh_settings_name_hint", "dsh_settings_port_hint",
            "dsh_settings_profile", "dsh_settings_title", "dsh_settings_version",
            "dsh_about_full_name", "dsh_settings_no_instance", "dsh_setting_group_hint",
            "dsh_setting_logs", "dsh_setting_logs_desc", "dsh_setting_download",
            "dsh_setting_download_desc", "dsh_setting_instance", "dsh_setting_instance_desc",
            "dsh_setting_theme_coming", "dsh_about_version", "dsh_setup_enter",
            "dsh_setup_running_now", "dsh_settings_row_key", "dsh_settings_row_key_status",
            "dsh_settings_desc_model", "dsh_settings_desc_profile", "dsh_settings_desc_key",
            "dsh_settings_desc_key_status",
        )
        assertEquals("E-4 清单应为 42 条（43 减掉世界时钟那条动态引用）", 42, deleted.size)

        for (rel in THREE_LOCALES) {
            val m = parseStrings(requireRes(rel))
            val resurrected = deleted.filter { m.containsKey(it) }
            assertTrue("$rel 里仍有 E-4 已删除的死 key: $resurrected", resurrected.isEmpty())
        }
    }

    /**
     * `world_time` 必须保留在**它原本所在的两种语言**里 —— 它是**动态引用**，静态 grep 看不见。
     *
     * 这条是 E-4 的护栏：任务单给的 43 条清单里有它，照删就会让
     * `LocaleUtils.dateTimeFormatter()` 的 `getIdentifier` 返回 0，日期格式串直接取不到。
     *
     * ★ 为什么只查两份而**不要求繁体也有**：这是修复前就存在的事实 —— `values-zh-rTW` 里从来
     *   没有 `world_time`，繁体下回落英文格式串（`EEE, MMM d, …`）。它不在本轮范围内
     *   （不是本次引入的缺陷），本轮只保证"别把现有的两份删掉"。若将来要补繁体格式串，
     *   应当同时改这条断言并说明理由，而不是让它变红后随手删掉。
     */
    @Test
    fun worldTimeSurvivesBecauseItIsLookedUpDynamically() {
        for (rel in listOf("values/strings.xml", "values-zh/strings.xml")) {
            val m = parseStrings(requireRes(rel))
            assertTrue(
                "$rel 必须保留 world_time（LocaleUtils.java:173 用 getIdentifier 动态取，grep 扫不到）",
                m.containsKey("world_time")
            )
        }
        // 并钉住"它确实是动态取的"这个前提：一旦那行 getIdentifier 被删/改名，
        // 这条断言会红，提醒重新评估 world_time 是否还需要保留。
        val localeUtils = resolve("FCL/src/main/java/com/tungsten/fcllibrary/util/LocaleUtils.java")
            ?: throw AssertionError("找不到 LocaleUtils.java")
        assertTrue(
            "LocaleUtils.java 应仍以 getIdentifier(\"world_time\", \"string\", …) 取该资源",
            localeUtils.readText().contains("getIdentifier(\"world_time\", \"string\"")
        )
    }

    // ------------------------------------------------------------------
    // E-5：dimens.xml 存在，且 drawable 里不再有硬编码 8dp 圆角
    // ------------------------------------------------------------------

    /** 修复前 `FCL/src/main/res/values/dimens.xml` **不存在**，全仓 `@dimen/` 引用为 0。 */
    @Test
    fun dimensFileExistsAndDefinesCornerRadius() {
        val f = requireRes("values/dimens.xml")
        val text = f.readText()
        val radius = Regex("""<dimen name="corner_radius">\s*([^<\s]+)\s*</dimen>""")
            .find(text)?.groupValues?.get(1)
        assertEquals("values/dimens.xml 必须定义 corner_radius = 8dp", "8dp", radius)

        // 四角档也要在，且都与主档一致 —— 否则"改一个角"这件事仍然做不到
        for (corner in listOf("tl", "tr", "bl", "br")) {
            val v = Regex("""<dimen name="corner_radius_$corner">\s*([^<\s]+)\s*</dimen>""")
                .find(text)?.groupValues?.get(1)
            assertEquals("values/dimens.xml 的 corner_radius_$corner 应为 8dp", "8dp", v)
        }
    }

    /**
     * 修复前 10 个 drawable 各自写死 8dp 圆角（任务单写的是 4 个，实测 10 个）。
     * 这条把"全清干净"钉住，而不只是清单上那 4 个。
     */
    @Test
    fun noDrawableHardcodesEightDpRadius() {
        val drawableDir = requireRes("drawable").let { if (it.isDirectory) it else error("drawable 目录不存在") }
        val hardcoded = mutableListOf<String>()
        drawableDir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }?.forEach { f ->
            val text = f.readText()
            // 只认"圆角属性 = 8dp/8.0dip"这一种写法；注释里提到 8dp 不算（下面用属性名限定）
            val re = Regex("""android:(radius|topLeftRadius|topRightRadius|bottomLeftRadius|bottomRightRadius)\s*=\s*"(8dp|8\.0dip|8\.0dp)"""")
            if (re.containsMatchIn(text)) hardcoded.add(f.name)
        }
        assertTrue(
            "这些 drawable 仍写死 8dp 圆角，应改用 @dimen/corner_radius*: $hardcoded",
            hardcoded.isEmpty()
        )
    }

    /**
     * 反向护栏：上面那条"不许有硬编码 8dp"很容易被"把 drawable 删了"或
     * "把属性整个删了"这两种方式假通过。这里钉住关键 drawable 仍在、且**确实引用了** dimens。
     */
    @Test
    fun keyDrawablesActuallyReferenceTheDimens() {
        val must = mapOf(
            "drawable/bg_item_rounded.xml" to "android:radius=\"@dimen/corner_radius\"",
            "drawable/bg_log_console.xml" to "android:radius=\"@dimen/corner_radius\"",
            "drawable/dialog_background.xml" to "android:radius=\"@dimen/corner_radius\"",
            "drawable/bg_container_white.xml" to "@dimen/corner_radius_tl",
            "drawable/bg_container_white_clickable.xml" to "@dimen/corner_radius_bl",
            "drawable/bg_item_rounded_top.xml" to "@dimen/corner_radius_tr",
            "drawable/bg_item_rounded_bottom.xml" to "@dimen/corner_radius_br",
            "drawable/bg_tab_top_rounded.xml" to "@dimen/corner_radius_tl",
            "drawable/bg_right_menu.xml" to "@dimen/corner_radius_tl",
            "drawable/bg_game_menu.xml" to "android:radius=\"@dimen/corner_radius\"",
        )
        for ((rel, needle) in must) {
            val text = requireRes(rel).readText()
            assertTrue("$rel 必须引用 $needle", text.contains(needle))
        }
    }

    // ------------------------------------------------------------------
    // E-8：night 版对话框卡片底色资源已就位（改 .kt 时才用得上）
    // ------------------------------------------------------------------

    /**
     * E-8 本轮**不动 .kt**（`DialogCard.kt:16` 的硬编码由主线程统一改）。但资源先建好：
     * `dialog_card_bg` 亮/暗两份都要在，且亮色值必须与 Kotlin 里现有的字面量一致，
     * 否则主线程替换时会出现"暗色没变、亮色悄悄变了"。
     */
    @Test
    fun dialogCardBackgroundResourceIsReadyInBothModes() {
        val light = parseColors(requireRes("values/colors.xml"))
        val dark = parseColors(requireRes("values-night/colors.xml"))

        assertEquals("values/colors.xml 的 dialog_card_bg 应等于 DialogCard.kt 现有亮色字面量 #F8F8F8",
            "#F8F8F8", light["dialog_card_bg"])
        assertEquals("values-night/colors.xml 的 dialog_card_bg 应等于 DialogCard.kt 现有暗色字面量 #323232",
            "#323232", dark["dialog_card_bg"])

        // 与对话框底色必须可区分（实测亮 1.04:1 / 暗 1.23:1，是"刚好浮起"这一档）。
        // 这里只钉"不同值"——精确比值容易随主题调整变化，钉死反而会阻碍后续设计调整。
        assertTrue("亮色 dialog_card_bg 不应等于 dialog_background",
            light["dialog_card_bg"] != light["dialog_background"])
        assertTrue("暗色 dialog_card_bg 不应等于 dialog_background",
            dark["dialog_card_bg"] != dark["dialog_background"])
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private companion object {
        val THREE_LOCALES = listOf(
            "values/strings.xml",
            "values-zh/strings.xml",
            "values-zh-rTW/strings.xml",
        )

        val STRING_RE = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val COLOR_RE = Regex("""<color name="([^"]+)">([^<]+)</color>""")

        fun parseStrings(file: File): Map<String, String> =
            STRING_RE.findAll(file.readText()).associate { m ->
                m.groupValues[1] to m.groupValues[2].replace("\\'", "'")
            }

        fun parseColors(file: File): Map<String, String> =
            COLOR_RE.findAll(file.readText()).associate { m ->
                m.groupValues[1] to m.groupValues[2].trim()
            }

        /** 从测试运行目录向上找 `[FCL/[FCL/]]src/main/res/<rel>`；找不到直接失败（不静默跳过）。 */
        fun resolve(rel: String): File? {
            System.getProperty("dsh.res")?.let { base ->
                val f = File(base, rel)
                if (f.exists()) return f
            }
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            var depth = 0
            while (dir != null && depth < 8) {
                val candidates = listOf(
                    File(dir, "FCL/FCL/src/main/res/$rel"),
                    File(dir, "FCL/src/main/res/$rel"),
                    File(dir, "src/main/res/$rel"),
                    // 也用于定位 java 源码（[worldTimeSurvivesBecauseItIsLookedUpDynamically]）
                    File(dir, "FCL/FCL/$rel"),
                    File(dir, "FCL/$rel"),
                    File(dir, "$rel"),
                )
                candidates.firstOrNull { it.exists() }?.let { return it }
                dir = dir.parentFile
                depth++
            }
            return null
        }

        fun requireRes(rel: String): File =
            resolve(rel) ?: throw AssertionError("找不到资源文件 $rel：请在仓库根目录或 FCL/ 目录下运行测试")
    }
}
