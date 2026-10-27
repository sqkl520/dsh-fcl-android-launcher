package com.dsh.core

import android.content.Context
import com.dsh.fcl.androidlauncher.R
import com.tungsten.fcl.util.RuntimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 底座缺口的**机器可读**分类（`isReady()` 的判定内核输出）。
 *
 * 为什么要有它（C-17）：`isReady()` 只回答"能不能用"，把 7 条判据压成一个布尔；
 * 而 `missingSummary()` 原来是**另一套** if 链 —— 少了 `isLatest` 与 `scriptsOutdated`
 * 两条判据，顺序也不同。结果是 `isReady()==false` 时报出的原因可能与真正卡住的那条不符。
 * 现在两边都从 [readinessGaps] 取同一份事实、同一套顺序。
 */
enum class ReadinessGap {
    SCRIPTS_MISSING, ROOTFS_MISSING, PROOT_BIN_MISSING,
    PROOT_BIN_NOT_EXECUTABLE, PROOT_LOADER_MISSING, OUTDATED, SCRIPTS_STALE
}

/**
 * 判定内核：按 `isReady()` 的 `&&` 短路顺序收集**全部**不满足项（空表 = 就绪）。
 *
 * 顺序与 [DshBootstrap.isReady] 一一对应：rootfs 版本 → scripts 版本 → scripts 版本
 * （独立通路）→ `start-dsh.sh` 存在 → proot 二进制（存在 + 可执行）→ proot loader → rootfs 布局。
 * 返回整张表而不是第一个缺口：横幅只要第一条，但"到底缺几项"是排障时唯一能对上日志的东西。
 *
 * 两处刻意的归并：
 * - `!scriptsCurrent` 与 `scriptsStale` 都是 [ReadinessGap.SCRIPTS_STALE]：它们是同一件事
 *   （磁盘脚本落后于 APK 内 assets）的两条独立通路（`RuntimeUtils.isLatest` 与
 *   `scriptsOutdated`）。拆成两个 key 会让同一张横幅里出现两句同义的话。
 * - `!rootfsCurrent` 归 [ReadinessGap.OUTDATED]：与 `missingSummary` 原来的兜底句同义
 *   （"底座需要更新"）。
 *
 * 为什么入参是布尔而不是 `File`：这些判据里有两处 JVM 里造不出来 ——
 * `canExecute()` 在 Windows 上恒 true、`RuntimeUtils.isLatest` 要 assets。把"怎么取事实"
 * 留给调用方（[DshBootstrap] 内部 + 单测），这里只做布尔组合，才能被 D 组用例逐条钉死。
 */
fun readinessGaps(
    rootfsCurrent: Boolean,
    scriptsCurrent: Boolean,
    scriptsStale: Boolean,
    startScriptExists: Boolean,
    prootBinPresent: Boolean,
    prootBinExecutable: Boolean,
    prootLoaderPresent: Boolean,
    rootfsUsable: Boolean
): List<ReadinessGap> = buildList<ReadinessGap> {
    if (!rootfsCurrent) add(ReadinessGap.OUTDATED)
    if (!scriptsCurrent || scriptsStale) add(ReadinessGap.SCRIPTS_STALE)
    if (!startScriptExists) add(ReadinessGap.SCRIPTS_MISSING)
    // "不存在"与"在但不可执行"是两件事：前者是没打包/没解压，后者是打包坏了（W^X/无执行位）。
    // 用 when 而不是两个独立的 if —— 缺文件时 canExecute() 必然也是 false，
    // 两条都报会在横幅上堆出"缺 proot + proot 不可执行"这种自相矛盾的两句。
    when {
        !prootBinPresent -> add(ReadinessGap.PROOT_BIN_MISSING)
        !prootBinExecutable -> add(ReadinessGap.PROOT_BIN_NOT_EXECUTABLE)
    }
    if (!prootLoaderPresent) add(ReadinessGap.PROOT_LOADER_MISSING)
    if (!rootfsUsable) add(ReadinessGap.ROOTFS_MISSING)
}

/**
 * 缺口 → 文案资源 id。**只映射现有 key**（三语文案都已齐），本函数不新增文案。
 *
 * 与 [readinessGaps] 拆开的理由与 `setupStepStateRes` 相同：`getString` 要 Context，
 * 而资源 id 是纯值，可以在 JVM 单测里直接断言映射关系（不必起 Android 环境）。
 */
fun gapMessageRes(gap: ReadinessGap): Int = when (gap) {
    ReadinessGap.OUTDATED,
    ReadinessGap.SCRIPTS_STALE -> R.string.dsh_bootstrap_outdated
    ReadinessGap.SCRIPTS_MISSING -> R.string.dsh_bootstrap_missing_scripts
    ReadinessGap.ROOTFS_MISSING -> R.string.dsh_bootstrap_missing_rootfs
    ReadinessGap.PROOT_BIN_MISSING -> R.string.dsh_bootstrap_missing_proot
    ReadinessGap.PROOT_BIN_NOT_EXECUTABLE -> R.string.dsh_bootstrap_proot_not_executable
    ReadinessGap.PROOT_LOADER_MISSING -> R.string.dsh_bootstrap_missing_proot_loader
}

/**
 * dsh 运行时底座的首启解压：把打包在 assets 里的 proot 二进制 + rootfs + 脚本，
 * 首次启动时解压到 App 私有目录（[DshPaths]）。
 *
 * 复用 FCL 现成的 [RuntimeUtils]（同款 tar.xz 解压 + version 文件比对增量更新逻辑）。
 *
 * ## assets 布局约定（打包时放到 FCL/src/main/assets/dsh/ 下）
 * ```
 * assets/dsh/
 *   version                 底座版本号（说明用；实际按各子项 version 增量解压）
 *   proot/     version, libproot.so, libproot-loader.so
 *   rootfs/    version, rootfs.tar.xz
 *   scripts/   version, setup-node-dsh.sh, start-dsh.sh
 * ```
 *
 * ## 本次加固（对应审查发现的问题）
 * 1. **解压完了要能用**：新增 [verify] 自检——真正在 proot 里跑一条最小命令确认整条链路可用。
 *    原来只检查 "version 文件对不对"，解压出一个空壳也会被判为就绪，然后在安装阶段抛一堆看不懂的错。
 * 2. **rootfs 少一层目录也能救**：rootfs.tar.xz 若带顶层目录（如 `ubuntu/`），解压出来会多套一层，
 *    proot -r 直接指错根。现在解压后检测"是否存在 /bin/sh"，若只有一个顶层目录则自动上提（hoist）。
 * 3. **磁盘空间检查**：rootfs + node_modules 动辄 1GB 起，空间不够时给明确提示，
 *    而不是解到一半失败留下半个 rootfs。
 * 4. **原子替换**：rootfs 先解到 `rootfs.tmp`，成功后换名，避免"解压中途被杀 → 半成品被当成就绪"。
 * 5. **进度可见**：[progress] StateFlow（阶段 + 明细 + 百分比），界面不再只有一句"请稍候"。
 * 6. **proot 缺失时给人话**：assets 方案在 Android 10+ 常因 W^X 无法执行，
 *    现在直接给出"请把 proot 放进 jniLibs"的可操作指引，而不是静默失败。
 *
 * ## 日志归属（本类全部走全局流）
 * 底座是**所有实例共享**的（一份 rootfs，实例只是它里面的一个目录），所以这里的每一行都是
 * App 级事实：解压进度、空间检查、原子替换/回滚、自检结论、底座就绪或失败。
 * 归到某个实例上是错的 —— 底座失败时**所有**实例都起不来，把原因写进"当时碰巧在装的那个实例"
 * 的日志，会让另外几个实例页上什么都不显示，用户反而找不到根因。
 * 因此本类一律 `DshLogBus.append`（全局流），不进任何实例流。
 */
