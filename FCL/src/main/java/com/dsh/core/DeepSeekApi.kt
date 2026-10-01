package com.dsh.core

import com.tungsten.fclcore.util.gson.JsonUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek 云端 API 的最小客户端：只做一件事——**校验 API key 是否可用**。
 *
 * 为什么需要它：原来的使用流程里，key 配错了只有在 WebView 里发消息失败时才会发现，
 * 用户拿不到任何具体原因（401？余额？模型名错？）。设置页提供"测试连接"，
 * 直接把结论（可用/密钥无效/网络不通）显示出来。
 *
 * 实现上刻意**没用** FCL 的 [com.tungsten.fclcore.util.io.HttpRequest]：它没有设置
 * connect/read 超时的入口（默认走 JDK 的无限等待），一旦网络半死，测试按钮会一直转。
 * 这里用 HttpURLConnection 显式设定 10s/15s 超时。
 */
object DeepSeekApi {

    const val BASE_URL = "https://api.deepseek.com"

    sealed class VerifyResult {
        /** 密钥有效；[models] 是服务端返回的可用模型 id */
        data class Ok(val models: List<String>) : VerifyResult()

        /** 密钥无效/无权限（401/403） */
        data class Invalid(val message: String) : VerifyResult()

        /** 其它失败（网络、服务端 5xx、响应格式），不代表 key 一定错 */
        data class Unknown(val message: String) : VerifyResult()
    }

    private data class ModelList(val data: List<ModelEntry>? = null)
    private data class ModelEntry(val id: String? = null)

    /**
     * 用 `GET /models` 校验密钥（OpenAI 兼容接口）。
     * @param apiKey 明文 key；只在本次请求的 header 里使用，不落盘、不写日志
     */
    suspend fun verifyKey(apiKey: String): VerifyResult = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isEmpty()) return@withContext VerifyResult.Invalid("密钥为空")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$BASE_URL/models").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            when {
                code in 200..299 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val models = runCatching {
                        JsonUtils.fromNonNullJson(body, ModelList::class.java)
                            .data?.mapNotNull { it.id }
                            ?: emptyList()
                    }.getOrDefault(emptyList())
                    VerifyResult.Ok(models)
                }
                code == 401 || code == 403 ->
                    VerifyResult.Invalid("服务端返回 $code，密钥无效或无权限")
                else -> {
                    val snippet = runCatching {
                        (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()?.take(200).orEmpty()
                    VerifyResult.Unknown("服务端返回 $code${if (snippet.isBlank()) "" else "：$snippet"}")
                }
            }
        } catch (e: Exception) {
            VerifyResult.Unknown("网络错误：${e.message ?: e.toString()}")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
