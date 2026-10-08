package com.dsh.core

import android.content.Context
import android.net.ConnectivityManager
import java.io.File

/**
 * 把宿主的 DNS 配置同步进 guest 的 `/etc/resolv.conf`。
 *
 * ## 为什么必须做（真机实测的阻塞级问题）
 * Android **没有** `/etc/resolv.conf`（DNS 由 netd 管理），而 rootfs 里也不该预置一份写死的
 * 配置（换网络就失效）。于是 guest 内所有域名解析都会失败：
 *
 * ```
 * npm error code EAI_AGAIN
 * npm error syscall getaddrinfo
 * npm error request to https://registry.npmjs.org/... failed
 * ```
 *
 * 即：**只要不写 resolv.conf，`npm install` 永远失败**（装 dsh、装插件全部不可用）。
 *
 * ## 做法
 * 每次走 proot 之前，从 [ConnectivityManager] 取当前网络的 DNS 服务器，写进
 * `<rootfs>/etc/resolv.conf`。取不到时回退公共 DNS，保证至少可用。
 *
 * 这是**宿主侧写文件**（rootfs 是解压出来的普通目录），不需要进 proot。
 */
object DshDns {

    /** 取不到系统 DNS 时的兜底（公共 DNS，保证基本可用） */
    private val FALLBACK = listOf("8.8.8.8", "1.1.1.1")

    /** guest 内 resolv.conf 的宿主路径 */
    fun resolvConfOf(rootfsDir: String): File = File(rootfsDir, "etc/resolv.conf")

    /**
     * 同步一次。返回是否成功写入。
     * 幂等：每次调用都按当前网络重写（切换 Wi-Fi/流量后 DNS 会变）。
     *
     * 成功时往**全局日志流**（[DshLogBus.append]，不是 `appendFor`）打一行：
     * DNS 同步发生在每次 proot 调用之前，是 App 级动作，不属于任何一个实例。
     */
    @JvmStatic
    fun sync(context: Context, rootfsDir: String): Boolean {
        // ★ 取 DNS 与写文件都包在 runCatching 里，但**日志留在外面**：两件事的理由不同 ——
        //   前者是"这一步失败也要返回 false，不能让排障动作本身把调用方崩掉"，
        //   后者是"这行日志是排障时唯一的线索（见下面那句），绝不能一起被吞掉"。
        //   所以下面用「先在 try 里算出结果，再在 try 外打日志」的写法，
        //   而不是把 append 塞进 runCatching 的块里。
        val resolved = runCatching { resolveServers(context, rootfsDir) }.getOrNull()
            ?: return false

        // 真机上 npm install 报 EAI_AGAIN（域名解析失败）时，日志里最想知道的就是
        // "guest 的 resolv.conf 到底被写成了什么" —— 只打"已同步"没有信息量：
        // **回退到公共 DNS（8.8.8.8 等）在国内网络常常不可达**，而它恰恰是 EAI_AGAIN 的常见原因，
        // 所以这一行必须把"用的是系统 DNS 还是回退值"说清楚，否则排障只能靠猜。
        DshLogBus.append(
            "[dns] resolv.conf ← ${resolved.servers.joinToString(" ")}" +
                if (resolved.usedFallback) "（回退公共 DNS）" else ""
        )
        return true
    }

    /** [sync] 的计算结果：写进去的那份列表，以及"它是回退来的还是系统给的" */
    private class Resolved(val servers: List<String>, val usedFallback: Boolean)

    /**
     * 解析出要写进 resolv.conf 的 DNS，并把它落到 `<rootfs>/etc/resolv.conf`。
     * 抛异常由 [sync] 统一兜住（写入失败 = 同步失败）。
     */
    private fun resolveServers(context: Context, rootfsDir: String): Resolved {
        if (rootfsDir.isBlank()) error("rootfsDir 为空")
        val root = File(rootfsDir)
        if (!root.isDirectory) error("rootfs 目录不存在：$rootfsDir")

        val system = systemDns(context)
        // 「一个都没取到 → 用 [FALLBACK]」这条判断只在这一个地方做：放在 [systemDns] 里的话，
        // 回退就等于"把兜底值伪装成系统配置"，调用方再也分不出"系统给的就是 8.8.8.8"与
        // "系统一个都没给、我们兜的底"—— 而这正是下面日志要回答的问题。
        val usedFallback = system.isEmpty()
        val servers = if (usedFallback) FALLBACK else system

        val target = resolvConfOf(rootfsDir)
        val content = buildString {
            servers.forEach { append("nameserver ").append(it).append('\n') }
            // 解析超时/重试：弱网下默认 5s 太短，容易出现"偶发 EAI_AGAIN"
            append("options timeout:2 attempts:3\n")
        }
        // ⚠️ 打的只是上面那份 `servers` 列表（见 [sync] 里那句），**整个文件内容不进日志**：
        //    除了那几行 nameserver 就只有固定的 options 行，多打一遍只是噪音。
        target.parentFile?.mkdirs()
        target.writeText(content)
        return Resolved(servers, usedFallback)
    }

    /**
     * 当前系统的 DNS 服务器列表。
     *
     * **空列表 = 取不到**（拿不到 [ConnectivityManager]、没有活动网络、或系统给的全是
     * 在 proot 内不可用的 IPv6 link-local）。回退到公共 DNS 的决定**不在这里做** ——
     * 只有调用方知道"这批值到底是系统给的，还是我们兜的底"，而这份区别要进日志（见 [sync]）。
     * 本函数在两者之间**只认"空"这一种信号**，绝不把自己的兜底伪装成系统配置。
     */
    fun systemDns(context: Context): List<String> {
        val out = LinkedHashSet<String>()
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching
            val network = cm.activeNetwork ?: return@runCatching
            cm.getLinkProperties(network)?.dnsServers?.forEach { addr ->
                val ip = addr.hostAddress?.substringBefore('%')?.trim().orEmpty()
                if (ip.isEmpty()) return@forEach
                // link-local IPv6 在 guest 里没有可用路由，写进去只会拖慢解析
                if (ip.startsWith("fe80", ignoreCase = true)) return@forEach
                out += ip
            }
        }
        return out.toList()
    }
}
