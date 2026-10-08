package com.dsh.core

import android.content.Context
import com.dsh.fcl.androidlauncher.R
import com.tungsten.fclauncher.utils.FCLPath
import java.io.File

/**
 * proot 命令的**唯一**构造处。
 *
 * ## 修的问题：两份漂移的命令行
 * 原来 [ProotProcessExecutor]（安装）和 [DshRuntime]（启动）各自手写了一整段 proot 参数，
 * 内容已经出现不一致（bind 项、工作目录、环境变量白名单都不同），任一处改动另一处不会跟着改，
 * 是典型的"下一个人必踩"的坑。现在统一到这里，附带预检与安全约束。
 *
 * ## 安全约束
 * - 命令一律用数组参数给 ProcessBuilder，**不做字符串拼接**，避免注入。
 * - **密钥不进 argv**：需要传给子进程的敏感值只走 [Spec.procEnv]（子进程环境），
 *   不放进命令行 —— 放在命令行里任何能看到 `ps` 的地方都能读到。
 *   （当前启动器**不再传任何凭据**：Key 与模型都归 dsh 自己的设置管，见 [DshRuntime] 的启动段注释。
 *   这一条约束留着，是因为 [DshRuntime] 仍会往 procEnv 放 `PROOT_NO_SECCOMP` 这类值。）
 * - 环境变量白名单（[isAllowedEnvKey]）拦掉 `LD_PRELOAD` 之类的高危变量注入。
 * - ⚠️ **[isAllowedEnvKey] 里的 `CRED_FILE` 与 `DEEPSEEK_` 通配现在是冗余项**：
 *   启动器已不再注入它们（脚本侧也不再读 `CRED_FILE`）。**刻意留着不删** ——
 *   它们是"将来若要再传此类值，入口已经在这里"的标记；删掉只会让下一个人重新想一遍。
 *   真要删时，请连同这条注释一起删，别只删一半。
 *
 * ## 布局约定
 * 宿主 `<filesDir>/dsh` 通过 `--bind` 暴露为 rootfs 内的 `/opt/dsh`，
 * 于是实例目录是 `/opt/dsh/instances/<id>`，脚本是 `/opt/dsh/scripts/` 下的 _.sh 文件。
 */
object ProotCommand {

    /** rootfs 内挂载点 */
    const val GUEST_ROOT = "/opt/dsh"

    const val DEFAULT_PATH = "/opt/node22/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** 一次 proot 调用的完整描述 */
    data class Spec(
        val argv: List<String>,
        /** 通过命令行 `/usr/bin/env` 传入的非敏感变量（保持与 PoC 验证过的行为一致） */
        val argvEnv: Map<String, String>,
        /** 通过 ProcessBuilder 环境传入的变量（含密钥，不出现在 argv 里） */
        val procEnv: Map<String, String>
    ) {
        fun toProcessBuilder(): ProcessBuilder {
            val pb = ProcessBuilder(argv)
            pb.redirectErrorStream(true)
            // 先把继承来的环境清干净再注入白名单内的项，避免宿主环境串进 rootfs
            val env = pb.environment()
            env.clear()
            env["PATH"] = DEFAULT_PATH
            env["HOME"] = "/root"
            procEnv.forEach { (k, v) -> if (isAllowedEnvKey(k)) env[k] = v }
            argvEnv.forEach { (k, v) -> if (isAllowedEnvKey(k)) env[k] = v }
            return pb
        }
    }

    /** 预检结果：不满足就直接给用户一句人话，而不是让 proot 抛出晦涩错误 */
    data class Preflight(val ok: Boolean, val reason: String? = null)

    /**
     * 启动前预检：proot 二进制可执行 + rootfs 可引导 + 脚本存在。
     * @param scriptRootfsPath 要执行的 rootfs 内脚本路径（可空表示只查底座）
     *
     * ## 文案为什么要现取一份 Context
     * [reason] 的去向是启动失败对话框与实例卡片的错误行 —— 是**给人看的话**，不是日志。
     * 但调用方传进来的 Context 常常是 `applicationContext`（见 [DshServices] 的单例口径），
     * 而它的资源语言只跟随系统、**不认启动器设置里选的语言**。所以这里和 [DshBootstrap] 用同一招：
     * 取当前 Activity 的 Context 并套上设置里的语言，拿不到才退回传进来的那个。
     */
    @JvmStatic
    fun preflight(context: Context, rootfsDir: String, scriptRootfsPath: String? = null): Preflight {
        val ctx = DshUiText.context(context)
        val proot = DshPaths.resolveProotBin(FCLPath.NATIVE_LIB_DIR)
        when {
            !proot.exists() ->
                return Preflight(false, ctx.getString(R.string.dsh_preflight_no_proot))
            !proot.canExecute() ->
                return Preflight(false, ctx.getString(R.string.dsh_preflight_proot_not_executable))
        }
        if (!File(rootfsDir).isDirectory) {
            return Preflight(false, ctx.getString(R.string.dsh_preflight_rootfs_not_ready))
        }
        // ★ 走 proot 之前先把宿主 DNS 同步进 guest（Android 没有 /etc/resolv.conf，
        //   不写的话 guest 内 getaddrinfo 必失败 → npm/curl 全部 EAI_AGAIN）。
        //   放在这里是因为所有 proot 调用（安装 / 启动 / 自检）都先过 preflight。
        DshDns.sync(context, rootfsDir)
        if (!DshPaths.rootfsLooksUsable(File(rootfsDir))) {
            return Preflight(false, ctx.getString(R.string.dsh_preflight_rootfs_incomplete))
        }
        if (scriptRootfsPath != null) {
            val rel = scriptRootfsPath.removePrefix("$GUEST_ROOT/scripts/")
            val host = File(DshPaths.SCRIPTS_DIR, rel)
            if (!host.isFile) {
                return Preflight(false, ctx.getString(R.string.dsh_preflight_missing_script, host.path))
            }
        }
        return Preflight(true)
    }

