# 任务清单（待办）

> **本文档会公开发布** —— 只写"要做什么/验什么"；本机路径、内部工作流这类细节不写。

> 放**已明确要做、但未做**的事项。做完从本清单移除。
>
> **本文档的性质**：§「已完成」是**历史索引**（保留，便于回溯）；P1/P2/P3 与「待真机验证」是
> **当前仍有效的待办/待验清单**。
>
> **fix7 之后的逐版追踪不在本文**：从 fix6/fix7 起，每一版的改动与修复说明都记在
> `RELEASES.md`（逐版、面向用户）。本文只保留"至今仍未完成/仍未验证"的部分，
> 以及每轮的**必验清单**（T6）。

## 版本进度（截至 fix22）

| 项 | 值 |
|---|---|
| 对外版本 | `0.1.6-SNAPSHOT`（`gradle.properties` 的 `dshVersion`） |
| 版本号 / fix 标签 | `dshVersionCode=128` · `dshFixLabel=fix22` |
| 当前验证包 | `0.1.6-SNAPSHOT-fix22`（已发布到 Releases） |
| 质量基线 | 单测 **513/513**；脚本自检 **28/28** |
| 真机端到端 | 0.1.6（批 A）已点亮；此后逐版真机反馈驱动修复（fix1~fix22） |

> 版本号唯一真源是 `gradle.properties`；`RELEASES.md` / `README.md` 的"当前版本"必须与它一致。

## 已完成（保留索引，便于回溯）

| 原编号 | 内容 | 落地 |
|---|---|---|
| G5 | 页面切换动画（淡入 + 上滑 30dp / 250ms，仅位置真变时播） | commit `97151a2` |
| G2 | 关于页（说明行 + 链接组，照 FCL `AboutPage`） | commit `97151a2` |
| G1 | 页内多页容器 + 标签栏（`DshMultiPageUI`；设置页 = 启动器设置 \| 关于） | commit `73f3d99` |
| G3 | 主题自定义（取色对话框 + 数值滑条 + 多图标行） | commit `136d962` |
| G4 | 文件/图片选择（系统 `ACTION_OPEN_DOCUMENT`） | commit `467c5c1` |
| — | 真机二测问题修复（DNS / 安装防重复 / FCL 规范控件 / 状态误判） | commit `ca95ab0` |
| — | 首启前置页 + 首页任务区 | commit `d285671`+`9cb4855` |
| — | **动画 16/16 全对齐**（含 SwipeMenuLayout / WaveProgressView / AnimUtil） | commit `d4b1a92`…`4d79564` |
| — | APK 产物整理 + 0.1.1 打包 + 0.1.0 归档 | commit `26d3a8a`/`a262a7e` |
| **T1** | 实例详情页改 FCL 行式（`DshInstanceSettingAdapter` + 值行/按钮行） | commit `839e0fa` |
| **T4** | 右面板按钮照 FCL 形态（图标+文字 clickable 容器；原 T4 前提「用 `bg_right_menu_button`」经核对是 FCL 未使用资源，已改按真实写法） | commit `839e0fa` |
| **T2** | 12 种语言 + 自定义启动器名（`LauncherUtil` + 编辑行） | commit `8b49ea9` |
| **T9** | `.github/workflows` 适配 —— **不需要做了**：FCL 原版 CI 在搬到电脑时已整体删除（`92ba6df`，远端实测 `workflows total_count: 0`） | commit `92ba6df` |
| **批 A** | 真机端到端第一次跑通（装→启→对话→停干净→再启不报 EADDRINUSE） | `0.1.6-SNAPSHOT`，code 106 |
| **T-D1** | 出货身份切换：正式包名 `com.dsh.fcl.androidlauncher` + 新签名 + 非 debuggable（提前到 fix6 落地） | fix6，code 112 |
| **T7 瘦身** | rootfs `314,360,800 → 148,637,136` 字节（−52.7%），解压后 1436 → 862 MB；已随 0.1.3 起的版本上真机 | 见 `ROOTFS.md` |

### fix7 ~ fix22 完成情况概览（逐版细节见 `RELEASES.md`）

