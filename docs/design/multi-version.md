# 设计：多版本/多实例管理 + npm 下载 UI（在 FCL 里改代码）

目标：在 FCL 代码库里加一套"dsh 实例/版本"的管理与下载，复用 FCL 的成熟范式
（StateFlow 单例仓库、Gson 序列化、HttpRequest、前台服务保活），但因为运行时模型不同
（FCL 是 in-process JVM，dsh 必须 proot），运行层不照搬、管理层照搬。

新增代码都在新包 `com.dsh.core`，不动 FCL 原有文件，降低合并冲突。

---

## 先厘清一个关键事实（读源码得到）

**FCL 并不用 proot。** 它把 JRE 打包进 assets，解压到私有目录，然后用 native 的
`jre_launcher.c` + `VMLauncher.launchJVM`（dlopen `libjvm.so`）在**同进程内**起 JVM。
所以 FCL 的"启动运行时"那层（`FCLauncher.launch`）对我们没有直接参考价值——dsh 是
独立的 Node 进程且原生依赖要命中 glibc/musl，**必须 proot**（任务1已验证）。

真正可借鉴、且我们照搬了的，是 FCL 的这几套上层设施：

| FCL 的东西 | 我们对应的实现 | 借鉴点 |
|---|---|---|
| `com.tungsten.fcl.setting.Profiles`（StateFlow 单例仓库） | `DshInstances` | 增删改统一入口 + StateFlow + 选中项自动校正 + 持久化 |
| `Profile.kt`（Gson 序列化的配置单元） | `DshInstance` + `DshInstances.Manifest` | 一个可运行环境作为持久化单元 |
| `FCLPath.java`（集中路径定义） | `DshPaths` | 私有目录布局、启动时 mkdirs |
| `com.mio.download.DownloadManager`（下载注册中心 + 前台服务保活） | `DshInstaller`（进度 StateFlow） | 订阅式进度、前台服务保活思路 |
| `ui/version` 的 List/Adapter/Item | `DshVersionListItem` + `DshDownloadViewModel` | 列表项模型 + 数据流 |
| `HttpRequest`（HttpURLConnection+Gson+异步重试） | `DshRegistry` 直接复用它 | 不引入 okhttp/retrofit 新依赖 |

---

## 新增文件（com.dsh.core）

- **DshPaths.kt** — 路径中心。布局：
  ```
  <filesDir>/dsh/
    rootfs/                共享 proot rootfs（所有实例复用）
    instances/<id>/        每实例独立
      node_modules/        该实例的 dsh（约 300MB）
      home/                DSH_HOME（会话/配置/profile）
      credentials.env      API key（权限 600）
    instances.json         实例清单
  <cacheDir>/dsh/tarballs/ 下载临时区
  ```

- **DshRegistry.kt** — npm registry 客户端 + 语义版本排序。
  - 用 abbreviated metadata（`Accept: application/vnd.npm.install-v1+json`），实测 112KB（full 是 142KB）。
  - scope 包名 URL 转义：`@deepseek-ai%2Fdsh`。
  - 标注每个版本命中的 dist-tag（latest/next/alpha）。
  - **注意**：abbreviated 里没有 `engines` 字段，所以 Node 版本要求不能靠它校验，交给 rootfs 内 setup 脚本。

- **DshInstance.kt** — 单个实例的数据模型（id/name/dshVersion/profile/port/model/state）。
  - `port` 字段：多实例并存时各用不同端口（`DshInstances.pickFreePort` 从 3080 起分配），避免 `dsh web` 端口冲突。
  - `profile` 字段：web（带 UI，走 WebView）/ headless（一次性任务）。

- **DshInstances.kt** — 实例仓库单例（照搬 Profiles）。
  - `instances` / `selectedId` 两个 StateFlow；`create/update/markState/delete/select`。
  - 删除会连带删磁盘目录（约 300MB，不可逆，UI 需先确认）。
  - Gson 持久化到 instances.json。

- **DshInstaller.kt** — 版本安装器。
  - 安装 = 在 proot 里跑任务1的 `setup-node-dsh.sh`（`npm install dsh@版本`），因为原生依赖要命中 rootfs libc。
  - 进度用 `Progress` sealed class + StateFlow（Log/Stage/Done/Failed）。
  - 抽象出 `ProotExecutor` 接口：真机由 App 的进程/native 层实现（对应 `proot-run.sh`），
    测试可注入假实现。**当前沙箱在 proot 内无法嵌套 proot，故此层是接口约定。**

- **DshVersionListItem.kt** — 下载列表行模型（版本/标签/体积/是否已装）。

- **DshDownloadViewModel.kt** — 下载页数据流：拉列表 → 合并已装版本 → 输出可渲染项；
  `installVersion` 新建实例并触发安装。含"显示预览版"开关（dsh 目前全是预发布版，默认开，否则列表空）。

---

## 实测证据

1. **registry 拉取**：真实请求 `@deepseek-ai/dsh` abbreviated metadata 成功，22 个版本，
   dist-tags = `{latest:0.1.5-rc.2, next:0.1.5-rc.2, alpha:0.1.6-alpha.2}`。样本存于
   `dsh-registry-sample.json`。
2. **排序逻辑**：用真实样本跑 `DshRegistry` 的解析+排序等价实现，输出从新到旧正确，
   `0.1.6-alpha.2` 排最前并标 `[alpha]`，`0.1.5-rc.2` 标 `[latest,next]`。
3. **语义版本比较**：11 条边界用例全过（稳定版>预发布、数字段按数值 rc.2<rc.10、rc>alpha、跨主干等）。

> 注：以上是逻辑层验证（用 Node 复刻 Kotlin 算法跑真实数据）。Kotlin/Android 编译需要
> Android SDK + Gradle，当前环境没有装，未做 Android 编译。代码按 FCL 现有风格与依赖
> （Gson、kotlinx.coroutines、HttpRequest）编写，接口与 FCL 既有类对齐。

---

## 尚未做 / 下一步

- **UI 层**：只写了 ViewModel 与 item 模型，具体的 FCLPage/RecyclerView Adapter/布局 xml 未写
  （需要 FCL 的主题组件与 databinding，建议照 `ui/version` 的 VersionListPage/Adapter 套一份）。
- **前台服务保活**：`dsh web` 长驻进程的保活服务未写，建议照 `DownloadService` 起一个
  `DshRuntimeService`，把运行中的实例进程挂在前台通知上。
- **ProotExecutor 真机实现**：把任务1的 proot-run.sh 逻辑用 ProcessBuilder 或 native 实现，注入进 DshInstaller。
- **端口/进程生命周期**：`dsh web` 的启动、token 捕获、WebView 对接（属于任务3）。
- **Android 编译验证**：需在带 Android SDK 的环境跑 `./gradlew :FCL:compileDebugKotlin`。