    /**
     * 预检文案用的 Context（当前 Activity 优先，见 [preflight] 的说明）。
     *
     * `setLanguage()` 在这里是**读**偏好 + `createConfigurationContext`，没有写盘：
     * 它只在生成 locale 相关的资源表，不会与 UI 线程正在读的同一张表较劲，
     * 在 IO 线程上调用是安全的。
     */
    /**
     * 构造一次 proot 调用。
     *
     * @param script rootfs 内要执行的脚本，如 `/opt/dsh/scripts/start-dsh.sh`
     * @param argvEnv 非敏感配置（INSTANCE_DIR/PORT/PROFILE/...）
     * @param procEnv 不宜出现在命令行里的值（当前是 PROOT_NO_SECCOMP；见 [isAllowedEnvKey] 的说明）
     * @param workDirRootfs 初始工作目录（默认 /root；实例运行时应传实例 workspace）
     * @param bindCacheAsTmp 是否把宿主 cacheDir/dsh/tmp 挂成 /tmp（默认是）
     */
    @JvmStatic
    fun build(
        context: Context,
        script: String,
        argvEnv: Map<String, String> = emptyMap(),
        procEnv: Map<String, String> = emptyMap(),
        workDirRootfs: String = "/root",
        rootfsDir: String = DshPaths.ROOTFS_DIR,
        bindCacheAsTmp: Boolean = true
    ): Spec {
        val prootBin = DshPaths.resolveProotBin(FCLPath.NATIVE_LIB_DIR).absolutePath
        val argv = buildList {
            add(prootBin)
            add("-r"); add(rootfsDir)          // 新根
            add("-0")                           // 伪装 root（npm/apt 需要）
            add("-w"); add(workDirRootfs)       // 工作目录
            add("--link2symlink")               // 绕开安卓 fs 无硬链接
            add("--kill-on-exit")               // 主进程退出时清理子进程（避免僵尸 node）
            // 内核伪文件系统
            add("--bind=/dev")
            add("--bind=/proc")
            add("--bind=/sys")
            add("--bind=/dev/urandom:/dev/random")
            // App 私有 dsh 目录 → rootfs 内 /opt/dsh（脚本与实例都在这下面）
            add("--bind=${DshPaths.ROOT_DIR}:$GUEST_ROOT")
            if (bindCacheAsTmp) {
                add("--bind=${DshPaths.TMP_DIR}:/tmp")
            }
            // 干净环境下执行目标脚本；非敏感项走 argv，敏感项走 procEnv
            add("/usr/bin/env")
            add("PATH=$DEFAULT_PATH")
            add("HOME=/root")
            add("TMPDIR=/tmp")
            add("LANG=C.UTF-8")
            add("TERM=xterm-256color")
            argvEnv.forEach { (k, v) -> if (isAllowedEnvKey(k)) add("$k=$v") }
            add("/bin/sh")
            add(script)
        }
        val env = HashMap<String, String>()
        env["PROOT_TMP_DIR"] = DshPaths.TMP_DIR
        env["PROOT_LOADER"] = DshPaths.resolveProotLoader(FCLPath.NATIVE_LIB_DIR).absolutePath
        // 子进程环境：密钥在这里，不出现在命令行
        procEnv.forEach { (k, v) -> if (isAllowedEnvKey(k)) env[k] = v }
        return Spec(argv, argvEnv, env)
    }

    /**
     * 只允许注入已知键：防止调用方（或被篡改的调用点）塞入 `LD_PRELOAD`/`LD_LIBRARY_PATH` 之类
     * 会改变 rootfs 内动态链接行为的变量。
     */
    fun isAllowedEnvKey(key: String): Boolean = when (key) {
        "PATH", "HOME", "TMPDIR", "LANG", "TERM", "USER", "SHELL",
        "INSTANCE_DIR", "DSH_VERSION", "PORT", "HOST", "PROFILE", "DSH_HOME", "CRED_FILE",
        "NODE_MAJOR", "NPM_CONFIG_CACHE", "NODE_OPTIONS", "CI", "READY_TIMEOUT",
        "PROOT_TMP_DIR", "PROOT_LOADER", "PROOT_NO_SECCOMP" -> true
        else -> key.startsWith("DEEPSEEK_")
    }
}