| fix | 主要交付 | 调查/锐评来源 |
|---|---|---|
| fix7 | 部署页实时日志面板、安装期活动行、启动冒烟自检 + 坏树自愈 | 作者 fix6 真机反馈（BL-165~167，无独立报告目录） |
| fix8 | 「复制访问链接」出口（BL-168 🔴）、部署页/日志页五处真机问题 | R21 五路 |
| fix9 | 门禁框第三出路 + 不可取消，但引入"复制后整屏空白" | R22 六路 |
| fix10 | fix9 返工：按钮恒挂 + 「稍后」出口 + `auth=yes\|no` 留痕 + 空白兜底 | R23 六路锐评 |
| fix11 | seccomp 重试真正 fork、孤儿 proot 根治、停止超时 13s、进程身份校验、Stopping 忙态 | R23b S2 / R24 |
| fix12 | 五套「装好了没」判据收成一套、fail-open 改 fail-closed、准备页判据同源 | R23b S1+S3 / R25 |
| fix13 | 契约测试取代"钉实现影子"；补开发与贡献文档 | R23b S6 / R26 |
| fix14 | fix13 的对照测试去掉镜像实现 + 变异验证 | R26 收尾 |
| fix15 | 外部浏览器「选不了工作区」：`HOME` 指向实例 workspace | R27 P0-A |
| fix16 | 「在浏览器中打开」+ 端口固定（外部浏览器长期免认证） | R27 P0-B/P0-C |
| fix17 | `HOME` 改指向实例目录本身 + 新增「启动目录」设置项 | R27 P0-A2（P0-A 返工） |
| fix18 | 自定义启动目录自动创建、拒绝 `..`、三处过时注释 | R28 锐评 |
| fix19 | 幽灵实例保护（运行时目录下不再新建目录）、保存值与显示值一致 | R28 二轮 |
| fix20 | API Key 暴露面提示、typo 空目录可诊断 | R28 三轮 |
| fix21 | 空目录自动告警 `WARN home-has-no-subdir`、更正三处错误说明 | R28 C3 |
| fix22 | 复制链接成功后不再重摆门禁（BL-221）；`--patch` overlay + `/assets` 缓存头插件（BL-222） | R29 C3 第 6/8 条 |

## P1 · UI 还原

### ~~T3. 资源补齐~~ —— **已核对，无需执行**
- 结论：FCL 有而我们缺的 71 个 drawable，**没有一个需要补** ——
  要么是 FCL 自己的未使用资源（`bg_right_menu_button` / `bg_game_menu_inset` /
  `bg_container_transparent_selected` / `ic_baseline_file_24` 等，全仓库零引用），
  要么是 MC 专属（控制器/整合包/账户/渲染器），要么是 MC 页面专用图标
- 教训：`git grep bg_item` 会命中 `bg_item_rounded`（子串误匹配），查资源引用要加边界

### T5. 插件管理子页（等插件体系确定）
- 设置页目前 3 个子页（启动器设置 | 日志 | 关于）；FCL 是 4 个。插件体系定下来后补一个子页

## P2 · 运行时与稳定性

### T6. 真机端到端验收（关键）—— **0.1.6 已点亮；当前验证包 fix22**

> **2026-10-30 新增：验证工具**
> - **能上电脑的别推给真机**：`sh tools/host-dsh-check.sh` 在 Windows 上真跑 dsh，
>   覆盖启动链 / 配置注入 / 前端静态资源缓存头（带反向对照）。零额外安装。
> - **必须真机的**：`sh tools/e2e-device.sh`（需接手机）——装机冷启落点、日志断言、
>   门禁框文案、截图留证。没接设备时退回码 2（环境问题，不是失败）。
> - 两条的**能测/不能测**边界见 `docs/DEV-PLAN.md §3.8`（内部文档）。

