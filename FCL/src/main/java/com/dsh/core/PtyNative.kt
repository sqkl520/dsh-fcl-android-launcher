package com.dsh.core

/**
 * PTY（伪终端）原生桥接。
 *
 * ## 为什么需要它
 * dsh 是面向真实终端的程序：它要读 TERM、要 tty、要能收发控制字符。
 * 若用 `ProcessBuilder` 直接起 proot，子进程只有管道（pipe）而非 tty ——
 * 表现是：输出不带 tty 语义、无法正确 resize、`dsh web` 的交互与日志抓取都更脆弱。
 *
 * ## 来源
 * 原生实现（`ptyjni.c`）取自 [oonid/pr](https://github.com/oonid/pr) 的 `:proot-engine`
 * （MIT），随本项目 `src/main/cpp/ptyjni/` 编译，ABI 固定 arm64-v8a。
 * 它做四件事：打开 `/dev/ptmx`、`grantpt`/`unlockpt` 拿从端、`fork` 后在子进程 `setsid`
 * 并把 0/1/2 重定向到从端、`execv` 目标程序。
 *
 * 注意：本类**只是执行通道**。绕过 W^X 靠的是 proot 自身的 `PROOT_LOADER` 机制
 * （loader 放 nativeLibraryDir），与本类无关。
 *
 * ## 失败表面：加载失败不抛异常，而是留下可查询的标志
 * `loadLibrary` 失败时抛的是 `UnsatisfiedLinkError`（`Error`，不是 `Exception`），而且
 * **失败发生在进入任何方法之前** —— 所以调用方连"看返回值是否 < 0"的机会都没有。
 * 更糟的是 JVM 会记住类初始化失败：第一次触达抛 `UnsatisfiedLinkError`，之后每次都退化成
 * `NoClassDefFoundError`，失败原因反而被掩盖。
 *
 * 因此 [init] 自己兜住 `Throwable`（类初始化**永不**抛），把原因存进 [loadError]，对外暴露
 * [isAvailable] / [loadErrorOrNull]；本类**每一个** native 入口都先查该标志，不可用时返回
 * 各方法 KDoc 里已声明的负值，**不再触达 native**。
 *
 * ★ 覆盖边界（verify-r31-243 的 N1，2026-11-13 更正）：本机制覆盖的是 **`loadLibrary` 失败**
 * （库缺失 / ABI 不匹配 / 依赖库缺失）。它**不覆盖**「库加载成功、但某个 native 符号没导出」
 * 这种情况 —— 那时 `isAvailable` 为 true、`loadErrorOrNull()` 为 null，`guarded` 放行后
 * 调用那个符号仍会抛 `UnsatisfiedLinkError`（实测两次触达都抛，消息形如
 * `'int Xxx.符号名()'`）。那正是 `ptyjni.c:15-19` 自述的历史故障形态（符号名不匹配）。
 * 要覆盖它需在 native 调用侧再套一层 try/catch，或启动期做一次自检；两者都超出本类职责，
 * 故此处只如实收窄声明。当前全仓 0 个生产调用点，所以这条边界暂无线上影响。
 *
 * （注：本段刻意避免写出「native 前缀 + 大写字母 + 左括号」的完整调用形态 ——
 * `R31Fix233Test` 有一条源码级断言用正则扫所有这类出现处，并要求每处都在 `guarded(` 里，
 * 文档里写实例会被误抓成未加守卫的调用点。）
 *
 * 真实场景：ABI 固定 arm64-v8a（本文件 KDoc 上一段），32 位设备或 x86 模拟器上这条路径必被触发。
 */
object PtyNative {

    /** 各入口统一的失败返回值（= 各自 KDoc 里声明的「失败返回负值」）。 */
    private const val FAILED = -1

    /**
     * `System.loadLibrary` 的失败原因；`null` = 已加载。
     * `@Volatile`：只写一次（init），但会被任意线程读（见 [isAvailable]）。
     */
    @Volatile
    private var loadError: Throwable? = null

    init {
        // ★ 这里**必须**是自己的 try/catch，不能用 runCatching 表达"以后可能不可用"：
        //   类初始化一旦抛异常，JVM 会缓存这次失败并把后续触达改写成 NoClassDefFoundError，
        //   调用方再也看不到真正的原因（UnsatisfiedLinkError 里才有 lib 名与 ABI 线索）。
        try {
            System.loadLibrary("ptyjni")
        } catch (t: Throwable) {
            loadError = t
        }
    }

    /** native 库是否可用。为 `false` 时所有入口返回各自声明的失败值，不会触达 native。 */
    val isAvailable: Boolean
        get() = loadError == null

    /** `loadLibrary` 失败的原因（可用时为 `null`）。诊断入口可直接读它，不必翻 logcat。 */
    fun loadErrorOrNull(): Throwable? = loadError

    /**
     * 统一的 native 调用闸门：不可用时直接给 [unavailable]，**不进入** native。
     * 抽成一个地方是为了让"每个入口都要查标志"这件事在源码里可机械核对
     * （测试 `R31Fix233Test` 会断言所有 `native*` 调用都发生在 `guarded(` 之内）。
     */
    private inline fun <T> guarded(unavailable: T, body: () -> T): T =
        if (loadError == null) body() else unavailable

    /**
     * fork 一个 PTY 会话并在其中执行 [cmd]。
     * @return 主端（master）fd；失败返回负值（含 native 库不可用）。
     */
    fun forkPty(
        cmd: String,
        args: Array<String>?,
        envVars: Array<String>?,
        rows: Int,
        cols: Int
    ): Int = guarded(FAILED) { nativeForkPty(cmd, args, envVars, rows, cols) }

    /** 读主端；无数据或中断返回 0，出错返回负值（含 native 库不可用） */
    fun read(fd: Int, buf: ByteArray, offset: Int = 0, length: Int = buf.size): Int =
        guarded(FAILED) { nativeRead(fd, buf, offset, length) }

    fun write(fd: Int, buf: ByteArray, offset: Int = 0, length: Int = buf.size): Int =
        guarded(FAILED) { nativeWrite(fd, buf, offset, length) }

    fun resize(fd: Int, rows: Int, cols: Int): Int = guarded(FAILED) { nativeResize(fd, rows, cols) }

    /** 非阻塞等待某 pid：0 仍在运行，>=0 已退出（退出码），负值异常（含 native 库不可用） */
    fun waitPid(pid: Int): Int = guarded(FAILED) { nativeWaitPid(pid) }

    /** 关主端 fd。库不可用时是 no-op（没有 fd 可关，也不该为此抛异常）。 */
    fun close(fd: Int) = guarded(Unit) { nativeClose(fd) }

    /** 最近一次 fork 的子进程 pid；没有/库不可用时为负值。 */
    fun getPid(): Int = guarded(FAILED) { nativeGetPid() }

    private external fun nativeForkPty(
        cmd: String, args: Array<String>?, envVars: Array<String>?, rows: Int, cols: Int
    ): Int

    private external fun nativeRead(fd: Int, buf: ByteArray, offset: Int, length: Int): Int
    private external fun nativeWrite(fd: Int, buf: ByteArray, offset: Int, length: Int): Int
    private external fun nativeResize(fd: Int, rows: Int, cols: Int): Int
    private external fun nativeWaitPid(pid: Int): Int
    private external fun nativeClose(fd: Int)
    private external fun nativeGetPid(): Int
}