object DshBootstrap {

    private const val ASSET_ROOT = "dsh"

    /**
     * rootfs 解压后的**门槛**体积（Debian bookworm arm64 + 官方 Node 22 + 预装 dsh，瘦身版 slim1）。
     *
     * 实测来源：docs/ROOTFS.md §9「瘦身后（0.1.3 起）」—— 解压后 apparent-size 合计
     * **904,335,360 B（≈862 MiB）**，tar.xz 压缩后 142 MB。assets 内 `rootfs/version` =
     * `debian-bookworm-arm64-node22-dsh0.1.6-alpha.2-slim1`，正与该瘦身记录对应。
     *
     * ★ K21 修正：旧值 1,650 MiB 是**瘦身前**的数字，把空间门槛抬高近一倍，误拦中低端机。
     * ★ BL-072（R15）：apparent-size **低估实际占盘约 8%** —— 真机实测（
     *   `docs/reports/round12/A6-bootstrap.md` A6-3）：34,050 文件 867,218,857 B，4K 块对齐后
     *   961,220,608 B，加 4,865 个目录约 936 MiB（f2fs 实际只会更大）。门槛若按 apparent-size
     *   会在窄空间带放行 → 中途 ENOSPC 留半成品。本常量改为**不小于实测的保守值 960 MiB**
     *   （与"宁多勿少"同向：这是要拦"注定写满"的门槛，不是体积展示）。
     *   项目惯例：阈值必须有实测出处。
     */
    private const val ROOTFS_EXTRACTED_BYTES = 960L * 1024 * 1024

    /**
     * 一个实例的 dsh 依赖树（node_modules）大致占用，实测 300~500 MB，取 600 MB 留余量。
     * （瘦身只剔了 rootfs 的 /opt 工具链与文档，不影响实例各自 npm 装出来的 node_modules，故此值不变。）
     *
     * ★ internal：这条门槛有**两个**使用方 —— 本类解压 rootfs 前的空间检查，以及 [DshInstaller]
     *   起 npm 之前的空间检查。两处必须用同一个数，否则会出现"底座说够、安装说不够"的矛盾提示。
     */
    const val INSTANCE_NODE_MODULES_BYTES = 600L * 1024 * 1024

    /**
     * 首装 / 升级分别需要的最小**可用空间**（字节）。纯函数，便于单测（见 DshBootstrapSpaceTest）。
     *
     * 两种口径为什么不同：
     * - **首装**（replacing=false）：解压把一份 rootfs 写进 `rootfs.tmp`，吃掉 1 份可用空间；
     *   随后首个实例还要 npm install → 再留 1 份 node_modules。需要 `rootfs + node_modules`。
     * - **升级/重装**（replacing=true）：旧 rootfs 已占盘（**不计入可用空间**），解压同样只把新的
     *   一份写进 `.tmp`（吃掉 1 份可用空间）；之后 dest→.old、tmp→dest 全是 rename（不再吃可用
     *   空间），.old 删除后净增量为 0。新旧两份只是**磁盘占用**上短暂并存，并不要求可用空间里再
     *   空出一整份；已有实例的 node_modules 原样留着也无需再预留。需要 `rootfs` 一份。
     *
     * ★ K21 修正：旧实现升级分支按 `rootfs×2 + node_modules`（把"磁盘占用并存"错当成"可用空间
     *   要双份"），门槛虚高到 ~3.9 GB，1.1 GB 可用的真机被直接误拦。
     */
    fun requiredFreeBytes(replacing: Boolean): Long =
        if (replacing) ROOTFS_EXTRACTED_BYTES
        else ROOTFS_EXTRACTED_BYTES + INSTANCE_NODE_MODULES_BYTES

    sealed class Progress {
        data class Stage(val text: String, val fraction: Double? = null) : Progress()
        data class Detail(val detail: String) : Progress()
        object Done : Progress()
        data class Failed(val reason: String) : Progress()
    }

    /** 自检结果 */
    data class VerifyReport(val ok: Boolean, val detail: String)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 互斥用：两个界面同时触发解压时，只允许一个真正执行（比读 _busy 再写更可靠） */
    private val busyGuard = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 当前解压任务的「归属者」标识（页面/入口名）。
     *
     * ★ 修的问题：原来只看 `busy` 布尔值，页面被 ViewPager 回收 → 页面级协程被 cancel →
     * 进度对话框不再刷新（看起来像卡死），用户再点一次就撞上互斥，只得到一句
     * 「已有解压任务在进行」——**完全看不到进度**。
     * 现在配合进程级作用域（[DshAppScope]）与 [progress] StateFlow：
     * 任务本身与界面生命周期解耦，界面只是订阅显示；同一归属者重复点击不再报错。
     */
    private val busyOwner = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** 是否有解压任务在跑 */
    @JvmStatic
    fun isBusy(): Boolean = busyGuard.get()

    /** 当前任务的归属者（排查用） */
    @JvmStatic
    fun currentOwner(): String? = busyOwner.get()

    /** 当前进度（界面订阅显示；无任务时为 null） */
    @JvmStatic
    fun currentProgress(): Progress? = _progress.value