> **当前验证包**：`0.1.6-SNAPSHOT-fix22`（`v0.1.6-SNAPSHOT-fix22`，code 128，已发布）。
> 历史对照包：`fix21`~`fix1` 与无后缀 `v0.1.6-SNAPSHOT`（code 106，对照基准）大多在 Releases 页
> （fix5 例外：仅本机验证、未上传）——一般不要装，出分歧时用来回装确认"问题是不是修出来的"。
> 历史对照：0.1.2（旧 rootfs，`8abaa3ea…`）与 0.1.3 起（瘦身 rootfs，
> `216cd9ef…`）的包仍在 Releases 页，可用于"瘦身有没有删坏东西"的对比。
>
> **0.1.6 已验证**（第三轮反馈前的真机日志证实）：停止杀 node（code=137）、
> PORT=0 真实端口回填（46501）、安装链（554M 依赖）均生效；**dsh 在真机成功打开**。
> **fix1 已验证**（第四轮反馈 bebug 证实）：停止质量史上最干净（EXIT code=0 优雅退出）；
> 兼容弹窗叠影/刷新转圈/外跳白屏/启动中进不去等问题在 fix2 修复。
> **fix2/fix3 历史包**：fix2 修第四轮 11 条；fix3 修日志链（智能跟随/K27/#7/#8b）。
> **fix4/fix5 历史包**：fix4 修认领裸 URL / 合成 URL 回归 / WebView 阈值 119 / 停止校验；
> fix5 做了 401 全覆盖（探活式 token 恢复），**按作者要求只做本机验证、未上传 Releases**。
> **fix6 起**：出货身份切换为新包名 + 新签名（旧 `.debug` 包与之并存、数据不迁移）。
> **fix7 ~ fix22**：每轮要么由作者真机反馈驱动，要么由上一轮的对抗锐评驱动
> （例如 fix11~fix14 来自 Round 23b 的 S1~S6；fix22 说明里引用的现场日志来自 fix16）。
> 各轮必验清单已写在对应 `RELEASES.md` 段落里，**不再逐版累积到本文**；本文只留
> 仍然有效的基础验收项与**当前包（fix22）的必验项**。

- **DNS 修复是否真的生效**（`npm install` 能否成功装 0.2.x）—— 0.1.6 已验证
- 首次解压 rootfs 的耗时与成功率（瘦身后约 862MB 解压 + 一实例约 523MB；装机前可用空间 ≥1.7GB）—— 0.1.6 已验证
- **瘦身有没有删坏东西**：`probe.sh` 的 `node` / `npm` / `dsh` 三项必须全过、
  `dsh web` 能起服务、`npm install` 能跑（后者依赖 npm 内部的 node-gyp 链）—— 0.1.6 已验证
- 前置页 / 首页任务区 / 实例详情页 / 右面板按钮的实际观感与行为 —— 长期回归项（各版反馈会继续调整）
- proot 内 `dsh web` 能否起来、WebView token/cookie、息屏保活、失败恢复 —— 长期回归项（每版都值得看一眼）
- **fix2 新增必验**（第四轮修复的 11 条 + 存量重点，见发布说明 `fix2` 段）——**已并入 fix3 起的各版回归**：
  ①启动中退出再进（入口放宽）；②外跳浏览器 → 回首页 + 无 `authentication required`；
  ③刷新（数据无变化场景）会停转；④长日志上滑读历史不漂移、回到底部可靠；
  ⑤安装中锁屏放置数分钟后回来仍正常；⑥停止中再点停止/删除无残留；⑦安装进度不再重播整块动画
- **fix3 新增必验**（Round 15，见发布说明 `fix3` 段）——**已并入后续各版回归**：
  ①日志页上滑读历史**不再被拽回**（智能跟随）；②日志页滚动比 fix2 顺滑（K27）；
  ③日志新行**淡入**（#7）；④**点左侧菜单切页**且有点击动画（含"菜单不切页"回归的修复确认）；
  ⑤设置里切语言**不弹回首页**；⑥同一个启动失败只弹一个框
