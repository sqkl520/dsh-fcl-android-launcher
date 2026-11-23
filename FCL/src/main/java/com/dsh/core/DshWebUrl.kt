package com.dsh.core

/**
 * "这一次运行该让 WebView 加载哪个 URL" —— **唯一**的合成口径（U3 / R49 架构）。
 *
 * ## 为什么要有它
 * `"http://127.0.0.1:<port>/"` 这个合成在本仓库里曾经有**四份**：
 * core 的认领路径（`DshRuntime.adoptOrphan` 的 `adoptedUrl`）、core 的 `READY port=` 兜底
 * （`onProcessLine` 的第三级候选），以及 UI 的两处（`DshWebViewActivity.loadBestUrl` 与
 * `runnableUrlOrNull`）。四处的判据都是 `port > 0`，看起来一样 —— 但"四处相同"本身不是保证，
 * 而是四份会各自漂移的副本：改其中一处（例如加一条"端口 0 时不许拼 URL"之外的新规则），
 * 另外三处不会跟着变，症状是"某个入口会去加载一个别的入口不会加载的地址"。
 * 这类漂移在本项目已经发生过（U2 的 `isLoopback` 少一个 `[::1]`）。收敛成一处之后，
 * 这条规则只需要被读一次、测一次。
 *
 * ## 判据（逐条对应调用点的真实意图，**不是**随手写的默认值）
 * 1. **[url] 非 null ⇒ 原样返回它**。哪怕 `port` 还是 0：
 *    带 token 的 URL 是 dsh 自己打印的、已经过 [webUiUrlAllowed] 的门，它比任何本地合成都可信。
 *    （`port == 0` 与"有 url"同时出现的窗口极短，但守住"有真凭据就不合成"这条语义。）
 * 2. **[url] 为 null 且 `port > 0` ⇒ 合成 `http://127.0.0.1:<port>/`**。
 *    这是 cookie 模式：应用内 WebView 有持久 cookie，裸 URL 能凭 cookie 进界面；
 *    进不去会走 401 兜底提示（`DshWebViewActivity` 的门禁）。
 * 3. **[url] 为 null 且 `port <= 0` ⇒ null**。
 *    `port = 0` 的含义是"dsh 还没打印出真实端口"（脚本用系统分配端口时启动初期就是 0）。
 *    这时拼出 `http://127.0.0.1:0/` 只会得到一个"页面加载失败"，看起来像 dsh 坏了 ——
 *    调用点应当显示"启动中"而不是去加载一个已知无效的地址。
 *    （对 UI 的 `loadBestUrl`：**不要**用 `dsh_webview_stopped` 那类文案，实例此刻其实在跑。）
 *
 * ## 为什么参数是 `(url, port)` 而不是整个 `State.Running`
 * 纯 Kotlin 函数、零 Android 依赖 ⇒ JVM 单测可以直接钉住三条判据的全部组合，
 * 不需要构造 `DshRuntime.State`（它本身是纯数据类，但少一层依赖总归更好测）。
 * 调用点负责把 `State.Running` 的字段传进来 —— 一行，且字段名自解释。
 *
 * @param url `DshRuntime.State.Running.url`（带 token 的完整 URL，或 null）
 * @param port `DshRuntime.State.Running.port`（宿主让脚本绑定的端口；未知为 0）
 */
fun webUrlOrNull(url: String?, port: Int): String? =
    url ?: if (port > 0) "http://127.0.0.1:$port/" else null