    /**
     * 底座是否已就绪。
     *
     * ★ 方案 B：proot / loader / busybox 一律由 **jniLibs** 提供（nativeLibraryDir 有执行位，
     * 才能在 W^X 下 execve），因此不再要求 assets 里存在 proot 副本与版本文件。
     *
     * ★ BL-214（fix10）：新增 [scriptsOutdated] 判据。原实现只信 `RuntimeUtils.isLatest`（走
     *   `Class.getResourceAsStream`），而它在 Android 上的行为在不同 ROM/打包方式下有分歧：
     *   若解析不到 assets 就**恒返回 true（视为最新）**，于是"脚本 9 → 10"这类升级永远被跳过。
     *   这里用一条**确定的通路**（`context.assets.open`）独立比对一次脚本版本，两者是 `&&` 关系：
     *   - `isLatest` 正常 → 两条判据结论一致，无行为变化；
     *   - `isLatest` 恒 true → 本判据仍能把"脚本过期"判出来，不会假就绪。
     *
     *   代价：这一步要读 assets 里 3 字节的 version 文件。调用方（准备页 onCreate / 下载页）
     *   本来就在做磁盘 stat，量级相同；读失败一律返回 false（见 [scriptsOutdated]），
     *   宁可多走一次幂等准备，也不误判"已就绪"。
     *
     * ★ fix12 / C-17：判定内核抽到 [readinessGaps]（顶层纯函数），这里只剩
     *   "收集事实 → 判空"。**行为与抽之前完全等价**：7 条判据同序、异常仍一律降级为 false，
     *   见 [collectGaps]。这样做是因为 [missingSummary] 必须与它同序——两套 if 链迟早会漂移。
     */
    fun isReady(): Boolean = runCatching { collectGaps().isEmpty() }.getOrDefault(false)

    /**
     * 收集一次事实并交给 [readinessGaps]。**唯一的取事实处** —— [isReady] 与 [missingSummary]
     * 都走这里，C-17 要求的"同序"由此在结构上成立（而不是靠两处注释互相对齐）。
     *
     * 每条判据各自兜异常（见 [fact]），理由：`RuntimeUtils.isLatest` 会抛 IOException、
     * [scriptsOutdated] 会读 assets。整段共用一个 try 的话，任何一条抛异常都会让
     * [missingSummary] 退化成"没有原因"——比报一句可能不准的原因更差（E3 C-17 的原文结论）。
     * 逐条兜住则是"这一条当作不满足"，恰好等价于 `&&` 短路到它时返回 false 的旧行为。
     */
    private fun collectGaps(): List<ReadinessGap> {
        // 两次 resolve 也各自兜住：它们读 DshPaths 的 lateinit 字段，路径未初始化时会抛
        // UninitializedPropertyAccessException。旧实现里这一步在 isReady 的 runCatching 之内、
        // 在 missingSummary 的 try 之外 —— 也就是说 missingSummary 那时**会**抛。
        // 现在 missingSummary 不再包 try，所以必须在这里兜住，否则同一场景会变成崩溃。
        val nativeLibDir = com.tungsten.fclauncher.utils.FCLPath.NATIVE_LIB_DIR
        val prootBin = runCatching { DshPaths.resolveProotBin(nativeLibDir) }.getOrNull()
        return readinessGaps(
            rootfsCurrent = fact { RuntimeUtils.isLatest(DshPaths.ROOTFS_DIR, "/assets/$ASSET_ROOT/rootfs") },
            scriptsCurrent = fact { RuntimeUtils.isLatest(DshPaths.SCRIPTS_DIR, "/assets/$ASSET_ROOT/scripts") },
            scriptsStale = fact { scriptsOutdated(null) },
            startScriptExists = fact { File(DshPaths.SCRIPTS_DIR, "start-dsh.sh").isFile },
            // "存在"用同一个 File 对象判；"可用"直接复用 prootBinReady()/prootLoaderReady()——
            // 它们同时是准备页第 1 行的判据（C-16），不在这里另写一份 `isFile && canExecute()`。
            prootBinPresent = fact { prootBin?.isFile == true },
            prootBinExecutable = fact { prootBinReady() },
            prootLoaderPresent = fact { prootLoaderReady() },
            rootfsUsable = fact { DshPaths.rootfsLooksUsable() }
        )
    }

    /**
     * 磁盘上的脚本目录是否落后于 APK 内的脚本（**只读、不触发任何解压**）。BL-214。
     *
     * 为什么需要它：启动页（[com.tungsten.fcl.activity.SplashActivity]）只认
     * `DshSetupActivity.isCompleted()`（`KEY_SETUP_DONE` 一次置真永真）就直接进主壳，而
     * `install()` 的唯一生产调用点在准备页 —— 存量用户升级后**根本不经过**那条路，脚本版本
     * 9 → 10 的解压永不发生（BL-191 的新日志因此对他们永远不可见）。启动链调用本方法，
     * 过期就改道准备页（[com.dsh.ui.setup.DshSetupActivity] 的 `beginPrepare()` 会因
     * `isReady()` 为 false 而重解压脚本）。
     *
     * 读哪两处（有意避开 `Class.getResourceAsStream`）：
     * - assets：`context.assets.open("$ASSET_ROOT/scripts/version")` —— Android 上确定能读的通路；
     * - 磁盘：`DshPaths.SCRIPTS_DIR/version` —— 解压时由 `copyAssets` 原样写下的那份。
     *
     * 判定（**宁可不打扰，也不误弹**）：
     * - 磁盘 version 缺失 / 为空 / 与 assets 不同 → `true`（需要重解压）；
     * - 任一侧读取失败（assets 条目不存在、IO 异常、路径未初始化）→ `false`。
     *   失败时返回 false 而不是 true：这条判据挂在启动链上，误判为 true 会让每个用户每次
     *   启动都进准备页；而"磁盘真缺文件"另有 `start-dsh.sh` 存在性兜底（[isReady] / [install]）。
     *
     * @param context 任意可拿 assets 的 Context；传 null 时退回进程级 Application Context
     *                （assets 不随语言/主题变化，用它读版本是安全的）。
     */
    @JvmStatic
    fun scriptsOutdated(context: Context?): Boolean {
        if (!DshPaths.isLoaded) return false
        // assets 读不到 = 这个包本身没有版本文件（打包问题）：不据此打扰用户
        val assetVersion = readAssetScriptsVersion(context) ?: return false

        val diskFile = File(DshPaths.SCRIPTS_DIR, "version")
        if (!diskFile.isFile) return true
        // 文件在但读不出（权限/IO）：不确定，按"不打扰"处理
        val diskVersion = runCatching { diskFile.readText().trim() }.getOrNull() ?: return false
        if (diskVersion.isEmpty()) return true

        return !sameScriptsVersion(diskVersion, assetVersion)
    }