- **fix4 新增必验**（见发布说明 `fix4` 段）——**已并入后续各版回归**：
  ①**401 主链**：启动实例 → 杀 App（划掉后台，不点停止）→ 重进 App（实例显示运行中=已认领）
  → 打开界面 → 选"用外部浏览器打开" → **浏览器应直接进入 dsh（不再 401 英文页）**；
  ②升级自旧版本首次认领若日志出现 `no persisted token url` 行：正常启动一次实例后即恢复；
  ③**旧 WebView（<119）**：选"继续"后页面崩坏时应**再弹一次带升级说明的提示**（对话框
  含"去哪里更新 WebView"；此为"有时不弹提示"的修复确认）；
  ④停止实例后不再出现"停止后 proot 仍存活"的误报（或确实出现时就是真残留，值得排障）；
  ⑤正常启动/停止/对话一条龙无回归
- **fix22 新增必验**（见发布说明 `fix22` 段）：
  ①**复制后不再重问**：打开界面 → 门禁框选「复制链接」→ 确认复制成功 →
  界面应停在「链接已复制……」面板（不重摆门禁框），带重试 / 日志 / 停止 / 关闭四个出口；
  ②从浏览器切回 App → **不应再被问一次**（此前会）；点「重试」→ 门禁框正常出现；
  ③复制失败 / 取消确认框时行为不变（仍回到门禁框）；
  ④**外部浏览器二次进入变快**：同一实例在外部浏览器里刷新 / 再开一次，
  响应头应带 `cache-control: public, max-age=31536000, immutable`（前端文件不再每次重下）；
  ⑤正常启动/停止/对话一条龙无回归（`--patch` overlay 不得影响启动）
  > 已知未解决（发布说明里也写了，不要当回归）：**退出再进界面仍会提示一次** WebView 门禁
  > （标记不落盘，刻意为之）；开着确认框时若发生状态回放（如按 Home 再回来），门禁框仍会重摆一次。

#### 怎么连设备 / 怎么抓日志

> 装机**主路径免 adb**（手机浏览器下 Release 附件直接装）；adb 链路是「反复装 + 抓详细日志」的可选增强。
> 下面命令里的 `adb` 指 Android SDK 的 `platform-tools/adb`（Windows 上是 `adb.exe`）。

- **包名**：fix6 起出货包 `com.dsh.fcl.androidlauncher`（无后缀）；调试通道（fordebug）
  `com.dsh.fcl.androidlauncher.debug`（`applicationIdSuffix=".debug"`）。两者可并存。
- **连设备**：`adb devices` → 看到 `<序列号>\tdevice`；`unauthorized` 则在手机上允许 USB 调试。
- **装机（adb 路）**：`adb install -r dsh-fcl-android-launcher-<ver>-arm64.apk` → `Success`；
  签名/版本不兼容先 `adb uninstall com.dsh.fcl.androidlauncher.debug` 再装（会触发重解压）。
- **抓日志**（证据主次：应用落盘日志为主，logcat 抓崩溃为辅）：
  - 落盘运行日志：`adb shell run-as com.dsh.fcl.androidlauncher.debug cat files/dsh/logs/runtime.log`
  - 实例日志：`... cat files/dsh/logs/instance-<id>.log`（路径见 `DshPaths.kt` / `DshLogBus.kt`）
  - logcat：先 `adb logcat -c` 清缓冲，再 `adb logcat --pid=$(adb shell pidof -s com.dsh.fcl.androidlauncher.debug)`；
    崩溃用 `logcat -d AndroidRuntime:E CrashReporter:E '*:S'`。
  - 应用内：设置 → 运行时自检（跑 `probe.sh`，末行 `dsh-probe-ok`）；设置/实例详情 → 日志。
- **设备空间**：跑前 `adb shell df -h /data`，确保可用 **≥1.7GB**（解压 862MB + 临时 + 一实例约 523MB）。
- **记录**：失败一律记下时间戳 + 原始日志行（不加工）。

### T7. rootfs 瘦身与可下载化 —— **瘦身已完成并已上真机跑通（0.1.6）**

**已做**（用 GNU tar `--delete` 在原包条目上直接剔除，不重打包）：

