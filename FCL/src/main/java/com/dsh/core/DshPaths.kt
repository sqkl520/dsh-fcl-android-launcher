package com.dsh.core

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * dsh 启动器的集中路径定义，仿 [com.tungsten.fclauncher.utils.FCLPath] 的组织方式。
 *
 * 目录布局（都在 App 私有目录内，无需存储权限）：
 * ```
 * <filesDir>/dsh/
 *   rootfs/            解压后的 proot Linux rootfs（Ubuntu-arm64 或 Alpine-arm64），所有实例共享
 *   proot/             assets 方案的 proot 二进制解压目录（推荐走 jniLibs，见 DshBootstrap）
 *   scripts/           setup-node-dsh.sh / start-dsh.sh
 *   npm-cache/         所有实例共享的 npm 缓存（重复安装 dsh 不再重新下载 ~300MB）
 *   logs/runtime.log   运行日志（DshLogBus 落盘，1MB 截断）
 *   instances/         每个 dsh 实例一个子目录，互相隔离
 *     <instanceId>/
 *       node_modules/   该实例安装的 @deepseek-ai/dsh 及依赖（约 300MB）
 *       package.json
 *       home/           DSH_HOME：会话、配置、profile、免登录 cookie
 *       workspace/      agent 的工作目录（proot 内 chdir 到这里）
 *       dsh.pid         运行中的 proot 进程 pid（用于清理孤儿进程）
 *   instances.json     实例清单元数据（DshInstances 持久化）
 * <cacheDir>/dsh/
 *   tarballs/         下载中的 npm tarball 临时文件
 *   tmp/              proot / rootfs 内 /tmp 的宿主目录
 * ```
 *
 * 说明：真正在 rootfs 内的绝对路径由 proot 的 -w/-r/--bind 决定，
 * 这里存的是安卓文件系统里的宿主路径（会通过 proot bind 暴露给 rootfs）。
 */
object DshPaths {

    /** dsh 根目录：<filesDir>/dsh */
    lateinit var ROOT_DIR: String
        private set

    /** 共享 rootfs 解压目录 */
    lateinit var ROOTFS_DIR: String
        private set

    /** 所有实例的父目录 */
    lateinit var INSTANCES_DIR: String
        private set

    /** 实例清单元数据文件 */
    lateinit var INSTANCES_MANIFEST: String
        private set

    /** 下载临时目录（cacheDir 下，系统可回收） */
    lateinit var TARBALL_CACHE_DIR: String
        private set

    /** proot 打包脚本所在目录（从 assets 解压出来的一份，供实例复用） */
    lateinit var SCRIPTS_DIR: String
        private set

    /** 共享 npm 缓存目录（挂在 rootfs 内 /opt/dsh/npm-cache） */
    lateinit var NPM_CACHE_DIR: String
        private set

    /** 运行日志文件（DshLogBus 落盘） */
    lateinit var LOG_FILE: String
        private set

    /** proot 用的宿主临时目录（挂在 rootfs 内 /tmp） */
    lateinit var TMP_DIR: String
        private set

    /** 是否已完成 [loadPaths]（避免未初始化就访问 lateinit） */
    var isLoaded: Boolean = false
        private set

    /**
     * 初始化所有路径并创建目录。
     * @return 创建失败时的可读原因（成功为 null）。失败要显式告诉用户，而不是等到后面
     *         在 proot 里抛一堆看不懂的错（磁盘满/权限异常等）。
     */
    @JvmStatic
    fun loadPaths(context: Context): String? {
        val filesDir = context.filesDir.absolutePath
        ROOT_DIR = "$filesDir/dsh"
        ROOTFS_DIR = "$ROOT_DIR/rootfs"
        INSTANCES_DIR = "$ROOT_DIR/instances"
        INSTANCES_MANIFEST = "$ROOT_DIR/instances.json"
        SCRIPTS_DIR = "$ROOT_DIR/scripts"
        NPM_CACHE_DIR = "$ROOT_DIR/npm-cache"
        LOG_FILE = "$ROOT_DIR/logs/runtime.log"
        TARBALL_CACHE_DIR = "${context.cacheDir.absolutePath}/dsh/tarballs"
        TMP_DIR = "${context.cacheDir.absolutePath}/dsh/tmp"

        val failed = mutableListOf<String>()
        listOf(ROOT_DIR, ROOTFS_DIR, INSTANCES_DIR, SCRIPTS_DIR, NPM_CACHE_DIR, TARBALL_CACHE_DIR, TMP_DIR)
            .forEach { d ->
                val f = File(d)
                if (!f.exists() && !f.mkdirs() && !f.isDirectory) failed += d
            }
        isLoaded = true
        // 日志落盘位置就绪后立刻绑定（进程崩了也能事后取证）
        runCatching { DshLogBus.attachFile(File(LOG_FILE)) }
        return if (failed.isEmpty()) null else failed.joinToString(", ")
    }

