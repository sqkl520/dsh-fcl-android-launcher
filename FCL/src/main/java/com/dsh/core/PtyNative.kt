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
 */
object PtyNative {

    init {
        System.loadLibrary("ptyjni")
    }

    /**
     * fork 一个 PTY 会话并在其中执行 [cmd]。
     * @return 主端（master）fd；失败返回负值。
     */
    fun forkPty(
        cmd: String,
        args: Array<String>?,
        envVars: Array<String>?,
        rows: Int,
        cols: Int
    ): Int = nativeForkPty(cmd, args, envVars, rows, cols)

    /** 读主端；无数据或中断返回 0，出错返回负值 */
    fun read(fd: Int, buf: ByteArray, offset: Int = 0, length: Int = buf.size): Int =
        nativeRead(fd, buf, offset, length)

    fun write(fd: Int, buf: ByteArray, offset: Int = 0, length: Int = buf.size): Int =
        nativeWrite(fd, buf, offset, length)

    fun resize(fd: Int, rows: Int, cols: Int): Int = nativeResize(fd, rows, cols)

    /** 非阻塞等待某 pid：0 仍在运行，>=0 已退出（退出码），负值异常 */
    fun waitPid(pid: Int): Int = nativeWaitPid(pid)

    fun close(fd: Int) = nativeClose(fd)

    fun getPid(): Int = nativeGetPid()

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