| 剔除项 | 理由 |
|---|---|
| `/usr/lib/gcc`、`/usr/include`、`libasan/libtsan/liblsan/libubsan/libhwasan/libcc1`、`lto-dump` | GCC 开发环境 —— 只在**现场编译**时才用；但 dsh 那 5 个原生模块全部**走 prebuild 分发**（node-pty 有 `prebuilds/linux-arm64/pty.node`、koffi/sharp/两个 node-addon 各自带 linux-arm64 二进制），实测 `/.node` 文件里**没有一个**是编译产物、`node-pty/build/Release/` 不存在 ⇒ 用不到编译器 |
| `/usr/lib/python3.11` + `/usr/bin/python3` 以外解释器 | 同上（node-gyp 的 python 依赖）。**保留 `python3` 二进制**（6.6MB）作保险 |
| `/usr/share/man`、`/usr/share/doc`、`/usr/share/info`、`/usr/share/gitweb` | 文档 |
| perl 全家（`/usr/share/perl*`、`/usr/lib/*/perl*`、`/usr/bin/perl*`、`cpan`） | 没有任何东西在跑 perl（apt 的 perl 依赖是 `perl-base` 的 C 工具，已保留） |
| `/usr/lib/git-core` + `git`/`git-shell`/`scalar` | dsh 自己带 git 能力时不依赖系统 git；`ssh` 保留 |
| `/root/.cache/node-gyp`（56MB）、`/var/lib/apt`（78MB）、`/var/cache/apt`（75MB） | 构建期缓存 / apt 索引 |
| `gconv`（非 UTF-8 字符集转换）、6 种大语种 locale（fr/ru/uk/sv/es/de） | 省空间；**保留 UTF-8 + 中日韩 + en** |
| 6 个 sanitizer 的软链（目标已删，留着是断链） | 一致性 |

**保留**：`gcc`/`g++`/`cpp`/`make`/`pkg-config`/`python3`（各自几 MB）、全部 `node_modules` 与
`/opt/node22`、apt/dpkg/openssl/ssh、`en`+`zh`+`ja`+`ko` 等 locale。

**实测结果**：`rootfs.tar.xz` **314,360,800 → 148,637,136 字节（−52.7%，省 158 MiB）**；
解压后 **1436 MB → 862 MB**；条目 57,527 → 39,975。
sha256（新版）：`216cd9ef915a3512b9fedadfed7d5be6119422d120005c86b54d9e3b8ccea3f6`
（旧版 `5d762c30…` 仍可从 0.1.2 的 Release 附件里取回）。

> ⚠️ **新旧不可混用**：`rootfs/version` 已同步改版（`…-slim1`），否则 App 不会重新解压。
> 瘦身包已随 0.1.3 起的版本发布，并在 0.1.6 真机端到端跑通（`node/npm/dsh` 自检 + `dsh web`
> 起服务 + `npm install`）——"删对了"已有直接证据。

**剩下没做的**：
- `pkg-config` 保留与否可再评估（它在 native 编译链里，单独存在无意义 —— 但它要求 `--as-needed`
  会被链接器用，`libglib-2.0.so` 又反过来要求它，留着更省事）。
- 更远期：改为**首次下载 + 校验**（APK 不含 rootfs，装完再下），APK 体积能再降一半以上。

### T8. 缓存与清理 UI
- npm cache（`dsh/npm-cache`）与 rootfs 体积显示 + 一键清理，放进设置页
- **状态（2026-10-30 核实）：未做** —— 设置页「启动器设置」里目前没有缓存/体积/清理相关行
  （共享 npm 缓存本身已生效，见 `DshInstaller.kt` 顶部注释；缺的只是**展示与清理入口**）

## P3 · 其他

### T10. 文档同步 —— **主体已完成（2026-10-06）；0.1.6 起的增量由各版发布说明承接**
- 已做：`PLAN.md` / `ROADMAP.md` 里"运行时底座要走 `:proot-engine`"的过时表述
  全部更正为「自研 proot 层 + 预打包 rootfs」；`proot-engine-integration.md` 顶部标注**未采用**；
  `PACKAGING.md` 的 "Ubuntu/Alpine"、"需你放入" 改为现状（Debian 12 + 已就位）。
- 0.1.6 之后：逐版变化一律写在 `RELEASES.md`（GitHub Release 正文取自此）——
  本清单只跟"还没做/还没验"的部分，不逐版重复。
- 剩余（低优先）：`PLAN.md` 仍是早期方案原貌（有顶部过时声明指路），彻底重写收益不大。