    /** 未初始化时的兜底：返回 false 而不是抛 UninitializedPropertyAccessException */
    fun isUsable(): Boolean = isLoaded && File(ROOT_DIR).isDirectory

    /** 某实例的根目录 */
    fun instanceDir(instanceId: String): File = File(INSTANCES_DIR, instanceId)

    /** 某实例的 DSH_HOME（会话/配置/cookie 都在这，重装 dsh 也不丢） */
    fun instanceHome(instanceId: String): File = File(instanceDir(instanceId), "home")

    /**
     * agent 工作目录。
     * **改进点**：原来 proot 的工作目录固定是 rootfs 内的 `/root`，而 rootfs 是"可被重新解压覆盖"的
     * 共享底座——一旦底座升级重解压，agent 在里面写的文件全没了。现在给每个实例一个宿主侧的
     * `workspace/` 目录，bind 进 rootfs 并 chdir 进去，重装/升级都不丢。
     */
    fun instanceWorkspace(instanceId: String): File = File(instanceDir(instanceId), "workspace")

    /** 实例内 dsh 包的相对路径 */
    private const val INSTANCE_DSH_REL = "node_modules/@deepseek-ai/dsh"

    /**
     * rootfs 内**预装** dsh 包的相对路径。
     *
     * ★ 必须与 `setup-node-dsh.sh` 的 `DSH_PREINSTALL_DIR`（默认 `/opt/dsh-preinstalled`）
     * 和 `start-dsh.sh` 的三级入口解析保持一致 —— 改一处必须三处同步。
     * 宿主路径 = `<ROOTFS_DIR>/opt/dsh-preinstalled/...`（rootfs 解压后即可读到）。
     */
    const val PREINSTALLED_DSH_REL = "opt/dsh-preinstalled/node_modules/@deepseek-ai/dsh"

    /**
     * 该实例**实际生效**的 dsh 包目录（判断「装好了没」「能不能启动」的权威依据）。
     *
     * 解析顺序（与 `start-dsh.sh` 的入口解析一致）：
     * 1. 实例自己的 `node_modules/@deepseek-ai/dsh`（多版本隔离，优先）
     * 2. rootfs 内预装的 `/opt/dsh-preinstalled/...`（阶段 D-1 的优化：命中预装版本时
     *    `setup-node-dsh.sh` 直接 `DONE source=preinstalled` 并**跳过下载**，
     *    此时实例目录里根本没有 node_modules —— 只查实例路径会把装好的实例误判成
     *    「未安装/BROKEN」，实例永远无法 READY，也永远无法启动）
     * 3. 两处都没有时返回实例内的预期路径（错误信息/日志据此定位）
     *
     * 抽成「接受目录参数」的纯函数便于单测（见 `DshCoreLogicTest`）。
     */
    fun effectiveDshDir(instanceDir: File, rootfsDir: File): File {
        val inInstance = File(instanceDir, INSTANCE_DSH_REL)
        if (File(inInstance, "package.json").isFile) return inInstance
        val preinstalled = File(rootfsDir, PREINSTALLED_DSH_REL)
        if (File(preinstalled, "package.json").isFile) return preinstalled
        return inInstance
    }

    fun effectiveDshDir(instanceId: String): File =
        effectiveDshDir(instanceDir(instanceId), File(if (isLoaded) ROOTFS_DIR else ""))

    /** 该实例实际生效的 dsh `package.json`（实例内优先，其次 rootfs 预装） */
    fun effectiveDshPackageJson(instanceId: String): File =
        File(effectiveDshDir(instanceId), "package.json")

