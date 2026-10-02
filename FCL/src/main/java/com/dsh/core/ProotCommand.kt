package com.dsh.core

import android.content.Context
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
 * - **密钥不进 argv**：API key 只通过 [Spec.procEnv]（子进程环境）传递。原来通过
 *   `/usr/bin/env DEEPSEEK_API_KEY=xxx` 放在命令行里，任何能看到 `ps` 的地方都能读到。
 * - 环境变量白名单（[isAllowedEnvKey]）拦掉 `LD_PRELOAD` 之类的高危变量注入。
 *
 * ## 布局约定
 * 宿主 `<filesDir>/dsh` 通过 `--bind` 暴露为 rootfs 内的 `/opt/dsh`，
 * 于是实例目录是 `/opt/dsh/instances/<id>`，脚本是 `/opt/dsh/scripts/` 下的 _.sh 文件。
 */
object ProotCommand {

    /** rootfs 内挂载点 */
    const val GUEST_ROOT = "/opt/dsh"

    const val DEFAULT_PATH = "/opt/dsh/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

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
     */
    @JvmStatic
    fun preflight(context: Context, rootfsDir: String, scriptRootfsPath: String? = null): Preflight {
        val proot = DshPaths.resolveProotBin(FCLPath.NATIVE_LIB_DIR)
        when {
            !proot.exists() ->
                return Preflight(
                    false,
                    "缺少 proot 可执行文件（期望 jniLibs 里的 libproot.so 或 assets/dsh/proot/libproot.so）"
                )
            !proot.canExecute() ->
                return Preflight(
                    false,
                    "proot 二进制没有执行权限（assets 方案在 Android 10+ 常因 W^X 限制无法执行，" +
                        "请改用 jniLibs 打包）"
                )
        }
        if (!File(rootfsDir).isDirectory) {
            return Preflight(false, "rootfs 未就绪（未解压或解压不完整）")
        }
        if (!DshPaths.rootfsLooksUsable()) {
            return Preflight(
                false,
                "rootfs 内容不完整（找不到 /bin/sh 或 /usr/bin/env）——" +
                    "常见原因：rootfs.tar.xz 里带了顶层目录，解压后多套了一层"
            )
        }
        if (scriptRootfsPath != null) {
            val rel = scriptRootfsPath.removePrefix("$GUEST_ROOT/scripts/")
            val host = File(DshPaths.SCRIPTS_DIR, rel)
            if (!host.isFile) return Preflight(false, "缺少脚本 $host（运行时底座未解压）")
        }
        return Preflight(true)
    }

    /**
     * 构造一次 proot 调用。
     *
     * @param script rootfs 内要执行的脚本，如 `/opt/dsh/scripts/start-dsh.sh`
     * @param argvEnv 非敏感配置（INSTANCE_DIR/PORT/PROFILE/...）
     * @param procEnv 敏感或需隐藏的配置（DEEPSEEK_API_KEY/...）
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