---

## 待真机验证

> 这些是**已实现但还没在真机上跑过**的东西。代码与单测都过了，但单测覆盖不到"系统行为"。
> 状态标注：**0.1.6 已验证** = 第三轮反馈前的真机日志已证实；**待验** = 已修待真机确认。
> 各轮新增的必验项统一收在 T6（尤其"fix22 新增必验"）；本节只留**长期回归**项。

### V1. 返回键链 —— **长期回归项**

- 首页按返回 → **交系统退出**（不是立刻退，也不是什么都不做）
- 非首页按返回 → **回首页**
- 实例详情里按返回 → **逐层退回**：详情 → 列表（而不是一路退掉 App）
- 左侧菜单的返回按钮 → 与按键**行为完全一致**
- **长按返回**（不要松手）：不应出现"先回首页、松手时整个外壳被退掉"

> 实现用的是 `onKeyDown` 而不是覆写 `onBackPressed`（理由：后者在 API 33+ 被
> `onBackPressedDispatcher` 接管，覆写会让"系统返回"与"自己调的返回"走两条路）。
> 依赖的前提是 manifest 没开预测式返回、targetSdk 34 —— 若将来把 targetSdk 提到 35，
> 这条链必须改走 `onBackInvokedDispatcher`，否则手势返回会绕过它。

### V2. 停止后进程是否真的没了 —— **0.1.6 已验证**

- 启动实例 → 停止 → **再启动同一个实例**：不应报端口被占
- 停止后等 10 秒，看应用日志里有没有
  `警告：停止后 proot 仍存活` 这一行（有就是没停干净，需要看现场）

### V3. 实例详情页 —— **0.1.6 已验证**（dsh 在真机成功打开，详情/日志链路已走通）

- 点实例行 → 进详情；三个标签（运行 / 日志 / 配置）都能切
- 「日志」标签只显示**这个实例**的输出（不是全世界混在一起）
- 切到别的标签再切回来：**临时页会回到列表**（这是既定行为 —— 临时页属于当前标签的上下文）
- 详情页在亮色模式下不再是整页全白（这是旧版独立页面的问题）

### V4. 日志分流 —— **待验**（长期回归项；日志页历经 fix3/fix9/fix21 多轮改动）

- 设置 → 日志：是**应用级**视角（解压/安装/凭据这些行）
- 实例详情 → 日志：只有该实例的行
- 落盘文件：`logs/runtime.log`（应用）与 `logs/instance-<实例>.log`（实例）各一份

### V5. 任务区与终态 —— **待验**（长期回归项；任务块在 fix6/R18 有文案与本地化改动）

- 装一个版本 → **装完之后那一行不会消失**，而是落到任务区下半段、标着完成
- 点「取消」→ 行不会瞬间消失，而是变成「已取消」（**不是**"安装失败"）
- 启动失败 → 任务区能留下一行失败，并且**带原因**（不再只在弹窗里闪一下）
- 任务区头部的「清空已完成」只在真有历史行时出现，点了能把下半段清空
- **取消**与**停止**是两张不同的图标（以前长得一样）

### V6. API Key 交给 dsh —— **待验**（长期回归项；fix20 新增"不要把 `home` 选成工作区"提醒）

- **没配 Key 也能启动实例**（以前会被一个"尚未配置 API Key"的弹窗挡住）
- 首次进 dsh 界面时提示一次"去哪填 Key"，**第二次进不再提示**
- 在 **dsh 界面 → 设置 → 模型** 里填 Key → **能保存、能对话**（这条最关键：
  旧版会因为启动器注入了环境变量而**拒绝写入**）
- 实例详情「配置」里**没有**"模型"这一行了
- 升级上来的旧安装：启动一次后，实例目录里的 `credentials.env` / `credentials.enc`
  应被自动清掉（应用日志里有一行"已清理遗留凭据文件"）
- **选工作区时选 `workspace`，不要选 `home`** —— `home` 里存着 API Key
  （`.credentials.yaml`），而 dsh 的文件接口允许读工作区外的文件（fix20 加的提醒）