    /** 该实例实际生效的 dsh 入口 `lib/bin.js`（实例内优先，其次 rootfs 预装） */
    fun effectiveDshBinJs(instanceId: String): File =
        File(effectiveDshDir(instanceId), "lib/bin.js")

    /** 运行中 proot 进程的 pid 文件（孤儿进程清理用） */
    fun instancePidFile(instanceId: String): File = File(instanceDir(instanceId), "dsh.pid")

    /** 旧的运行期明文凭据文件（已废弃：现在密钥走进程环境变量，不再落盘） */
    fun instanceLegacyCredentials(instanceId: String): File =
        File(instanceDir(instanceId), "credentials.env")

    /** Keystore 加密后的 API key 密文 */
    fun instanceCredentialsEnc(instanceId: String): File =
        File(instanceDir(instanceId), "credentials.enc")

    /** proot 二进制解压目录（assets 方案；jniLibs 方案则在 nativeLibraryDir） */
    fun prootDir(): File = File(ROOT_DIR, "proot")

    /**
     * 解析 proot 主程序路径：优先用 jniLibs 打包（nativeLibraryDir 自带执行位，最稳），
     * 回退到 assets 解压目录（[DshBootstrap] 解压并加执行位）。
     *
     * 注意：targetSdk>=29 起，App 私有数据目录里的可执行文件受 W^X/SELinux 限制，
     * assets 方案在多数新机型上会 exec 失败，所以 jniLibs 方案实际是首选。
     * @param nativeLibDir 传入 FCLPath.NATIVE_LIB_DIR；该字段可能为空（FCLPath 未初始化），
     *                     这里容忍 null 并直接走回退路径（原来会 NPE）。
     */
    fun resolveProotBin(nativeLibDir: String?): File {
        if (!nativeLibDir.isNullOrEmpty()) {
            val inJni = File(nativeLibDir, "libproot.so")
            if (inJni.isFile) return inJni
        }
        return File(prootDir(), "libproot.so")
    }

    /** proot loader 路径，规则同 [resolveProotBin] */
    fun resolveProotLoader(nativeLibDir: String?): File {
        if (!nativeLibDir.isNullOrEmpty()) {
            val inJni = File(nativeLibDir, "libproot-loader.so")
            if (inJni.isFile) return inJni
        }
        return File(prootDir(), "libproot_loader.so")
    }

    /**
     * busybox 路径：jniLibs 的 `libbusybox.so`（nativeLibraryDir 有执行位，可在 W^X 下 execve）。
     * rootfs 内的 `/bin/busybox` 通常是指向它的软链（由 DshBootstrap 建立）。
     */
    fun resolveBusybox(nativeLibDir: String?): File {
        if (!nativeLibDir.isNullOrEmpty()) {
            val inJni = File(nativeLibDir, "libbusybox.so")
            if (inJni.isFile) return inJni
        }
        return File(prootDir(), "libbusybox.so")
    }

    /** rootfs 是否看起来可引导（必须有 /bin/sh 或 /usr/bin/env） */
    fun rootfsLooksUsable(): Boolean {
        if (!isLoaded) return false
        return rootfsLooksUsable(File(ROOTFS_DIR))
    }

    /**
     * 同上，但检查**指定目录**。
     * ★ 第十一轮：`ProotCommand.preflight()` 收 `rootfsDir` 参数却调用无参版本（看的是
     * [ROOTFS_DIR]），传非默认 rootfs 时预检结论会失真。现在预检用这个重载，参数与判据一致。
     */
    fun rootfsLooksUsable(root: File): Boolean =
        File(root, "bin/sh").exists() ||
            File(root, "usr/bin/env").exists() ||
            File(root, "bin/busybox").exists()

    // --- 磁盘 ---------------------------------------------------------------

    /** filesDir 所在分区剩余空间（字节） */
    fun freeSpaceBytes(): Long = runCatching {
        val stat = StatFs(File(ROOT_DIR.ifEmpty { "/data" }).absolutePath)
        stat.availableBytes
    }.getOrDefault(-1L)

    /** 递归统计目录体积（IO 线程用；UI 展示"占用"） */
    fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { f -> if (f.isFile) total += f.length() }
        return total
    }

    /** 可读的体积文本 */
    fun formatSize(bytes: Long): String = when {
        bytes < 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    }
}
