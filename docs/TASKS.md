# 任务清单（待办）

> 放**已明确要做、但未做**的事项。做完从本清单移除。

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

## P1 · UI 还原

### ~~T3. 资源补齐~~ —— **已核对，无需执行**
- 结论：FCL 有而我们缺的 71 个 drawable，**没有一个需要补** ——
  要么是 FCL 自己的未使用资源（`bg_right_menu_button` / `bg_game_menu_inset` /
  `bg_container_transparent_selected` / `ic_baseline_file_24` 等，全仓库零引用），
  要么是 MC 专属（控制器/整合包/账户/渲染器），要么是 MC 页面专用图标
- 教训：`git grep bg_item` 会命中 `bg_item_rounded`（子串误匹配），查资源引用要加边界

### T5. 插件管理子页（等插件体系确定）
- 设置页目前 2 个子页（启动器设置 | 关于）；FCL 是 4 个。插件体系定下来后补第三个子页，
  并可考虑撤掉外壳菜单里的「管理」占位 tab

## P2 · 运行时与稳定性

### T6. 真机端到端验收（关键）

> **验哪个包**：`0.1.3-SNAPSHOT`（2026-10-06 出包，**瘦身 rootfs**）。
> 0.1.2 那一份（旧 rootfs，sha256 `8abaa3ea…`）仍在
> <https://github.com/sqkl520/dsh-fcl-android-launcher/releases/tag/v0.1.2-SNAPSHOT>，
> **可以两个都装来对比**：0.1.2 验"底座能不能跑通"，0.1.3 额外验"瘦身有没有删坏东西"。

- **DNS 修复是否真的生效**（`npm install` 能否成功装 0.2.x）
- 首次解压 rootfs 的耗时与成功率（0.1.3 只需 **~1.5GB** 空闲，比 0.1.2 的 ≥2GB 宽松）
- **（0.1.3 新增）瘦身有没有删坏东西**：`probe.sh` 的 `node` / `npm` / `dsh` 三项必须全过、
  `dsh web` 能起服务、`npm install` 能跑（后者依赖 npm 内部的 node-gyp 链）
- 前置页 / 首页任务区 / 实例详情页 / 右面板按钮的实际观感与行为
- proot 内 `dsh web` 能否起来、WebView token/cookie、息屏保活、失败恢复

### T7. rootfs 瘦身与可下载化 —— **瘦身已完成（2026-10-06），待真机验证**

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
> 换包后**必须**在真机跑一次 T6 —— `probe.sh` 的 `node/npm/dsh` 三项与 `dsh web` 起服务
> 是对"删对了"的直接证据。

**剩下没做的**：
- `pkg-config` 保留与否可再评估（它在 native 编译链里，单独存在无意义 —— 但它要求 `--as-needed`
  会被链接器用，`libglib-2.0.so` 又反过来要求它，留着更省事）。
- 更远期：改为**首次下载 + 校验**（APK 不含 rootfs，装完再下），APK 体积能再降一半以上。

### T8. 缓存与清理 UI
- npm cache（`/opt/dsh/npm-cache`）与 rootfs 体积显示 + 一键清理，放进设置页

## P3 · 其他

### T10. 文档同步 —— **主体已完成（2026-10-06）**
- 已做：`PLAN.md` / `ROADMAP.md` 里"运行时底座要走 `:proot-engine`"的过时表述
  全部更正为「自研 proot 层 + 预打包 rootfs」；`proot-engine-integration.md` 顶部标注**未采用**；
  `PACKAGING.md` 的 "Ubuntu/Alpine"、"需你放入" 改为现状（Debian 12 + 已就位）。
- 剩余（低优先）：`PLAN.md` 仍是早期方案原貌（有顶部过时声明指路），彻底重写收益不大。



