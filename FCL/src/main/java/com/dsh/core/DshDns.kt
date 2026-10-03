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
     */
    @JvmStatic
    fun sync(context: Context, rootfsDir: String): Boolean = runCatching {
        if (rootfsDir.isBlank()) return@runCatching false
        val root = File(rootfsDir)
        if (!root.isDirectory) return@runCatching false

        val servers = systemDns(context)
        val target = resolvConfOf(rootfsDir)
        val content = buildString {
            servers.forEach { append("nameserver ").append(it).append('\n') }
            // 解析超时/重试：弱网下默认 5s 太短，容易出现"偶发 EAI_AGAIN"
            append("options timeout:2 attempts:3\n")
        }
        target.parentFile?.mkdirs()
        target.writeText(content)
        true
    }.getOrDefault(false)

    /**
     * 当前系统的 DNS 服务器列表。
     * 优先 [ConnectivityManager]（正规途径）；过滤掉无法在 proot 内使用的 IPv6 link-local；
     * 一个都取不到时回退公共 DNS。
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
        if (out.isEmpty()) out += FALLBACK
        return out.toList()
    }
}
