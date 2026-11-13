package com.tungsten.fcllibrary.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class LocaleUtils {

    /**
     * 0: System
     * 1: English
     * 2: Simplified Chinese
     * 3: Traditional Chinese
     *
     * ## 为什么只剩四项（原来是 12 项，照 FCL 抄来的）
     * 12 项是 FCL 作为 MC 启动器时的语言面，而本项目**只维护中文（简/繁）与英文**三套文案：
     * 另外那几种语言既没有 `values-xx` 资源、也没有人校对，选进去只会得到一份**半英半中的界面**
     * —— 比"没有这个选项"更糟。少几个选项换来的是"每个选项都真的可用"。
     */
    public static final int LANG_SYSTEM = 0;
    public static final int LANG_ENGLISH = 1;
    public static final int LANG_CHINESE_SIMPLIFIED = 2;
    public static final int LANG_CHINESE_TRADITIONAL = 3;

    public static Locale TRADITIONAL_CHINESE = Locale.TRADITIONAL_CHINESE;

    private static DateTimeFormatter dateTimeFormatter;
    /**
     * [dateTimeFormatter] 的缓存键 = 构造它用的 `(locale, world_time 原文)`；`null` = 尚未构造。
     * 键不一致就必须重建（见 [getDateTimeFormatter]）。
     */
    private static String dateTimeFormatterKey;

    public static final boolean IS_CHINA_MAINLAND = isChinaMainland();

    private static boolean isChinaMainland() {
        if ("Asia/Shanghai".equals(ZoneId.systemDefault().getId()))
            return true;

        // 手动计算 8 小时对应的秒数（兼容 API 26）
        long offsetSeconds = ZonedDateTime.now().getOffset().getTotalSeconds();
        long eightHoursInSeconds = 8 * 3600; // 8 小时 = 8 * 3600 秒
        if (offsetSeconds == eightHoursInSeconds) {
            return "CN".equals(Locale.getDefault().getCountry());
        }

        return false;
    }

    public static boolean isChinese(Context context) {
        int lang = getLanguage(context);
        return lang == LANG_CHINESE_SIMPLIFIED || lang == LANG_CHINESE_TRADITIONAL
                || (lang == LANG_SYSTEM && getSystemLocale().getLanguage().startsWith("zh"));
    }

    /**
     * 当前语言选项下标。
     *
     * ## 为什么要把旧值映射回来（而不是直接用存的整数）
     * 选项从 12 项精简到 4 项之后，**老用户机器上存的还是旧下标**：老 8（繁中-香港）、
     * 11（繁中-台湾），以及已下线的俄(3)/葡(4)/波斯(5)/乌(6)/德(7)/日(9)/土(10)…… 直接拿这个数
     * 去 `getLocale()` 会落到 `default` 分支（跟随系统）—— 于是"用户明明选了繁体，升级后变回跟随系统"。
     * 这里把旧下标**一次性归一到新下标**，让升级后仍然停在他当初选的那个语言上。
     *
     * 旧 → 新：1→1（英文）；2→2（简中）；8、11→3（繁中）；3→3（**新**繁中下标，原样返回）；
     * 其余（跟随系统 0，以及已下线的葡/波斯/乌/德/日/土）→ 0（跟随系统）。
     *
     * ⚠️ 旧 12 项列表里下标 3 是**俄语**，新 4 项列表里 3 是**繁體中文** —— 同一个整数在两代
     * 语义下指向不同语言，无法只靠整数区分。这里选新语义（3 = 繁中，往返一致），代价是
     * 曾在 v0.1.0 里主动选过俄语的用户升级后会被读成繁中。
     *
     * ⚠️ **不做"写回"**：`getLanguage()` 是纯读函数，写回去会让每次读都产生一次 IO，
     * 而且迁移本身已经幂等（旧值每次都映射到同一个新值），不需要落盘。
     */
    public static int getLanguage(Context context) {
        return migrateIndex(context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
                .getInt("lang", LANG_SYSTEM));
    }

    /** 旧下标 → 新下标（见 [getLanguage]）。幂等：新下标传进来原样返回。 */
    private static int migrateIndex(int old) {
        switch (old) {
            case LANG_ENGLISH:
                return LANG_ENGLISH;
            case LANG_CHINESE_SIMPLIFIED:
                return LANG_CHINESE_SIMPLIFIED;
            // ⚠️ 繁中的**新**下标必须原样返回：`changeLanguage` 落盘的是原始值（不做迁移），
            // 少了这一行，用户选完繁體中文下次读出来就塌回"跟随系统"——这正是本函数要防的回归。
            case LANG_CHINESE_TRADITIONAL:
                return LANG_CHINESE_TRADITIONAL;
            // 繁体：旧的 HK(8) 与 TW(11) 两个都并到新的 LANG_CHINESE_TRADITIONAL
            case 8:
            case 11:
                return LANG_CHINESE_TRADITIONAL;
            default:
                return LANG_SYSTEM;
        }
    }

    public static Context setLanguage(Context context) {
        SharedPreferences sharedPreferences = context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        return updateResources(context, migrateIndex(sharedPreferences.getInt("lang", LANG_SYSTEM)));
    }

    public static void changeLanguage(Context context, int lang) {
        SharedPreferences sharedPreferences = context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        @SuppressLint("CommitPrefEdits") SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt("lang", lang);
        editor.apply();
    }

    private static Context updateResources(Context context, int lang) {
        Locale locale = getLocale(lang);
        Configuration configuration = context.getResources().getConfiguration();
        configuration.setLocale(locale);
        configuration.setLocales(new LocaleList(locale));
        return context.createConfigurationContext(configuration);
    }

    /** 下标 → Locale。下标与 [LANG_*] 常量一一对应；越界一律按"跟随系统"处理。 */
    public static Locale getLocale(int lang) {
        switch (lang) {
            case LANG_ENGLISH:
                return Locale.ENGLISH;
            case LANG_CHINESE_SIMPLIFIED:
                return Locale.SIMPLIFIED_CHINESE;
            case LANG_CHINESE_TRADITIONAL:
                return TRADITIONAL_CHINESE;
            default:
                return getSystemLocale();
        }
    }

    public static Locale getSystemLocale() {
        return LocaleList.getDefault().get(0);
    }

    public static String formatDateTime(Context context, Instant instant) {
        return getDateTimeFormatter(context).format(instant);
    }

    /**
     * 当前语言对应的日期时间格式化器。
     *
     * ## 缓存必须以语言为键（slice-11-dc-f4）
     * `world_time` 是**按语言不同**的资源串（英文 `EEE, MMM d, yyyy HH:mm:ss`、中文
     * `yyyy 年 MM 月 dd 日 HH:mm:ss`），而本方法原来只判 `null` —— 于是进程内切语言后
     * （设置页 `changeLanguage` + `recreate()`，**不重启进程**）继续用切换前的模式串格式化，
     * 是"缓存键缺失且无失效路径"的直接形态。
     *
     * ## 为什么不写"在 `changeLanguage` 里清空缓存"
     * 清空要求"每一处改语言的地方都记得调"，漏一处就复发；而且 `changeLanguage` 只写盘，
     * 真正决定资源语言的是**这个 context**（`setLanguage`/`attachBaseContext` 造出来的那份
     * ConfigurationContext）。把语言直接当键，读路径自己就自洽了，不依赖写入侧配合。
     *
     * 键与资源同源：`getLanguage(context)` 与 `setLanguage` 里 `migrateIndex(prefs.getInt("lang"))`
     * 是同一个表达式，所以键永远等于该 context 实际取到的那份 `world_time` 所属语言。
     */
    public static synchronized DateTimeFormatter getDateTimeFormatter(Context context) {
        Locale locale = getLocale(getLanguage(context));
        // 走 getResources().getString 而不是 Context.getString：AOSP 里后者就是
        // `return mResources.getString(id);` 一行委派，但它在 `android.jar` 桩里是
        // final + `throw new RuntimeException("Stub!")`，单测打不到真实的格式语义。
        @SuppressLint("DiscouragedApi") int resId = context.getResources().getIdentifier("world_time", "string", context.getPackageName());
        String pattern = resId != 0 ? context.getResources().getString(resId) : "EEE, MMM d, yyyy HH:mm:ss";
        // 缓存键 = (locale, world_time 原文)，**两个都要**。原来只判 `null`，于是进程内
        // 切语言后继续用切换前的模式串（`world_time` 英文/中文两串不同，输出可见地不同）。
        // 只拿原文当键也不够：两语言共用同一串、或资源缺失回落同一硬编码默认串时，
        // pattern 相同而解析 locale 不同（`EEE`/`MMM` 这类文本字段按 locale 输出）结果仍不同。
        String key = locale.toLanguageTag() + '\u0000' + pattern;
        if (!key.equals(dateTimeFormatterKey)) {
            dateTimeFormatter = DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault());
            dateTimeFormatterKey = key;
        }
        return dateTimeFormatter;
    }

}