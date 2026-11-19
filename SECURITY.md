[English](SECURITY-EN.md) | 简体中文

# 安全策略

DSHarness-FCL-Launcher 是一个**在本机运行**的安卓启动器。它用 proot 在应用私有目录里跑一个 Debian
rootfs、一个 Node 进程和一个 dsh（DeepSeek Harness）服务，然后用 WebView 加载
`http://127.0.0.1:<port>/`。

它**不是**远程服务，**没有**我们自己运营的后端，**不收集**遥测，**不会自动上传**你的任何数据。
（唯一的，是你按下去的：崩溃页有一个从上游继承的「上传日志」按钮，见 [出站连接](#出站)。）
它做的事是：在你手机上起一个只有本机能连的 Web 服务，然后把界面给你看。

正因为它是"在你设备上跑别人的代码"（rootfs 里的 Node、你装的 dsh、你配的模型提供方），本文档
刻意写得具体：**它请求什么权限、连哪里、把凭据放在哪、以及哪些东西它防不住**。

> ⚠️ **先说最重要的一条**：**rootfs 不是安全沙箱。** proot 只是系统调用转发，不是容器、不是虚拟化，
> 也没有内核级隔离。跑在里面的东西拥有本 App 的全部文件权限。见下方「本启动器防不住什么」。

---

## 支持范围

安全修复只发给**最新发布的包**。

| 版本 | 是否支持 | 说明 |
|------|---------|------|
| `0.1.6-SNAPSHOT-fix22`（**公开发布的最新包**） | ✅ 支持 | 安全修复落在这里 |
| 更早的 `-fixN` | ❌ 不支持 | 请升到最新发布包 |
| `0.1.6-SNAPSHOT` 正式包 | ⚠️ 仅接受严重问题的修复 | 尚未转 `1.0.0` |

> ⚠️ **关于 fix23~fix26 的说明**：代码仓库里已经到 **fix26**（code 132），但**这四轮尚未发布到
> Releases 页、也没有 tag**。它们目前只在本机与 `output/` 目录里，公开下不到。
> 所以**本文档所描述的行为以 fix22 为公开发布基线**；fix23~26 一旦发布，支持范围随之上移。

项目处于 **1.0 之前**，接口与目录布局仍会变。不向旧包回移补丁——这是一个单人维护项目，
维护多条分支等于哪条都维护不好。若你在不受支持的版本上，修复方式就是升级。

---

## 报告漏洞

**不要为安全问题开公开 issue。** 公开 issue 所有人可见，包括会在修复前利用它的人。

**首选 —— GitHub Security Advisory（私密报告）：**

> <https://github.com/sqkl520/dsh-fcl-android-launcher/security/advisories/new>

这条通道给我们一个私密线程、一个共同开发修复的地方，以及发布带署名的安全公告的方式。

**备选 —— 邮件：** `sqkl520soap@gmail.com`，主题写 `[DSH-FCL Security]`。

无需加密，本项目没有 PGP 密钥。请**不要**在报告中粘贴生产凭据、你的 API Key、模型提供方地址、
访问 token 或个人数据；请先打码。

### 请提供

- 版本号后缀（**关于页**里那串，如 `0.1.6-SNAPSHOT-fix22`）—— 没有它无法判断你装的是不是当前包
- 机型 + Android 版本 + **WebView 版本**（`chrome://version` 或关于页会显示）—— proot、WebView、
  存储路径的行为跟 ROM 强相关
- 问题是什么，以及攻击者能因此得到什么 —— **影响比机制更重要**
- 复现步骤，或 PoC。一份最小复现比一篇长文更有用
- 相关日志片段，**已脱敏**（访问 token、API Key、模型提供方地址、设备路径）

### 响应时间

| 阶段 | 目标 |
|------|------|
| 确认收到 | **7 天内** |
| 初步判定 —— 接受 / 需要更多信息 / 超出范围 | **14 天内** |
| 修复，或给出你可自行实施的缓解方案 | **90 天内**；高危问题更快 |
| 发布安全公告并署名 | 修复版本可用之后 |

这些是**一个人给出的目标**，不是合同 SLA。本项目免费、由志愿者维护，没有漏洞赏金。若某份报告
需要的专业知识我不具备，我会直接说明，而不是让它一直挂着。除非你要求匿名，否则会在安全公告与
CHANGELOG 中署名致谢。

### 范围 —— 什么算安全漏洞

**在范围内：**

- **访问 token 或 API Key 泄漏**到日志、剪贴板、导出文件、崩溃报告或截图里
- **让第三方 App 读到本 App 私有目录**（`<filesDir>/dsh/` 下的 `home/.credentials.yaml`、
  `dsh-token-url.txt`、pid 文件里的 token URL）
- **让 WebView 加载非回环地址**，或让进程输出里的伪造行把 WebView 导向攻击者控制的主机/端口
  （见下方「URL 采信门」）
- **任意文件读写 / 路径穿越**：实例名、启动目录、日志文件名等用户可控输入逃出预期目录
- **导出组件被第三方调起**并造成上述任一后果
- **网络策略绕过**：让 App 的 Java/WebView 网络层向站外发**明文** HTTP
- **构建链问题**：CI 工作流、发布产物、被替换的依赖
- **proot / PROOT_LOADER 层的越权**：从 rootfs 内写到 App 私有目录之外的位置

**不在范围内** —— 这些是预期行为或已声明的设计取舍，不是漏洞：

- **本机其它 App 能连到 `127.0.0.1:<port>`。** 安卓上所有 App 共享同一个网络命名空间，
  回环端口对本机所有进程可见。这是平台事实，**不是本启动器的缺陷**。保护手段是访问 token，
  见下方「访问 token」。
- **rootfs 里的代码能做 App 能做的一切。** 见「本启动器防不住什么」。你装进 rootfs 的东西
  本来就是你自己选的。
- **你配置的模型提供方能收到你的对话内容。** 那是它的用途。发什么给它由你选的 dsh 与 provider 决定，
  不由启动器决定。
- **需要 root 或解锁 bootloader 的攻击。** 那已经超出应用层权限边界。
- **上游的漏洞**：dsh 自身、Node、Debian、WebView、FCL。请报告给上游；若上游缺陷**在本项目里**
  被放大成额外风险，那部分算我们的。
- **不在支持列表内的版本中的任何问题。**
- **"应用在旧 WebView 上白屏/崩溃"。** 这是已知的平台版本限制，有明确的门禁提示与绕行方案，
  不是安全问题。

### 已知非问题

以下行为常被当成 bug 或安全问题上报，但都是有意为之：

1. **"用浏览器打开时地址栏里有 `?token=...`。"** 有意如此。dsh 的鉴权就是这个 token，没有它
   打不开界面。这也是为什么提供「复制链接」——你要自己决定把它交给哪个浏览器。
2. **"App 申请了 `INTERNET` 权限。"** 拉 npm 版本列表、在 rootfs 里 `npm install`、以及 dsh 自己
   访问你配的模型 API，都需要它。App 不向任何自有服务器上报。
3. **"`android:allowBackup` 是 `true`。"** Manifest 里确实是 `true`，但 `<filesDir>/dsh/`
   —— 也就是**全部**运行时数据（rootfs、实例、凭据、日志）—— 在 `backup_rules.xml` 与
   `data_extraction_rules.xml` 里被**整目录排除**在云备份与设备迁移之外。见下方「备份」。
4. **"App 允许明文 HTTP。"** 默认**禁止**。只有 `127.0.0.1` 与 `localhost` 例外，因为 WebView 要
   加载本机 dsh。见 `network_security_config.xml`。
5. **"对话里贴的站外链接被交给系统浏览器了。"** 有意如此。WebView 只承载回环地址；其余一律
   外跳，避免在带凭据的 WebView 上下文里渲染任意站点。
6. **"切到外部浏览器后，日志里出现了端口和有无凭据，但没有 token。"** 有意如此。外跳留痕只记
   `scheme://host:port` 与"是否带凭据"，**绝不记 token 值**。

---

## 权限 —— 申请了什么，为什么

| 权限 | 为什么需要 | 能不给吗 |
|------|-----------|---------|
| `INTERNET` | 拉 dsh 版本列表（npm registry）、在 rootfs 里 `npm install`、dsh 访问你配的模型 API | 不能 |
| `ACCESS_NETWORK_STATE` | 判断有没有网，避免离线时把"下载失败"报成别的原因 | 不能 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` + `FOREGROUND_SERVICE_SPECIAL_USE` | dsh 是长驻进程，agent 跑任务时不能被系统随手杀掉 | 不能 |
| `POST_NOTIFICATIONS` | 前台服务必须显示常驻通知（Android 13+ 要用户授权） | 可拒绝，只影响通知可见性 |
| `WAKE_LOCK` | 跑任务时保持 CPU 清醒，避免长任务跑到一半被挂起 | 不能 |

**没有申请**：存储读写（`MANAGE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`）、
安装其他应用（`REQUEST_INSTALL_PACKAGES`）、相机、麦克风、定位、通讯录、短信、通话记录、
查询已安装应用列表。全部数据都在应用私有目录里，不需要任何存储权限。

**导出组件**：Manifest 里**面向用户的** `android:exported="true"` 组件只有 `SplashActivity`（启动器入口）。
运行时服务 `DshRuntimeService` 与自有的 `ContentProvider` 都是 `exported="false"`，第三方 App
无法直接调起。`FileProvider` 走 `grantUriPermissions="true"`，权限按 URI 单独授予。

如实补充两点打包结果里的事实：

- 打包时会并入 androidX 的 `androidx.profileinstaller.ProfileInstallReceiver`，它也是
  `exported="true"`；但它受系统级 `android.permission.DUMP`（signature|privileged）保护，
  **第三方 App 实际发不进来**。
- 另有一个 androidX 的 `InitializationProvider`，`exported="false"`。

### 备份

`android:allowBackup` 在 Manifest 里是 `true`，但**全部运行时数据都被排除**：

| 规则文件 | 生效范围 | 排除内容 |
|---------|---------|---------|
| `backup_rules.xml` | Android 11 及以下 | `domain="file" path="dsh/"` |
| `data_extraction_rules.xml` | Android 12+（云备份 + 设备迁移） | 同上 |

`domain="file"` 即 `<filesDir>`，而运行时根目录正是 `$filesDir/dsh`（`DshPaths.ROOT_DIR`）——
所以 rootfs、实例目录、`.credentials.yaml`、pid/侧车 token 文件、日志**全在排除范围内**。

为什么整目录排除：备份配额约 25 MB，而该目录 1 GB 起；更关键的是换机后 `version` 文件要跟本机
assets 比对，恢复也仍要重新解包，实例清单可由界面重建。

`<filesDir>` 根下还有两个**不在**排除名单里的文件：`startup-failures.log`（启动失败栈回溯，由
`FCLApp` 写）与 `app_log/`（FCL 自身日志）。它们**不含凭据**（FCL 日志路径本身也做了 token 替换），
因此没有列入排除——这一点写在这里，而不是留给读者自己推断。

---

## 网络 —— 连哪里、能不能关

### 出站

| # | 连接 | 目的地 | 由谁发起 | 能不能关 |
|---|------|--------|---------|---------|
| 1 | dsh 版本列表 | `https://registry.npmjs.org` | 启动器（`DshRegistry`） | 不用版本页就不发生 |
| 2 | `npm install @deepseek-ai/dsh@<版本>` | `https://registry.npmjs.org` | rootfs 里的 npm（原生进程，走 socket） | 不装新版本就不发生 |
| 3 | 模型 API 调用 | **你自己配的地址** | dsh（rootfs 里的 Node） | 由 dsh 的配置决定 |
| 4 | 你主动外跳的站外链接 | 你点的那个站 | 系统浏览器（**不是本 App**） | 不点就不发生 |
| 5 | **崩溃日志上传** | `https://api.logshare.cn/1/log`（中文环境）<br>`https://api.mclo.gs/1/log`（其它） | 启动器（继承自 FCL 的 `HttpRequest`） | **只有你按下崩溃页的「上传日志」才发生** |

**没有**自有后端、没有遥测、没有**自有的**崩溃上报服务器、没有更新检查器。

⚠️ **但崩溃页保留了一个第三方上传按钮**：这是从上游 FCL 继承来的。只有你主动按下「上传日志」，
崩溃文本才会被 POST 到第三方日志分享服务（中文环境 `api.logshare.cn`，其它 `api.mclo.gs`）。
**它不会自动触发**。另外，这条路径的文本**不经过** [日志脱敏](#访问-token)——见下方「已知的残余风险」。

验证方式（只查本 App 的 Java 网络层）：

```bash
grep -rn "HttpURLConnection\|openConnection\|new URL(\|new Socket" FCL/src/main/java/com/dsh/
```

命中会连**注释**一起命中，要逐条看是不是真的调用。真实的网络调用有三处：

| 位置 | 用途 | 目标 |
|------|------|------|
| `DshRegistry.kt` | 拉 dsh 版本列表 | `https://registry.npmjs.org` |
| `DshRuntime.kt`（`Probe.isPortOpen` / `probeTokenUrl`） | 探活**本机** dsh 是否还活着、token 是否仍被受理 | `127.0.0.1` |
| `LogSharingUtils.kt`（在 `com/tungsten/fcllibrary`） | 崩溃页「上传日志」按钮 | 见上表第 5 条 |

> `DshDownloadViewModel.kt` 里也出现了 `HttpURLConnection`，但那**只在注释里** —— 它是
> `DshRegistry.fetchVersionsCached()` 的调用方，本身不发请求。

> `com/tungsten/fclcore/` 里还有一套从 FCL 继承下来的通用 HTTP 工具（`HttpRequest` /
> `NetworkUtils`）——那是**请求层**，本身不决定连哪里，实际目标由调用方给出。它在本项目里
> **恰好有两个用户**：`DshRegistry`（npm 版本列表）与 `LogSharingUtils.uploadLog`（崩溃页上传）。

rootfs 内的 `npm` / dsh 是原生进程，直接走 socket，不经过这层代码——它们连哪里由 npm 与
dsh 的配置决定，不由启动器决定。

### 入站

**dsh 只绑回环地址。** 启动时显式传 `HOST=127.0.0.1`（`DshRuntime.kt` 的 `argvEnv`），
脚本侧同样默认 `127.0.0.1` 并注明"别绑 `0.0.0.0`"（`start-dsh.sh`）。

⚠️ **但这不等于"局域网上别人连不上"就万事大吉**：安卓上所有 App 共享同一个网络命名空间，
**本机任何 App 都能连 `127.0.0.1:<port>`**。真正的保护是访问 token，见下节。

一个值得一提的细节：dsh 的会话 cookie 名是 `dsh-auth-` + `sha256(authority)` 的 base64url，
而 **authority 含端口**（`127.0.0.1:38501`）。因此换端口会让 cookie 名整体变掉 —— 这既是
"换端口后立刻 401"这类问题的根源，也意味着**凭据不能跨端口复用**，攻击者换一个端口拿不到上一条
会话的 cookie。

### 明文 HTTP 策略

`network_security_config.xml` 里默认 **`cleartextTrafficPermitted="false"`**，只对
`127.0.0.1` 与 `localhost` 开例外：

```xml
<base-config cleartextTrafficPermitted="false" />
<domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="false">127.0.0.1</domain>
    <domain includeSubdomains="false">localhost</domain>
</domain-config>
```

含义：即使某处代码被误导向站外发明文请求，也会**直接失败**而不是静默把 token/cookie 发出去。
该策略只作用于 App 进程内的 Java/WebView 网络栈；rootfs 里的 apt/npm 是原生进程，不受其约束
（否则装不了依赖）。

---

## 凭据 —— 放在哪，怎么保护

### 访问 token

dsh 启动时自己打印形如 `dsh web: http://127.0.0.1:3080/?token=XXXX` 的地址。这个 token 是
**进程级、base64url、43 字符**，是 dsh 唯一的内建鉴权。

它必然出现在几个地方，这是设计使然：**内存、WebView 的 URL、以及剪贴板**。
剪贴板这一项要说得更准确些 —— 有两种情形会写入：

| 情形 | 是否额外确认 |
|------|-------------|
| 你点「复制链接」 | 是，你明确知道自己在复制 |
| 你选择「在浏览器中打开」（含门禁框的「用外部浏览器打开」、实例详情页的浏览器按钮、崩溃提示的外跳） | **否** —— 外跳路径会**先复制再跳转**，作为浏览器吞掉查询串时的兜底（BL-225） |

所以：**只要用过任何一次「在浏览器中打开」，剪贴板里就躺着一条带 token 的地址**，不一定是你主动复制的。

我们做了三件事限制它的扩散面：

1. **日志脱敏。** 所有进日志总线的行（含落盘文件）都会把 `token=XXXX` 换成 `token=***`，
   已注册的 token 值再做一次全文替换兜底。
   注册一条 secret 要通过**五道门**：长度下限（≥8，防单字符打码整份日志）、上限（≤256）、
   token 形状（只接受 base64url 字符集）、不遮蔽协约词（否则日志归因会静默失效）、
   容量（≤16 条）。**容量满时拒绝新增而不是淘汰旧条目** —— 淘汰会让一条已经漏进日志的真 token
   重新变回明文。
2. **启动日志有界轮转。** `dsh-start-*.log` 是**含明文 token** 的启动日志。每次启动前会做一次
   **有界的**轮转：按修改时间保留最新 **6** 份，其余只在超过 **7 天**未修改后才删除。
   脚本只在**正常退出**路径自删该文件，App 被强杀后会残留 ——
   所以**在一次会话内可能同时存在多份含 token 的日志**。这是刻意保留恢复源（认领孤儿进程要用它）
   换来的代价。
   ⚠️ 更直接的一面：这些日志落在 `cacheDir` 下并被 proot `--bind` 成 rootfs 的 `/tmp`，
   **rootfs 内任何进程都能读到它们**（也就读得到明文 token）。
3. **外跳留痕不记 token。** 交给系统浏览器时，日志只写 `scheme://host:port` 与"是否带凭据"。

⚠️ **明说**：带 token 的 URL 会以**明文**写进 pid 文件与侧车文件 `dsh-token-url.txt`
（都在应用私有目录）。这样做是为了让"App 被杀后还能认领孤儿进程并拿回 token"——token 是进程级的，
dsh 自己不落盘，不存就只能重启。**它们没有加密**，保护完全依赖 Android 的应用私有目录隔离。

### API Key（模型提供方密钥）

**归 dsh 自己管，启动器不碰。** Key 存在实例的 `home/.credentials.yaml` 里，由 dsh 读写。
启动器不会把它写进日志、不会注入环境变量、也不经过自己的代码。

历史遗留：早期版本启动器自己管 Key，留下过 `credentials.env`（**明文**）与 `credentials.enc`
（启动器自己的 Keystore 密文）。这两份文件在升级后会被**主动清理**——否则一个明文 API Key 会
一直躺在实例目录里。

⚠️ 这次清理的**确切范围**要说清楚，别让人以为"清过了就没了"：

- 只覆盖 **`instances.json` 清单内**的实例目录。不在清单里的孤儿目录（比如历史上的残留）
  **不会被清理**，那个明文 Key 会一直躺着。
- 删除**失败时完全静默**（没有 `onFailure` 日志）。失败后下次启动会再试，但你可能永远不会知道
  它没删掉。
- 想彻底确认，请手动检查各实例目录下是否还存在这两个文件。

⚠️ **一个真实的暴露面（已写进设置项说明）**：dsh 的文件 API **允许读工作区外的文件**
（它的设计如此——agent 要能读任意文件）。所以**如果你把 `home/` 选成工作区，agent 就能读到
`.credentials.yaml`**。启动器的选择器默认落点是实例目录（`workspace/` 与 `home/` 并列），
并在设置项说明里写清了这一点，但**无法从技术上禁止**——那是 dsh 的语义。

### 剪贴板

「复制链接」把**带 token 的完整 URL** 放进系统剪贴板。这是有意的功能（BL-225：Edge 等浏览器
会剥掉第三方传入的查询串，复制粘贴是兜底路径）。

⚠️ 但**这不只发生在「复制链接」时**：任何一次「在浏览器中打开」也会先复制再跳转（见上表）。
所以别以为"我没点过复制，剪贴板里就没有凭据"。

⚠️ 剪贴板是**系统级共享**的：Android 10 之前任何有焦点的 App 都能读；Android 10+ 只有当前
焦点 App 能读，但用户粘贴到哪由用户决定。**这个 token 的暴露面由你控制**——粘贴前想一下
你在把它交给谁。

---

## 本启动器防不住什么

**这一节是本文件最重要的部分。** 下面每一条都是真实存在的限制，不是免责声明。

1. **rootfs 不是安全沙箱。** proot 是**系统调用转发**，不是容器、不是虚拟化、没有内核隔离。
   rootfs 里跑的任何代码都拥有本 App 的全部文件权限，能读写 `<filesDir>/dsh/` 下的一切
   —— 包括 `.credentials.yaml` 和 token 文件。你 `npm install` 的包、你装的 dsh 插件、
   你让 agent 跑的命令，都在这个权限级别上。
2. **proot 刻意绕过了安卓的 W^X 保护。** `targetSdk 34` 下应用私有目录默认不可执行，本项目
   用 `PROOT_LOADER` 把这一限制绕过去，才能在 rootfs 里 exec。这是**对一项平台安全边界的
   主动弱化**，换来的是"不降 targetSdk 也能跑 Linux"。取舍记录见 `docs/design/wx-exec-proot-loader.md`。
3. **`<filesDir>/dsh` 以可写方式挂进 rootfs。** proot 用 `--bind=` 把宿主目录挂到 guest 的
   `/opt/dsh`，rootfs 内**可写**。这是让 dsh 能装依赖的必要条件，也意味着 rootfs 内的进程
   能改写宿主的运行时文件。
4. **本机任何 App 都能连 `127.0.0.1:<port>`。** 见上。token 是唯一屏障；token 一旦泄漏
   （日志、剪贴板、截图），同机任何 App 都能接管那个界面。
5. **WebView 开了 JavaScript。** 必须开——dsh 的前端是 JS 应用。缓解手段：禁文件/内容访问、
   禁混合内容、禁第三方 cookie、禁多窗口、禁 JS 自动开窗、只允许回环地址留在 WebView 内、
   WebView 远程调试**只在调试包**开启。
   ⚠️ **但这不等于"该关的都关了"**：以下几项**没有显式设置，全靠 WebView 默认值** ——
   `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs`（API ≤29 默认 `true`，
   30+ 默认 `false`，本机 minSdk 是 **26**）、`savePassword`（默认 `true`）、
   `saveFormData`（默认 `true`）、`databaseEnabled`（默认 `false`）。
   `allowFileAccess=false` 已把它们能造成的危害压得很低，但这是"没关"而不是"关了"，
   本项目**不声称**这部分已加固。
   一个好消息：`addJavascriptInterface` **从未被调用**，页面无法直接调用 Java 对象。
6. **我们不校验 rootfs 内 npm 包与 dsh 插件的完整性**，只有 npm 自身的校验。供应链安全由
   上游与你的选择决定。
7. **不防设备已被攻破。** 有 root、解锁 bootloader、装了恶意 Xposed 模块的设备上，应用私有
   目录隔离不成立，本文档的多数保证都不再有效。
8. **没有 PGP、没有漏洞赏金、没有专职安全响应。** 响应时间是单人目标。

---

### 已知的残余风险（我们知情但未修）

这些不是"没想到"，是**权衡后暂时保留**的。列在这里，是为了让你能自己判断风险，而不是等出事才发现。

1. **崩溃页上传的文本不经过日志脱敏。** 「上传日志」把崩溃面板全文原样 POST 出去
   （`LogSharingUtils.uploadLog`），没有复用 `DshLogBus.sanitize`。当前崩溃文本是栈回溯，
   一般不含 token；但**一旦某天的异常消息里带上了带 token 的 URL，这条路就是绕开脱敏的直出通道**。
2. **页面内的回环链接不校验端口。** `shouldOverrideUrlLoading` 用 `isLoopback()` 判断是否留在
   WebView 内，而它**只比对 host、不含端口**。于是页面里的 `http://127.0.0.1:<任意端口>/` 会被
   留在 WebView 内导航。这与 `webUiUrlAllowed(url, expectedPort)` 的口径不同 —— 后者是严格的。
3. **`DEEPSEEK_*` 环境变量前缀被无条件放行。** `ProotCommand.isAllowedEnvKey` 的最后一条是
   `key.startsWith("DEEPSEEK_")`。当前**没有任何调用方**使用它，但它是一条现成的
   "把 key 塞进子进程 argv（会出现在 `/proc/<pid>/cmdline`）"的通道。保留它是因为上游脚本可能用到
   这个前缀；如果你 fork 这个项目，建议改成显式白名单。
4. **`::1` 能过主机判据，但过不了明文策略。** 主机判据接受 `::1`，而
   `network_security_config.xml` 的例外只列了 `127.0.0.1` 与 `localhost`。结果是
   `http://[::1]:port/` 会被网络安全策略直接拦死 —— 这是**功能缺口**，不是安全洞（失败方向是拒绝）。

---

## 已知的加固措施（可自行验证）

| 措施 | 位置 | 说明 |
|------|------|------|
| URL 采信门 | `DshRuntime.kt` `webUiUrlAllowed` | 见下方「为什么需要这道门」 |
| 日志脱敏 | `DshLogBus.kt` | 正则替换 + 已注册 secret 全文兜底；注册有上下限，超限拒绝而非淘汰 |
| 启动日志轮转 | `DshRuntime.kt` | 含明文 token 的 `dsh-start-*.log` 每次启动前清理 |
| 明文 HTTP 默认禁止 | `network_security_config.xml` | 只对回环开例外 |
| 备份排除运行时目录 | `backup_rules.xml` / `data_extraction_rules.xml` | `<filesDir>/dsh/` 整目录排除 |
| WebView 硬化 | `DshWebViewActivity.setupWebView` | 见上方第 5 条 |
| WebView 调试仅调试包 | 同上 | `BuildConfig.DEBUG` 判断，出货包关闭 |
| 实例名/路径清洗 | `DshPaths.kt`、`DshRuntime.resolveHomeRootfs` | 拒绝 `..`/`.`，只接受 guest 内绝对路径，非法值回落默认 |
| 退出归因与失败可见 | `DshRuntime.kt` | 停止失败不再谎报"已停止"；安装错误可见 |

---

### 为什么需要这道门（真实攻击链）

这条不是假想的威胁。`dsh-start-*.log` 落在**共享的** `TMP_DIR`，而 `ProotCommand` 把它
`--bind` 成 rootfs 里的 `/tmp` —— **rootfs 内任何代码都能往那里追加一行**。而宿主解析这个文件
时，正则只要求"像 URL"，不校验主机。

于是完整的攻击链是：

1. rootfs 里跑的任何东西（一个 npm 包、一个 dsh 插件、你让 agent 跑的命令）往
   `/tmp/dsh-start-*.log` 追加 `dsh web: https://evil.example/?token=x`；
2. 宿主读到它，探活时向该主机发 GET，对方回 3xx 即判定"live"，这个 URL 被写进侧车/pid 文件；
3. 它成为 `state.url`，最终进 `webView.loadUrl` —— **界面被导向攻击者的站点**，
   而那个 WebView 上下文里带着你的凭据。

`https` 变体能真正渲染（`network_security_config.xml` 只拦明文 http），所以明文策略**不能**
替代这道门。三重校验（scheme 白名单 + 回环 host + 端口）缺一不可 —— 红队实测：只判 host 时，
`http://127.0.0.1:<攻击者端口>/?token=x` 仍会被采信。

---

## 加固建议（给用户）

- **给 rootfs 里装什么，等同于给这个 App 什么权限。** 别装来历不明的 dsh 插件。
- **别把 `home/` 选成工作区。** 那样 agent 就能读到你的 API Key。设置项说明里也写了这条。
- **复制链接后粘贴到哪要自己判断。** 剪贴板里是带 token 的完整访问地址。
- **分享日志前先自查。** 日志已做 token 脱敏，但**实例路径、模型提供方地址、你让 agent 执行的
  命令内容**不在脱敏范围内。
- **用最新包。** 安全修复只落最新 `-fixN`。
- **设备本身要干净。** 有 root 的设备上，本文档的隔离假设不成立。

---

> 本文档描述的是**代码基线 `0.1.6-SNAPSHOT-fix26`**（code 132）时的实现；对外公开发布的最新包是
> `fix22`。安全相关的断言都以代码为准，若你发现文档与代码不一致，**以代码为准**，并欢迎提 issue 指出。