    /**
     * 只读快照：`(磁盘版本, assets 版本)`，读不到的一侧为 null。BL-214 日志用。
     *
     * 与 [scriptsOutdated] 同一套读取方式，只为把"X → Y"写进日志（排障时要能一眼看出
     * 到底哪一版落后，而不是只有一句"过期了"）。任何异常都降级为 null，绝不影响启动链。
     */
    @JvmStatic
    fun scriptsVersionPair(context: Context?): Pair<String?, String?> {
        val disk = if (!DshPaths.isLoaded) null else runCatching {
            File(DshPaths.SCRIPTS_DIR, "version").takeIf { it.isFile }?.readText()?.trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        return disk to readAssetScriptsVersion(context)
    }

    /** assets 内的脚本版本号；Context 不可用或条目缺失/为空时返回 null。 */
    private fun readAssetScriptsVersion(context: Context?): String? = runCatching {
        val ctx = context ?: DshAppContextHolder.context ?: return@runCatching null
        ctx.assets.open("$ASSET_ROOT/scripts/version")
            .use { it.bufferedReader().readText().trim() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * 两个脚本版本号是否等价：字符串相等，或两边都是纯数字时数值相等
     * （与 [RuntimeUtils.isLatest] 的历史兼容口径一致，"007" 与 "7" 视为同版本）。
     *
     * BL-214（fix10）：实现挪到文件顶层（`scriptsVersionSame`），理由同 `trimDeltaFor`：
     * 单测 harness 的 classpath 没有 androidx，经 companion/object 访问会拉起整条父类链。
     */
    private fun sameScriptsVersion(installed: String, asset: String): Boolean =
        scriptsVersionSame(installed, asset)

    /**
     * proot 主程序是否\"可用\"。
     * ★ 与 [ProotCommand.preflight] 的口径对齐：preflight 要求 `exists() && canExecute()`，
     * 这里原来只要求 `isFile` —— 若二进制存在但没有执行位，就会出现\"横幅隐藏（以为就绪）
     * 但一启动就报『proot 二进制没有执行权限』\"的假就绪（第九轮修的是同一类问题，
     * 只是当时漏了执行位这一维）。判据必须**不弱于**真正会失败的那个入口。
     *
     * ★ fix12 / C-16：`private` → `internal`。准备页第 1 行原来自己写了一份
     *   `isFile && isFile`（漏了 `canExecute()`），于是会出现同屏"清单已就绪 + 标题准备失败"。
     *   改可见性而不是复制判据，是为了让这条判据只有一个定义。
     */
    internal fun prootBinReady(): Boolean =
        DshPaths.resolveProotBin(com.tungsten.fclauncher.utils.FCLPath.NATIVE_LIB_DIR)
            .let { it.isFile && it.canExecute() }

    /** proot loader 是否在。可见性理由同 [prootBinReady]（C-16）。 */
    internal fun prootLoaderReady(): Boolean =
        DshPaths.resolveProotLoader(com.tungsten.fclauncher.utils.FCLPath.NATIVE_LIB_DIR).isFile

    /**
     * 一句话描述当前缺口（界面提示用）。
     *
     * ★ fix12 / C-17：改为与 [isReady] **同一个内核**（[collectGaps]）的第一条。
     *   原实现是另一套 if 链：缺 `isLatest` / `scriptsOutdated` 两条判据、顺序也不同，
     *   于是 `isReady()==false` 时可能报出一句与真实缺口无关的兜底话
     *   （典型：脚本版本落后，却因为 `start-dsh.sh` 在、rootfs 布局完好而落到"底座过期不了"的 else）。
     *
     * 仍然不包 `runCatching`：逐条事实已在 [collectGaps] 里各自兜住异常，这里只剩取值与取文案，
     * 没有可抛点；若整个包起来，反而会把"读 assets 失败"退化成"没有原因"（E3 C-17）。
     */
    fun missingSummary(): String? {
        val gaps = collectGaps()
        val gap = gaps.firstOrNull() ?: return null
        val ctx = DshUiText.contextOrNull() ?: return null
        return ctx.getString(gapMessageRes(gap))
    }

    /**
     * 把"取一条事实"包成"失败即不满足"。
     *
     * 等价性说明：旧 [isReady] 是 `A && B && …` 包在**一个** `runCatching` 里 ——
     * 第 k 条抛异常时整体返回 false，恰好等于"第 k 条为假"。逐条兜住后，异常那条为 false，
     * 其后的判据照常求值；对 [isReady] 的**布尔结果**没有任何影响（第一条假就已经不是就绪），
     * 对 [missingSummary] 则变成了"能报出这条"，而不是整段降级成 null。
     */
    private fun fact(block: () -> Boolean): Boolean =
        runCatching { block() }.getOrDefault(false)

    /**
     * 界面文案用的 Context。
     *
     * ## 为什么不是调用方传进来的那个
     * 这里的文案最终显示在准备页与首页任务区，而进程级单例（[DshServices]、[DshTasks]）持有的
     * 是 `applicationContext` —— Android 的**资源级**语言（`values-zh/…` 选哪一套）是在每个 Context
     * 上解析的，Application 那份跟随系统，**不认启动器设置里选的语言**。直接用它就等于"界面切成英文
     * 了，这里还是中文"。所以每次取这条链上的文本时都现取一份**当前 Activity** 的 Context
     * （`setLanguage` 在 `FCLActivity.attachBaseContext` 里生效），拿不到才退回 App Context。
     *
     * 一个字段缓存不成立：这条链是进程级的，用户可以在 App 存活期间切语言，缓存会把它钉死在旧语言上。
     */
    /**
     * 首启解压（幂等）。在 IO 线程调用。逐项检查 version，只解压过期的子项。
     */
    fun install(context: Context, owner: String? = null, forceExtract: Boolean = false, onProgress: (Progress) -> Unit = {}) {
        // 原子占位：避免两个界面（列表页横幅 + 下载页）同时解压同一个 rootfs 互相踩坏
        if (!busyGuard.compareAndSet(false, true)) {
            val cur = busyOwner.get()
            if (owner != null && cur == owner) {
                // ★ 同一入口重复点击：不报错，只把"最新进度"重放给本次调用方（幂等）
                _progress.value?.let(onProgress)
            } else {
                // owner 是**内部标识**（两个界面同时进来时用来分辨"是不是同一入口重复点了"），
                // 不是给人看的名字。这里把标识保留在括号里：它是排障时唯一能对上日志的信息，
                // 而把它翻成一句人话反而会丢掉"是谁在跑"。
                onProgress(
                    Progress.Failed(
                        context.getString(R.string.dsh_bootstrap_busy, cur ?: "")
                    )
                )
            }
            return
        }
        busyOwner.set(owner)
        _busy.value = true
        val emit: (Progress) -> Unit = { p ->
            _progress.value = p
            onProgress(p)
        }
        // 界面文案统一用 DshUiText.contextOrNull()：它与调用方传进来的 context 可能不是同一个
        // （DshInstaller 那条链拿的是 applicationContext，不认启动器语言），详见 DshUiText.contextOrNull()。
        val ui = DshUiText.contextOrNull() ?: context
        try {
            // BL-214（fix10）：这一步现在也承担"让 [scriptsOutdated] 的路径字段可用"——它读 DshPaths
            // 的 lateinit 字段，必须在任何脚本版本判定之前初始化（loadPaths 幂等，顺序不变）。
            val pathErr = DshPaths.loadPaths(context)
            if (pathErr != null) {
                fail(emit, ui.getString(R.string.dsh_bootstrap_data_dir_failed, pathErr))
                return
            }

            // 0) 磁盘空间检查**移到了真正要解压 rootfs 的分支里**（见第 3 步）。
            // 原实现把它放在这里，无论是否需要解压都会先卡一道门槛：rootfs 已经就绪、本次只是
            // 补脚本的场景，也会被"空间不足"直接判失败（假失败）。门槛口径见 [requiredFreeBytes]。

            // 1) 脚本
            // ★ 判定带\"文件存在性\"兜底：RuntimeUtils.isLatest 用 Class.getResourceAsStream(\"/assets/...\")
            // 比对版本，与解压用的 context.getAssets().open(\"dsh/...\") 是两套访问机制；一旦前者在任何
            // 环境解析不到（返回\"已是最新\"），这里仅靠 isLatest 判定就会永远跳过首次解压，导致
            // scripts/probe.sh、start-dsh.sh 缺失、实例永远起不来。加一道\"目标文件在不在\"的硬校验，
            // 文件缺失就无条件补解压（幂等，安全）。
            // ★ BL-214（fix10）：再加 [scriptsOutdated]（确定的 assets 通路）—— 否则 isLatest 恒 true
            //   时，"脚本 9 → 10"在准备页里也会被跳过：isReady() 已判否（进入本页），而这里又判\"无需
            //   解压\"，两个判据互相矛盾，页面就会停在失败/反复重试。三选其一为真即重解 scripts，
            //   解压动作本身（RuntimeUtils.install + 加执行位）一行未改。
            if (!RuntimeUtils.isLatest(DshPaths.SCRIPTS_DIR, "/assets/$ASSET_ROOT/scripts") ||
                scriptsOutdated(context) ||
                !File(DshPaths.SCRIPTS_DIR, "start-dsh.sh").isFile
            ) {
                emit(Progress.Stage(ui.getString(R.string.dsh_bootstrap_extract_scripts), 0.05))
                RuntimeUtils.install(
                    context, DshPaths.SCRIPTS_DIR, "$ASSET_ROOT/scripts",
                    listener(emit)
                )
                markExecutableShellScripts(File(DshPaths.SCRIPTS_DIR))
            }

            // 2) proot / loader / busybox：方案 B 一律走 jniLibs（nativeLibraryDir 有执行位）。
            // 不再从 assets 解压 proot 副本——assets 方案在 targetSdk>=29 会被 W^X 拒绝 execve。
            val nativeLibDir = com.tungsten.fclauncher.utils.FCLPath.NATIVE_LIB_DIR
            val prootBin = DshPaths.resolveProotBin(nativeLibDir)
            val prootLoader = DshPaths.resolveProotLoader(nativeLibDir)
            if (!prootBin.isFile || !prootBin.canExecute() || !prootLoader.isFile) {
                // 明确报出缺哪个，并给出可操作指引（这是打包问题，不是运行环境问题）
                // 缺哪个原生库是**构建产物的事实**，不是给人读的句子：库名（含"无执行位"这个
                // 形态差异）原样列出即可，列表分隔符走资源（中文用顿号、英文用逗号），
                // 拼句子的事交给 dsh_bootstrap_missing_artifacts 一条文案整体负责。
                val sep = ui.getString(R.string.dsh_list_separator)
                val libs = buildList {
                    if (!prootBin.isFile) add("libproot.so")
                    else if (!prootBin.canExecute()) {
                        add(ui.getString(R.string.dsh_bootstrap_artifact_no_exec, "libproot.so"))
                    }
                    if (!prootLoader.isFile) add("libproot-loader.so")
                }.joinToString(sep)
                fail(emit, ui.getString(R.string.dsh_bootstrap_missing_artifacts, libs))
                return
            }
            emit(Progress.Stage(ui.getString(R.string.dsh_bootstrap_proot_ready), 0.1))

            // 3) rootfs（tar.xz，最耗时）
            // ★ forceExtract（「重新开始」用，BL-069）：isLatest 在 Android 上恒 true（K20）且
            //   rootfsLooksUsable 只查布局 —— "布局完好但内容坏"的底座（如 /opt 被清）两个判据
            //   都不命中，重解压被永久跳过。强制参数给这条恢复路径兜底。
            if (forceExtract ||
                !RuntimeUtils.isLatest(DshPaths.ROOTFS_DIR, "/assets/$ASSET_ROOT/rootfs") ||
                !DshPaths.rootfsLooksUsable()
            ) {
                // 解压前才做空间检查，按"首装 / 升级分别需要多少可用空间"算
                // （口径见 [requiredFreeBytes] —— 升级分支不再按两份 rootfs 的可用空间要求）。
                // ★ BL-072（R15）：评估 free **之前**先把上次中断遗留的 `rootfs.tmp` 清掉 ——
                //   它是可回收空间，不清就把它算进"已占用"，中断遗留越多越被误拦
                //   （"明明删 .tmp 就够"的假失败）。原清理在 extractRootfs 内部（更晚），
                //   现在提前到此；extractRootfs 里那处保留（幂等，且防其它调用路径）。
                //   注：`.old` 不跨启动残留（解压成功路径末尾即删），优先级低，不动。
                runCatching { File("${DshPaths.ROOTFS_DIR}.tmp").deleteRecursively() }
                val replacing = DshPaths.rootfsLooksUsable()
                val need = requiredFreeBytes(replacing)
                val free = DshPaths.freeSpaceBytes()
                if (free in 0 until need) {
                    // 首装与升级**各一条**文案，而不是"共用一条 + 补一段括号说明"：
                    // 升级时那句"新旧两份会同时存在"是解释**为什么**要这么多空间，英文重写后
                    // 放进一条 %3$s 尾注里怎么排都不顺；分成两条让两边都能把话说明白，
                    // 也顺手避开了"缺了这句解释的译文看起来像凭空要双倍空间"。
                    // 两条都登记进 DshResourceFormatTest.callSites（两个参数都是 formatSize 的文本）。
                    fail(
                        emit,
                        ui.getString(
                            if (replacing) R.string.dsh_bootstrap_no_space_replace
                            else R.string.dsh_bootstrap_no_space_extract,
                            DshPaths.formatSize(need),
                            DshPaths.formatSize(free)
                        )
                    )
                    return
                }
                emit(Progress.Stage(ui.getString(R.string.dsh_bootstrap_extract_rootfs), 0.2))
                // 决策 5：解压 rootfs 是数分钟 CPU 密集活，灭屏 Doze 会挂起它。整段持 PARTIAL_WAKE_LOCK。
                // install() 是阻塞函数（调用方在 DshAppScope 的 IO 线程上跑），而帮助函数是 suspend 签名：
                // 用 runBlocking 就地桥接。block 内是纯同步解压、无挂起点，等价于原来的直接调用；
                // 占用的是本就已被 install() 同步阻塞的那个线程，不引入额外阻塞面。
                kotlinx.coroutines.runBlocking {
                    withRuntimeBusyWakeLock(context, EXTRACT_WAKE_LOCK_TIMEOUT_MS) {
                        extractRootfs(context, emit)
                    }
                }
            }

            // 4) 自检：能不能真跑
            emit(Progress.Stage(ui.getString(R.string.dsh_bootstrap_verify_check), 0.98))
            val report = verifySync(context)
            if (!report.ok) {
                fail(emit, ui.getString(R.string.dsh_bootstrap_verify_failed_prefix, report.detail))
                return
            }
            emit(Progress.Done)
            DshLogBus.append("[bootstrap] 运行时底座就绪")
        } catch (e: Exception) {
            fail(emit, e.message ?: e.toString())
        } finally {
            _busy.value = false
            busyGuard.set(false)
            busyOwner.set(null)
        }
    }

    /**
     * 底座准备失败：写日志 + 发进度事件 + **上报一条终态任务**。
     *
     * ★ 为什么失败也必须 [DshTasks.report]（而不是只发 `Progress.Failed`）：
     *   `Progress.Failed` 是"这一帧的进度状态"，而 [install] 的 `finally { _busy.value = false }`
     *   紧接着就把 `_busy` 置回 false —— [DshTasks] 那句 `if (busy) add(bootstrapTask(progress))`
     *   于是几乎在同一瞬间不再成立。结果：失败信息只在"progress 已更新、busy 还没置 false"那个
     *   竞态窗口里存在过一帧（状态流还是**合帧**的，同一轮里的两次写入只会通知一次），
     *   用户通常根本看不到，只知道"点了准备运行环境，转一圈，什么都没发生"。
     *
     *   `busy` 的语义不能改（界面拿它做门禁：解压中要禁按钮，置 false 太晚会让用户并发触发两次解压），
     *   所以这里不碰它，而是把**失败这件事**变成一条**事件**上报：终态任务是 push 进来的，
     *   不受 `busy` 的影响，会稳定留在任务区（[DshTasks.finished]）里，
     *   显示成"运行环境准备失败：xxx"。这也是"失败态该由事件承载、而不是靠状态残留"的直接体现。
     */
    private fun fail(emit: (Progress) -> Unit, reason: String) {
        DshLogBus.append("[bootstrap] 失败：$reason")
        emit(Progress.Failed(reason))
        // instanceId 留 null：底座是**所有实例共享**的，不属于任何一个实例（与 bootstrapTask 同口径）
        DshTasks.report(
            DshTask(
                id = "bootstrap",
                kind = DshTask.Kind.BOOTSTRAP,
                title = "",
                stage = reason,
                action = DshTask.Action.NONE,
                state = DshTask.State.FAILED,
                error = reason
            )
        )
    }

    /**
     * 运行时自检：在 proot 里跑最小命令，确认 proot + rootfs + bind 都正常。
     * 这是"装机后第一次真正执行 proot"的地方，能提前把 W^X/proot 缺失/rootfs 布局错误暴露出来。
     */
    suspend fun verify(context: Context): VerifyReport = verifySync(context)

    private fun verifySync(context: Context): VerifyReport {
        val ui = DshUiText.contextOrNull() ?: context
        val pre = ProotCommand.preflight(context, DshPaths.ROOTFS_DIR)
        // 预检失败时 reason 必非空（[ProotCommand.Preflight] 的约定），这里的兜底串只是
        // "万一"——真到了那一步说明 ProotCommand 自己也出问题了，所以它不配文案，用一句
        // 不带语言的机器可读串，免得把一条**本该永不出现**的兜底也塞进三套翻译里。
        if (!pre.ok) return VerifyReport(false, pre.reason ?: "preflight failed")

        // 自检脚本：随底座一起解压的 scripts 目录里放一个最小探针（不存在则现写）
        val probe = File(DshPaths.SCRIPTS_DIR, "probe.sh")
        return try {
            if (!probe.isFile) {
                probe.writeText("#!/bin/sh\necho dsh-probe-ok\n")
                probe.setExecutable(true, false)
            }
            val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
            val code = ProotProcessExecutor(context).run(
                script = "${ProotCommand.GUEST_ROOT}/scripts/probe.sh",
                argvEnv = emptyMap(),
                secretEnv = emptyMap(),
                timeoutMs = 60_000
            ) { lines += it }
            when {
                code == 0 && lines.any { it.contains("dsh-probe-ok") } ->
                    VerifyReport(true, ui.getString(R.string.dsh_bootstrap_verify_ok))
                else -> VerifyReport(
                    false,
                    ui.getString(
                        R.string.dsh_bootstrap_verify_proot_failed,
                        code,
                        lines.takeLast(3).joinToString(" / ").take(300)
                    )
                )
            }
        } catch (e: Exception) {
            VerifyReport(false, e.message ?: e.toString())
        }
    }

    /**
     * 长时运行任务（解压/安装）期间持 `PARTIAL_WAKE_LOCK`（决策 5）：这类任务是数分钟到数十分钟的
     * CPU/网络密集活，灭屏进入 Doze 会把它挂起或掐断。开始前 acquire，任务结束（正常或异常）即 release。
     *
     * [timeoutMs] 是系统级兜底上限（`WakeLock.acquire(timeoutMs)`），只防"忘记释放"，**不代表任务时长**：
     * 即便 release 漏调用，系统也会自动放锁，绝不会无限持有。各调用侧的时长依据：
     * - **解压侧**传 [EXTRACT_WAKE_LOCK_TIMEOUT_MS]（20 分钟）：实测出自 bebug 真机日志 ——
     *   `20:53:31 [bootstrap] wake lock held for rootfs extraction`（旧版日志原文）→
     *   `20:56:41 [bootstrap] 运行时底座就绪`（约 3 分钟），20 分钟足够覆盖且留足余量；
     * - **安装链**会传 [INSTALL_WAKE_LOCK_TIMEOUT_MS]（40 分钟）：npm 安装由 [DshInstaller.INSTALL_TIMEOUT_MS]
     *   （30 分钟）看门狗兜底，bebug「装 29 分钟失败」疑似锁屏 Doze 掐网络所致（BL-051，登记为推断），
     *   40 分钟 = 30 分钟看门狗 + 10 分钟余量，保证锁一定活到看门狗收口之后。
     *
     * WAKE_LOCK 权限已在 AndroidManifest 声明（本任务不改 manifest）。拿不到 PowerManager（或
     * acquire 抛异常）时降级为直接执行 block —— 保活是优化项，绝不能成为新的崩溃点，故全程
     * runCatching 兜住。
     */
    suspend fun <T> withRuntimeBusyWakeLock(context: Context, timeoutMs: Long, block: suspend () -> T): T {
        val wl = runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)?.apply {
                setReferenceCounted(false)
                // ★ 必须用单参 acquire(timeout)：双参 acquire(String, long) 是 @hide 系统 API，
                //   对着 public SDK（android-35 android.jar，已 javap 核实只有 acquire/acquire(long)）
                //   编译不过。tag 已在 newWakeLock 时传给系统。
                acquire(timeoutMs)
            }
        }.getOrNull()
        if (wl != null) {
            // 解压与安装共用此函数，日志带上 timeout 值以便区分调用侧（20min=解压 / 40min=安装）
            DshLogBus.append("[bootstrap] wake lock held for long-running task (timeoutMs=$timeoutMs)")
        } else {
            DshLogBus.append("[bootstrap] no wake lock (PowerManager unavailable); task continues")
        }
        try {
            return block()
        } finally {
            runCatching { wl?.let { if (it.isHeld) it.release() } }
        }
    }

    /**
     * 解压 rootfs.tar.xz 到临时目录，校验布局后原子替换。
     */
    private fun extractRootfs(context: Context, onProgress: (Progress) -> Unit) {
        val destDir = File(DshPaths.ROOTFS_DIR)
        val tmpDir = File("${DshPaths.ROOTFS_DIR}.tmp")
        if (tmpDir.exists()) tmpDir.deleteRecursively()
        tmpDir.mkdirs()

        context.assets.open("$ASSET_ROOT/rootfs/rootfs.tar.xz").use { input ->
            RuntimeUtils.uncompressTarXZ(input, tmpDir, listener(onProgress))
        }

        // 布局校验 / 自动上提单层顶层目录
        hoistSingleTopLevelDir(tmpDir)

        if (!looksUsable(tmpDir)) {
            tmpDir.deleteRecursively()
            // 异常消息的去向是 catch → fail() → 准备页/首页任务区（用户可见），所以必须能本地化。
            // 这是首启最容易撞上的一条：rootfs.tar.xz 一旦多套了一层目录，用户看到的就是它。
            throw IllegalStateException(
                (DshUiText.contextOrNull() ?: context).getString(R.string.dsh_bootstrap_rootfs_layout_bad)
            )
        }

        // 写 version（与 assets 内 version 对齐，供下次 isLatest 比对）
        val version = context.assets.open("$ASSET_ROOT/rootfs/version")
            .bufferedReader().use { it.readText().trim() }
        File(tmpDir, "version").writeText(version)

        // 原子替换（★ 本轮加固）：先把旧 rootfs 挪到 .old 再让新内容上位。
        // 原实现是 \"先 deleteRecursively 旧目录，再 rename 新目录\"——删除与 rename 之间若失败
        // （磁盘满 / 被系统杀 / rename 异常），用户会**同时失去新旧两份 rootfs**：升级失败 = 底座全没了。
        // 现在失败可回滚到旧的（至少还能用），成功后再删备份。
        val backupDir = File("${DshPaths.ROOTFS_DIR}.old")
        if (backupDir.exists()) backupDir.deleteRecursively()
        val hadOld = destDir.exists()
        if (hadOld && !destDir.renameTo(backupDir)) {
            // 挪不动旧目录（少见）：退化为原行为，但记一行日志便于排查
            DshLogBus.append("[bootstrap] 旧 rootfs 无法改名备份，退化为直接覆盖")
            destDir.deleteRecursively()
        }
        if (!tmpDir.renameTo(destDir)) {
            // 某些文件系统上跨目录 rename 失败：退化为移动内容
            destDir.mkdirs()
            tmpDir.listFiles()?.forEach { f -> f.renameTo(File(destDir, f.name)) }
            tmpDir.deleteRecursively()
        }
        if (backupDir.exists()) {
            if (looksUsable(destDir)) {
                backupDir.deleteRecursively()
            } else {
                // 新内容不可用 → 回滚旧的，别让用户连旧底座都没了
                DshLogBus.append("[bootstrap] 新 rootfs 不可用，回滚到备份")
                destDir.deleteRecursively()
                backupDir.renameTo(destDir)
            }
        }
        DshPaths.loadPaths(context) // 重建目录（rootfs 被换过）
    }

    /** tar.xz 里若只有一个顶层目录，把其内容上提一层（rootfs 必须是"根"的形态） */
    private fun hoistSingleTopLevelDir(dir: File) {
        val entries = dir.listFiles() ?: return
        val visible = entries.filter { it.name != "version" }
        if (visible.size != 1) return
        val only = visible[0]
        if (!only.isDirectory) return
        if (looksUsable(only)) {
            DshLogBus.append("[bootstrap] rootfs 包内多了一层目录 ${only.name}，已自动上提")
            only.listFiles()?.forEach { f -> f.renameTo(File(dir, f.name)) }
            only.delete()
        }
    }

    private fun looksUsable(dir: File): Boolean =
        File(dir, "bin/sh").exists() || File(dir, "usr/bin/env").exists() ||
            File(dir, "bin/busybox").exists()

    /** 给 scripts 目录下的 .sh 加执行位 */
    private fun markExecutableShellScripts(dir: File) {
        dir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".sh")) it.setExecutable(true, false) }
    }

    /**
     * 解压进度回调 → [Progress.Detail] +（BL-170）按节流旁路写日志总线。
     *
     * ★ 节流（配合 RuntimeUtils 去掉的"每文件 sleep 25ms"）：解压 rootfs 时每个文件都会回调一次
     * （几万次），如果原样往下传，UI 线程会被几万个 `runOnUiThread { dialog.setMessage(...) }`
     * 淹没。这里把回调压到约 5 次/秒：解压全速跑，界面仍然每 200ms 刷新一次。
     *
     * ★ BL-170（R21）：解包的逐文件明细原来**只**走 [_progress] 这条独立的进度流，一行都不进
     *   [DshLogBus]。而部署页（R20/fix7）的实时日志面板订阅的是总线的全局流 —— 于是在最长的
     *   rootfs 解压那三分钟里，面板恰好只剩 `[app]` + `[bootstrap]` 两行不动，看起来像死机
     *   （E2/E3/E4/E5 四源同判；PLAN 原来猜的"订阅错流"已被反证）。
     *   现在同一个回调里旁路写一行 `[extract] <相对路径>`，间隔见 [EXTRACT_BUS_LINE_INTERVAL_MS]。
     *   **量级自查**：3~4 分钟 × 0.5 行/秒 ≈ 120 行，总线环形上限 2000 行装得下，不会把头部
     *   两行冲掉（面板本来就贴底滚动，头两行少看见也无妨）。
     *   rootfs（tar.xz → [RuntimeUtils.uncompressTarXZ]）与 assets 脚本（→ [RuntimeUtils.install]
     *   → copyAssets）**共用这个 listener**，两个阶段一并覆盖（E3-C15 提的同型问题即此）。
     *
     * ★ 线程：回调由解压线程发出（调用方在 IO 线程上同步跑 install），而 [DshLogBus.append]
     *   的契约就是"任意线程可调"（内部串行化 + ~200ms 合并 flush），所以这里直接调，不再切线程。
     *
     * ★ fix8 / BL-170 配套（W1B 上报、主代理裁决）：路径行走 [DshLogBus.appendProgress] 而非
     *   [DshLogBus.append]。原因：rootfs 里有 464/35101（1.3%）条文件路径**本身**含
     *   error/failed/exception 字样（`libgpg-error.so.0`、`npm/lib/utils/audit-error.js` 这类
     *   包名），[DshLogBus.classifyLevel] 按整行文本跑正则会把它们判成 ERROR 刷红字 —— 那是
     *   假错误，排障现场里假错误的代价比没有错误更高。"正在解哪个文件"是进度事件、本无语义
     *   级别可言，appendProgress 正是为这类行开的窄口（脱敏与时间戳照旧，只跳过判级）。
     */
    private fun listener(onProgress: (Progress) -> Unit) =
        object : RuntimeUtils.InstallListener {
            private var lastEmitAt = 0L
            private var lastBusEmitAt = 0L

            override fun onUpdate(detail: String) {
                val now = System.currentTimeMillis()
                // 总线走自己的（更慢的）节奏：它要的是"一直在动"的观感，不是逐文件流水账
                if (shouldEmitExtractLine(lastBusEmitAt, now, EXTRACT_BUS_LINE_INTERVAL_MS)) {
                    lastBusEmitAt = now
                    // tar 是 `tar … .` 打的，条目名带 `./` 前缀：去掉它，行更短也更好和磁盘路径对照
                    DshLogBus.appendProgress("${EXTRACT_BUS_LINE_PREFIX} ${detail.removePrefix("./")}")
                }
                if (!shouldEmitExtractLine(lastEmitAt, now, DETAIL_THROTTLE_MS)) return
                lastEmitAt = now
                onProgress(Progress.Detail(detail))
            }

            override fun onStage(resId: Int) { /* dsh 用文本 Stage，忽略 resId */ }
        }

    /** 解压进度明细的最小刷新间隔（毫秒） */
    private const val DETAIL_THROTTLE_MS = 200L

    /**
     * BL-170：解包活动行写日志总线的最小间隔（毫秒）—— 2 秒一行。
     *
     * 取 2 秒而不是 [DETAIL_THROTTLE_MS] 的理由：面板要的是"还在动"的证据，不是逐文件流水账；
     * 200ms × 34000 文件会把 2000 行的环形缓冲整轮刷成解压清单，把真正要看的安装/启动行挤掉。
     */
    const val EXTRACT_BUS_LINE_INTERVAL_MS = 2_000L

    /** BL-170：解包活动行的前缀（与 `[bootstrap]`/`[app]` 同轨 —— 底座是 App 级，不带实例归属） */
    const val EXTRACT_BUS_LINE_PREFIX = "[extract]"

    /**
     * BL-170：解包活动行要不要落一行进日志总线（[listener] 的判据内核，纯函数）。
     *
     * ★ 抽出来放对象里（与 [requiredFreeBytes] 同一惯例）是为了能在 JVM 单测里直接钉住：
     *   整条解压链要 assets、要 tar.xz、要真文件系统，测试里构造不出来，而"什么时候写一行"恰恰是
     *   这轮唯一的可判定核心（写太密 = 把总线当流水账冲掉别的行，写太疏 = 面板又看起来像死的）。
     *   见 `R21FixBTest`。
     *
     * @param lastEmitMs 上一行的时间戳（毫秒）；`<= 0` = 这一轮还没写过（必写首行）
     * @param nowMs      本行的时间戳（毫秒）
     * @param intervalMs 最小间隔（毫秒）；`<= 0` = 不节流（每行都写）
     * @return true = 该写一行（调用方随即把 [lastEmitMs] 更新为 [nowMs]）
     *
     * ★ `nowMs < lastEmitMs`（系统时钟被校正回跳）判**放行**而不是拦住：回跳后差值恒为负，
     *   拦住就等于"整轮不再写一行"；放行则顺便把时间戳重新对齐到当前时钟。
     */
    @JvmStatic
    fun shouldEmitExtractLine(
        lastEmitMs: Long,
        nowMs: Long,
        intervalMs: Long = EXTRACT_BUS_LINE_INTERVAL_MS
    ): Boolean = lastEmitMs <= 0L || nowMs < lastEmitMs || nowMs - lastEmitMs >= intervalMs

    /** 任务保活用的 WakeLock 标签（决策 5）：解压与安装共用，tag 只做日志/排障标识 */
    private const val WAKELOCK_TAG = "dsh:bootstrap-extract"

    /**
     * 解压 rootfs 的 WakeLock 兜底超时：20 分钟（保持原 `WAKELOCK_TIMEOUT_MS` 值，不降不改）。
     * 实测依据出自 bebug：解压全程约 3 分钟（`20:53:31` 持锁 → `20:56:41` 底座就绪），20 分钟是
     * "忘记释放"的兜底上限而非任务时长。public：供安装链与测试/文档引用同一口径。
     */
    const val EXTRACT_WAKE_LOCK_TIMEOUT_MS = 20 * 60_000L

    /**
     * npm 安装期的 WakeLock 兜底超时：40 分钟 = [DshInstaller.INSTALL_TIMEOUT_MS]（30 分钟看门狗）
     * + 10 分钟余量。约定由安装链在调用 [withRuntimeBusyWakeLock] 时传入（本文件只定义常量）。
     */
    const val INSTALL_WAKE_LOCK_TIMEOUT_MS = 40 * 60_000L
}

/**
 * 两个脚本版本号是否等价（BL-214，fix10）。
 *
 * 口径与 `RuntimeUtils.isLatest` 的历史兼容行为一致：字符串相等，或两边都是纯数字时数值相等
 * （`"007"` 与 `"7"` 视为同版本 —— 解压写盘的是 assets 原文，理论上不会出现前导零，
 * 但历史版本文件曾手改过，宽松比较能少一次无谓重解压）。
 *
 * 写成**顶层函数**而不是放进 [DshBootstrap]：单测 harness 的 classpath 没有 androidx，
 * 经 object 访问会拉起父类链（同 `trimDeltaFor` / `isRowBusy` 的既有理由）。
 */
fun scriptsVersionSame(installed: String, asset: String): Boolean {
    if (installed == asset) return true
    return try {
        java.lang.Long.parseLong(installed) == java.lang.Long.parseLong(asset)
    } catch (e: NumberFormatException) {
        false
    }
}
