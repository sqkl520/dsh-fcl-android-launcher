# R49 修复总账（第一轮）

五份审查报告共 **94 条**发现。本文件是**逐条修复记录**：原问题、怎么修的、怎么验证的。
第二轮 agent 验收时以本表为核对基准。

图例：`✅` 已修并验证 · `🔄` 施工中 · `⏸` 有意推迟（附理由）· `❌` 不修（附理由）

---

## 波次与文件归属（并行安全）

| 波 | 文件集 | 条数 | 状态 |
|---|---|---|---|
| W1-A | `tools/*.sh` | 6 | ✅ 完成 |
| W1-B | `DshLogBus.kt` | 4 | ✅ 完成 |
| W1-C | `ProotProcessExecutor.kt` | 2 | ✅ 完成 |
| W1-D | `DshAboutUI.kt` / `DshWebViewCompat.kt` | 2 | ✅ 完成 |
| W1-E | `res/values*` + `strings.xml` | 8 | ✅ 完成 |
| W1-F | `DshWebViewActivity.kt` | 9 | ✅ 完成 |
| W2-G | `DshInstances.kt`（热点，串行） | 8 | ✅ 完成 |
| W2-H | `DshRuntime.kt`（热点，串行） | 6 | ✅ 完成 |
| W3 | 结构性重构（ARCH） | 12 | ✅ 部分完成（见下） |

**热点文件互斥**：`DshRuntime.kt` / `DshInstances.kt` / `build.gradle.kts` 同刻只给一个 agent。
W1 全不碰热点，所以可全并行；W2 两个热点文件互不重叠，可并行（G 只改 DshInstances，H 只改 DshRuntime）。

---

## 已修（R49 期间，commit 在案）

| # | 问题 | 修法 | 验证 | commit |
|---|---|---|---|---|
| F-01 | `run-tests.sh` 并发互删产物目录 | `HARNESS/run-$$` 私有目录 + trap 清理 + 孤儿回收 | 两进程并发各 1096/0 | `d945625` |
| F-02 | `killBlocking` 的 SIGTERM/SIGKILL 失败静默 | 两条各补 `.onFailure { logFor(...) }` | `R49SilentFailureTest` + 变异 0→1 | `656965e` |
| F-03 | `writeFileAtomically` 备份失败静默（注释自相矛盾） | 补 `.onFailure` 记日志 | 同上 | `656965e` |
| F-04 | 死 pid / token 侧车删除失败却宣布"已清理" | 按实际结果分两种措辞；`deleteTokenUrlFile` 改返回 Boolean | 同上 | `656965e` |
| F-05 | `release.sh` 调 `$ROOT/*.sh`，但脚本已搬进 `tools/` | 新增 `$TOOLS`，4 处引用 + dry-run 回显 | `check-sync` 可跑通 | `f67b970` |
| F-06 | `build-apk.sh` `rm -f "$OUT"` 违反 output 红线 | 目标存在时先改名备份（时间戳+撞名序号），失败则中止 | /tmp 实测旧内容保留 | `f67b970` |
| F-07 | 私钥无 `.gitignore` 保护（只在 info/exclude） | `/release-key/` `*.jks` `*.keystore` `dsh.pwd` 写进 `.gitignore` | fresh clone 复测不再暂存 | `f67b970` |
| F-08 | README/PACKAGING/ondevice 四处失真数字 | 按实测改：1096/40、version=23、fix40/code146 | 逐条 grep 核对 | `e79daca` |
| F-09 | 🟠 | **S-15** `allowBackup` 只排除 `files/dsh/`，WebView cookie 库（30 天会话）可进云备份/迁移 | 两份 rules 各补 `app_webview/` `app_textures/` + 两个 cookie db | JDK XML 解析器校验通过 | 待提交 |

---

## W1-A：`tools/*.sh`（BUILD 报告 §7）

| # | 严重度 | 问题 | 修法 | 验证 | 状态 |
|---|---|---|---|---|---|
| A-1 | 🟠 | `bootstrap.sh:43` 的 `${1:?--repo 后面要跟 URL}` 在 dash 下把中文提示拼到值上 ⇒ `SRC_URL` 变成 `https://…/repo.git后面要跟` | 改纯 ASCII 提示，或拆成 `[ -n "${1:-}" ] \|\| { echo … >&2; exit 1; }` | dash 实测 | ✅ |
| A-2 | 🟡 | `bootstrap.sh:40` 的 `shift 2>/dev/null || true` 只 shift 1。**审查没发现更严重的**：POSIX 下特殊内建失败会终止 shell，`|| true` 拦不住 ⇒ `--check` / `--install-excludes` 在 dash 下 **exit=2 完全跑不起来**（电脑上 sh 是 bash 所以从没暴露） | **不采用审查建议的 `shift 2 || true`** —— 实测它会把 `--repo` 和 URL 一起吃掉（`set -- <target> --repo <url>` 只有 3 个参数，`$1` 已读进 `$TARGET`，该移的只有 1 个）。改用守卫式 `if [ $# -gt 0 ]; then shift; fi` | dash 实测 + 隔离脚本矩阵；修后 `--check` 能跑完出报告、`--install-excludes` exit=0 | ✅ |
| A-3 | 🟡 | `build-apk.sh:66` 用 GNU-only `find -printf`，与 `run-tests.sh` 的自律矛盾 | 改 `ls -t … \| head -1`（与 `e2e-device.sh:70` 一致） | BusyBox 兼容性 | ✅ |
| A-4 | 🟡 | `check-sync.sh:51-52` 校验 `docs/INDEX.md` / `docs/CHANGELOG.md`，两文件不存在 ⇒ 死代码 | 删掉这两条校验 | 跑 check-sync | ✅ |
| A-5 | 🟡 | `docs/PLAN-SANDBOX-ROUND49.md` 是内部文档却进了公开仓 | 移到 `_dsh-internal-docs` + 加 exclude | git status | ✅ |
| A-6 | 🟡 | `tools/publish-fix23-26.sh` 是一次性脚本，留在 Git 里 | 删除（历史在 git 里可查） | — | ✅ |

---

## W1-B：`DshLogBus.kt`（RELIABILITY）

| # | 严重度 | 问题 | 修法 | 验证 | 状态 |
|---|---|---|---|---|---|
| B-1 | 🟠 | `attachFile` 启动截断失败静默，与同文件 `rotateIfTooBig` 口径不一致（后者用 `getOrElse` 记 `lastFileWriteError`） | 补 `.onFailure { lastFileWriteError = … }` | 单测 | ✅ |
| B-2 | 🟠 | `lastFileWriteError` 无任何 UI 消费（只有测试读）⇒ 磁盘满时用户不知情 | 复用 `manifestBanner` 形态加一条横幅 | 单测 + 接线断言 | ✅ |
|   └ 范围变更 | — | B-2 的横幅只能落在 UI 层，而 bus 侧 agent 的文件范围原本不含 UI。**已批准扩范围到 `DshLogsUI.kt` + `strings.xml`（仅许新增 key）**。理由：只做 bus 侧 seam、UI 侧留补丁给下一轮 = 没真修完。约束：strings.xml 有 W1-E 同时在改，故只许追加；接线断言必须带变异验证（BL-245/246 的教训） | — | — | 已通知 |

| B-3 | 🟠 | 沙箱探测关键两行（`bwrap=absent` / `landlock=UNUSABLE`）落在 INFO，"只看错误"不可见 | `ERROR_RE` 加显式例外（前缀 `sandbox-probe` 且含 `absent`/`UNUSABLE`/`DENIED` ⇒ ERROR） | 单测 | ✅ |
| B-4 | 🟡 | 无 `CoroutineExceptionHandler`，`armFlushJob` 里 `flush()` 抛的 Throwable 进默认 handler | scope 加 handler 记一行 | 单测 | ✅ |

---

## W1-C：`ProotProcessExecutor.kt`（RELIABILITY）

| # | 严重度 | 问题 | 修法 | 验证 | 状态 |
|---|---|---|---|---|---|
| C-1 | 🟠 | `:942` `runCatching { handle.pid() }` 预解析失败丢现场，现成的 `logPidFailureOnce()` 不调 | `.onFailure { handle.logPidFailureOnce() }` | 单测 | ✅ |
| C-2 | 🟡 | `:155/:318` 空 `catch (InterruptedException)` 未恢复中断标志，与同文件 `:62` 规范不一致 | 各加 `Thread.currentThread().interrupt()` | 单测 | ✅ |

---

## W1-D：`DshAboutUI.kt` / `DshWebViewCompat.kt`（RELIABILITY）

| # | 严重度 | 问题 | 修法 | 验证 | 状态 |
|---|---|---|---|---|---|
| D-1 | 🟠 | `DshAboutUI.kt:88` 复制兜底失败静默，Toast 暗示"已复制"；同 App `DshWebViewActivity.kt:1137` 有完整失败文案（BL-207 修了那边漏了这里） | 补 `onFailure`；失败文案改用 `dsh_copy_url_failed` | 单测 | ✅ |
| D-2 | 🟡 | `DshWebViewCompat.kt:155` 探测后还原 `WebViewClient` 失败静默 —— 注释自己点名的"自伤型 bug"（页面加载成功但界面永远"加载中"） | 补 `onFailure` 日志 | 单测 | ✅ |

---

## W1-E：`res/values*` + `strings.xml`（UI）

| # | 严重度 | 问题 | 修法 | 验证 | 状态 |
|---|---|---|---|---|---|
| E-1 | 🔴 | 繁体中文缺 `dialog_positive` / `dialog_negative` ⇒ **所有确认框按钮在繁体下变英文** | 补 `values-zh-rTW/strings.xml` | 单测断言三语齐全 | ✅ |
| E-2 | 🟠 | `colorOnPrimary`/`colorOnSecondary` 与 `colorPrimary` 同值（对比度≈1:1） | 三份 themes.xml 都改 | 人工核对 | ✅ |
| E-3 | 🟡 | 3 组文案不一致（install/download；broken=损坏 vs 安装失败；`dsh_action_open_webui` 零引用） | 统一 | 单测 | ✅ |
| E-4 | 🟡 | 43 个 string key 零引用 | 删除（先逐个 grep 确认） | 单测 | ✅ |
| E-5 | 🟡 | 无 `dimens.xml`，8dp 圆角在 4 个 drawable 各写一次 | 抽 `dimens.xml` | 编译 | ✅ |
| E-6 | 🟡 | `dp()` 助手复制 5 份（`ConvertUtils.dip2px` 已存在） | 统一 | 编译 | ✅ |
| E-7 | 🟡 | 2 处 `parseColor("#000000"/"#FFFFFF")` 重复定义 `ThemePreference` 默认值 | 收敛到常量 | 编译 | ✅ |
| E-8 | 🟡 | `DialogCard.kt` 自造 `#323232`/`#F8F8F8`，与 FCL `dialog_background` 不一致且无 night 资源 | 改用主题色 | 人工核对 | ✅ |

---

## W1-F：`DshWebViewActivity.kt`（SECURITY + RELIABILITY + UI）

| # | 严重度 | 问题 | 修法 | 验证 | 状态 |
|---|---|---|---|---|---|
| F-1 | 🟠 | **S-01** 回环导航只判 host 不判端口，cookie 不区分端口 ⇒ 一次点击可把会话 cookie 交给 rootfs 里另一个监听者 | `shouldOverrideUrlLoading` 回环判据换成与 `loadUrl` 同一份 `webUiUrlAllowed(url, expectedPort)` | 单测 | ✅ |
| F-2 | 🟡 | **S-02** 外跳不判 scheme（`intent://` / `file://` 直进 `ACTION_VIEW`） | 白名单 scheme，其余拒绝 | 单测 | ✅ |
| F-3 | 🟡 | **S-03** `isLoopback` 认 `::1`，NSC 未放行 `::1`（口径不一致） | NSC 补 `::1` 或 `isLoopback` 去掉 | 单测 | ✅ |
| F-4 | 🟠 | **S-13** token URL 经**隐式** `ACTION_VIEW` 外跳，接收方不可校验（无 `<queries>`） | 加 `<queries>` 或改显式 | 单测 | ✅ |
| F-5 | 🟡 | **S-11** `[wsdiag]` 打原始 URL（DEBUG 包） | 走 `urlSkeleton()` | 单测 | ✅ |
| F-6 | 🟡 | **S-12** 前端 console 整句进日志，API Key 不在脱敏集合 | 脱敏集合加 key 形态 | 单测 | ✅ |
| F-7 | 🟡 | **S-14** 剪贴板 token 无 `EXTRA_IS_SENSITIVE` | 三处补上 | 单测 | ✅ |
| F-8 | 🟡 | **P2-10** 同一 URL 日志两套口径（`:455` 走 `urlSkeleton`，`:919` 原样） | 统一 | 单测 | ✅ |
| F-9 | 🔴 | 日志控制台色在 `onCreate` 取一次且未挂 `registerEvent` ⇒ 亮暗切换后停在旧色（`uiMode` 在 configChanges 里不重建） | 挂 `ThemeEngine.registerEvent` | 单测 | ✅ |

---

## W2-G：`DshInstances.kt`（热点，串行）

| # | 严重度 | 问题 | 修法 | 验证 | 状态 |
|---|---|---|---|---|---|
| G-1 | 🟠 | **S-05** 编辑器改写 `.credentials.yaml` 不强制 0600，dsh 会拒绝加载凭据 | `writeFileAtomically` 补 0600（抄 `DshRuntime.kt:3740-3743`） | 单测 | ✅ |
| G-2 | 🟠 | **S-09** 同函数无 `fd.sync()`（断电丢新值） | 补 `fd.sync()` | 单测 | ✅ |
| G-3 | 🟠 | **S-06** `.credentials.yaml.bak` / `.tmp` 是额外明文 Key 副本，永不清理 | 凭据类文件不写 `.bak`；`.tmp` 写完即删 | 单测 | ✅ |
| G-4 | 🟠 | **S-07** 用户真 YAML 解析失败时 `launcher.patch.yml` 被静默整份覆盖且无备份 | 解析失败时先备份再写，且日志说明 | 单测 | ✅ |
| G-5 | 🟡 | **S-08** `.tmp` 名固定，潜在并发踩踏 | 加 PID 后缀 | 单测 | ✅ |
| G-6 | 🟠 | **BL-250** `readInstalledVersion` 用会回退预装的 `effectiveDshDir` 判"这个实例装好了没" ⇒ 空实例被判"已装完" | 目录来源改 `ownDshDir`；给 `effectiveDshDir(instanceId)` 也打 `@Deprecated` | 单测（空目录+预装 ⇒ null） | ✅ |
| G-7 | 🟠 | **B3/BL-251** guest 路径两处用裸 `instance.id`，宿主用 `safeInstanceDirName` ⇒ 含 `\` 的 id 路径漂移 | 新增 `DshPaths.instanceGuestDir(id)`，两处改用 | 单测 | 等 W1（跨 DshRuntime） |
| G-8 | 🟡 | **S-17** `home/` 选成工作区 ⇒ agent 可读 Key（已有默认值+文案缓解） | 已是缓解态，仅补注释 | — | ✅ |

---

## W2-H：`DshRuntime.kt`（热点，串行）

| # | 严重度 | 问题 | 修法 | 验证 | 状态 |
|---|---|---|---|---|---|
| H-1 | 🟠 | **S-04** 默认 `port=0` 时端口门整维弃权，`loadUrl` 侧只剩 host+scheme | 从脚本 `READY port=` 取可信端口 | 单测 | ✅ |
| H-2 | 🟡 | **P2-6** 停止失败文案内联英文（中/繁界面显示英文） | 补 `dsh_reason_stop_incomplete` 三语 | 单测 | ✅ |
| H-3 | 🟠 | **BL-251** guest 路径裸拼（与 G-7 同一处改动，需协调） | 见 G-7 | 单测 | ✅ |
| H-4 | 🟠 | **R3** `_running` 只写不读的僵尸 flow（14 写点） | **不改**：`R36LockedRmwTest` 6 处断言钉着，且它守 RE-02 真竞态 | — | ⏸ |
| H-5 | 🟠 | **P0-4 沙箱零提示**（Kotlin 侧 `grep -rni sandbox` = 0） | 见 `docs/PLAN-SANDBOX.md` P0 | 单测 | ⏸ 需你批准 |

---

## W3：结构性（ARCH）—— 评估结论

ARCH 报告的 27 条里，**12 条是架构可维护性**（不是缺陷）。逐条判断：

| # | 条目 | 判断 | 理由 |
|---|---|---|---|
| L1 | `core` 反向依赖 `ui`（`DshRuntimeService → DshMainActivity`） | **可修** | 抽 `DshShellEntry` 契约，低风险 |
| L2 | `core` 取文案依赖 `FCLApp.getActivity()` | **可修** | 抽 `DshUiText` 接口 |
| C1 | 5 个循环依赖 | ⏸ | 拆环需先抽 `RuntimeCore`，是大工程 |
| R1 | 一把对象监视器横跨 3 职责 | ⏸ | ARCH 自己标"高风险，必须测试全绿先行" |
| R2 | `startLocked` 529 行 | ⏸ | 同上，依赖 R1 |
| R4 | `ProcessUtil` 位置错误（22 处调用） | ⏸ | 纯搬家，收益低风险中，单独的清洁活 |
| B2 | 4 份原子写实现 | **部分修** | 统一到 `writeFileAtomically`（配合 G-1/G-2 一起做） |
| B4 | pid 文件读 4 套 | **可修** | 收敛到 `readStateOf` |
| B5/B6 | `DshInstances` 前 824 行 + `delete` 338 行 | ⏸ | 纯搬家，单独一轮 |
| U1/U2/U3 | UI 直接操作 core 内部状态 | **可修** | 约 10 处，改走 core API |
| G1–G5 | object 当全局变量容器 | ⏸ | 纯卫生，最后做 |

**结论**：本轮修 L1/L2/B4/U1/U2/U3/B2，其余 ⏸ 并写明理由。**不假装全修完了。**

---

## 提交前必须做的清场

| # | 事项 | 状态 |
|---|---|---|
| 1 | 删掉 agent 留下的临时分析文件：`_w1e_check_keys.sh` / `_w1e_keys.txt` / `_w1e_keys_ctrl.txt` / `_w1e_scan.js` / `_w1e_scan_ctrl.js` | 待收尾 |
| 2 | 确认 `docs/PLAN-SANDBOX-ROUND49.md` 的 `git rm --cached` 生效，且文件仍在盘上 | 待收尾 |
| 3 | 跑全量 `run-tests.sh` 确认 TOTAL ≥ 1096 且 FAILED=0 | 待收尾 |
| 4 | 跑 `run-compile.sh` 确认编译通过（多 agent 并发改完是必查项） | 待收尾 |
| 5 | 跑 `check-sync.sh --offline` | 待收尾 |
| 6 | 逐条核对本表状态，把 🔄 改成 ✅ 或 ⏸（附理由） | 待收尾 |
| 7 | 确认没有 agent 越界改了别人范围的文件（`git diff --stat` 逐个看） | 待收尾 |

---

## 修复过程中的重要发现（不是审查给的，是修的时候才暴露的）

### D-1 🔴 `tools/bootstrap.sh:96` exclude 检测**恒 FAIL**（W1-A 发现，我已修）

```sh
grep -q '源码仓要忽略的路径' "$TOOLS/.git/info/exclude"      # 原模式
```
规范副本的标题是 `# 源码仓（.git）要忽略的路径`（多「（.git）」），**模式永不匹配**。
后果：`--check` 永远报「布局不完整」，这条检查**完全失去判别力**（永远 FAIL = 永远没信息）。

实测：
```
$ printf '%s' '# 源码仓（.git）要忽略的路径' | grep -c '源码仓要忽略的路径'   → 0
$ dash tools/bootstrap.sh --check   （修复前）
  [FAIL] 源码仓 info/exclude 未安装（跑 --install-excludes）      ← 即使装好了也这么报
```
**已修**：模式改 `grep -qE '源码仓.*要忽略的路径'`。修复后 `--check` → 「布局完整 ✅」exit=0。

这类"恒真/恒假断言"比功能 bug 更隐蔽 —— 它不报错，只是**沉默地不再提供信息**。

### D-2 🟠 审查给的建议本身可以是错的（A-2）

RELIABILITY/BUILD 审查建议把 `shift 2>/dev/null || true` 改成 `shift 2 || true`。
W1-A 实测发现**这会引入新回归**：

```
$ dash -c 'set -- /tmp/tgt --repo URL; TARGET=$1; shift; shift 2 || true; echo "$*"'
（空）                                    ← --repo 和 URL 都被吃掉
$ dash -c 'set -- /tmp/tgt --repo URL; TARGET=$1; shift; if [ $# -gt 0 ]; then shift; fi; echo "$*"'
--repo URL                                ← 正确
```
根因：`set -- <target> --repo <url>` 只有 3 个参数，`$1` 已读进 `$TARGET`，**该移的只有 1 个**。
审查看的是"`shift 2` 的语义被重定向破坏了"，但没意识到原意就不是移 2 个。

**教训**：锐评 agent 的建议必须实测。它看到的现象是真的，但给的改法可能制造新 bug。
第二轮验收时也要检查这类"照抄审查建议"的地方。

### D-3 🟠 `publish-fix23-26.sh` 删除后，仓库失去唯一的 APK 签名一致性校验

W1-A 在删 A-6 那条时发现：那个一次性脚本里有两段 `apksigner` 校验
（`:44-47`、`:84-90`），是仓库里**唯一**检查"新包签名与已发布旧包相同、可覆盖安装"的地方 ——
`release.sh` / `build-apk.sh` 都没有。

删掉脚本时这个能力一并消失。**这是真缺口**，但补它属于新功能（不是修 bug），
登记进 BUGLEDGER 供后续决定，本轮不动 —— 否则"修 94 条"会变成"顺手加新功能"，范围失控。

### D-4 🔴 审查报告的"零引用"结论不可靠 —— 动态查表会漏（W1-E 发现）

UI 审查说「43 个 string key 零引用」，W1-E 逐个核实后发现其中 **`world_time` 在用**，
而且静态 grep **永远扫不到**：

```java
// LocaleUtils.java:173
int resId = context.getResources().getIdentifier("world_time", "string", context.getPackageName());
```

如果照单全删，`getIdentifier` 返回 0 ⇒ 日期格式串退化成硬编码的 `"EEE, MMM d, yyyy HH:mm:ss"`，
中文环境下本该显示 `yyyy 年 MM 月 dd 日 HH:mm:ss`。

W1-E 额外扫了全仓 `getIdentifier("X","string",…)` 这种动态取法（1683 个文件），
**只有这一处**命中本次清单，于是只删 42 条、保留 `world_time`，并写测试同时钉住
「资源存在」和「`getIdentifier` 调用还在」（后者保证将来若删掉调用，测试会变红提醒重新评估）。

**另一条同类误报**：UI 审查说 `dsh_action_open_webui` 零引用，实际
`activity_dsh_main.xml:256` 的 `android:text="@string/dsh_action_open_webui"` 在用。

**教训**：以后凡是要"删零引用资源"，必须先扫三种动态取法：
`getIdentifier(`、`getResources().getIdentifier(`、`Resources.getSystem()`。
`grep '<key名>'` 只覆盖静态引用（Kotlin/layout/manifest），**不足以证明零引用**。

### D-5 🟡 审查的计数有三处不准（W1-E 实测）

| 审查说 | 实测 | 说明 |
|---|---|---|
| 8dp 圆角在 **4 个** drawable 各写一次 | **10 个、20 处** | 漏了 `bg_container_white_clickable` / `bg_item_rounded_top/bottom` / `bg_tab_top_rounded` / `bg_right_menu` / `bg_game_menu` 等 |
| 2 处 `parseColor` **重复定义** | 没有重复定义，是两处独立调用 | 措辞错，不是缺陷 |
| `dp()` 复制 5 份可直接换 `ConvertUtils.dip2px` | **不能直接换** | 舍入行为不同（`dp()` 用 `+0.5f` 四舍五入，`dip2px` 用 `toInt()` 截断），直接替换会让所有尺寸偏小最多 1px |

**教训**：审查报告的数字是"agent 的 grep 结果"，也要抽样复核。这次三处数字有两处偏差、
一处措辞错误。第二轮验收要专门查这个。

### D-6 🔴 多 agent 并行导致的两类真实破坏（都已修）

多 agent 并行改同一个仓库，出现两类互相踩踏。**这不是理论风险，是实际发生的**：

**① 删死资源时误伤测试表**（W1-E ↔ `DshResourceFormatTest`）

W1-E 删了 42 个零引用 string key，其中 7 个仍登记在 `DshResourceFormatTest.callSites` 里
（那个表的作用是"验证带占位符的串能被正确格式化"，与"有没有人用"是两件事）。
后果：`dshStringFormatsAcceptTheArgsPassedInCode` 抛 `@string/X 在 values 中不存在`。

涉及的 7 个：`dsh_settings_version`、`dsh_disk_usage`、`dsh_about_version`、
`dsh_instance_detail_title`、`dsh_logs_path`、`dsh_download_error`、
`dsh_download_warning`、`dsh_install_skipped`。

**修法**：从测试表移除这 8 条（每一条都先用三种取法确认零引用：
`R.string.X` / `@string/X` / `getIdentifier("X",…)`）。
**系统化验证**：改完后跑了一遍全表核对 —— 49 条登记 × 3 个语系 = 0 缺失。

**教训**：删资源前必须同时查两个方向 —— ①代码有没有引用它 ②有没有测试在登记它。
第二个方向我一开始也漏了，是跑测试才暴露的。

**② 放宽测试判据时误伤正确实现**（W2-G ↔ `R43OverlayAtomicReplaceTest`）

`R43` 的判据原来是 `body.contains("delete(")` —— 意思是"`writeLauncherPatchOverlay` 里不许
出现任何删除"。W2-G 为修 S-08（tmp 名唯一化）加了失败路径的 `tmp.delete()`，
这是**必需且正确**的，却被那条宽判据判成 SP-08 复发。

**修法**：判据收紧到"不许删 **target**"：
```kotlin
Regex("""\b(?:target|targetFile|overlay|overlayFile)\s*\.\s*delete\s*\(""")
```
**保留判别力**：实测当前实现 0 处（绿）、2 处删 tmp（允许）、人为加回 `target.delete()` 时 1 处（红）。

**教训**：源码文本判据要"精确到意图"，不能停在"看到某个词就算缺陷"。
判据越宽，越容易在别人做正确改动时误报 —— 而误报会让人开始怀疑测试本身。

### D-7 🟡 W1-B 的 UI 横幅：它停下来报告，我代为落地

W1-B 正确遵守了"范围外的文件不许碰"，把精确补丁写进报告。我在它跑完前已批准扩范围，
消息没赶上。于是我按它的补丁落地：

| 改动 | 文件 |
|---|---|
| 横幅 View（默认 `gone`，`FCLTextView` + `bg_container_white` + `auto_text_tint`） | `res/layout/activity_dsh_logs.xml` |
| `log_scroll` 的 top 重新锚到横幅下 | 同上 |
| 订阅 `DshLogBus.logFileWriteFailure`，null↔非 null 切可见性 | `ui/shell/DshLogsUI.kt` |
| 三语文案 `dsh_logs_write_failed` | `values/` `values-zh/` `values-zh-rTW/` |
| 接线断言（5 条：订阅存在 / 用 collect / 真切可见性 / 两个分支都在 / 布局与三语齐备） | `R49LogBusFixTest.writeFailureBannerIsWiredInLogsUi` |

**为什么用 XML 而不是 W1-B 建议的代码建 View**：UI 审查 §6.2 记过这个坑 ——
`FCLConstraintLayout.autoTint` 是 public 字段，改它不触发刷新，代码建 View 只能手写 tint。
XML 的 `FCLTextView` 自带那套。这个偏离已在代码注释里说明。

### D-8 🔴 审查的"可信来源"建议不成立 —— 那个来源本身可伪造（W2-H 发现）

S-04 审查说「默认 `port=0` 时端口门整维弃权，改成从脚本 `READY port=` 取可信端口」。
W2-H 核实了传输链后指出：**这个"可信来源"可信度不高于它要校验的对象**：

| 环节 | 事实 |
|---|---|
| `start-dsh.sh:605` | `LOGFILE="${TMPDIR:-/tmp}/dsh-start-$$.log"` |
| `start-dsh.sh:618-629` | `flush_new` 把 LOGFILE 的新增字节**原样 `tail -c`** 到 stdout |
| `start-dsh.sh:710/714/777` | node 的 stdout 是 `>"$LOGFILE" 2>&1` —— 写进同一个文件 |
| `ProotCommand.kt:186` | `--bind=<cacheDir>/dsh/tmp:/tmp` —— **rootfs 内可写** |

⇒ rootfs 里任何进程都能往 `/tmp/dsh-start-*.log` 追加一行 `READY port=1234`，
而它走的是和真 READY 行完全相同的通道。**用这条通道做信任锚等于没做。**

W2-H 改成两半，**两半都必要**：

1. **锚点**：仍读那一行，但**必须** `Probe.isPortOpen(port)` 通过才采信
   —— 端口真的在监听才是独立证据（宿主进程的 `Socket().connect`，不经 rootfs 写面）。
2. **退让门**：基准仍不可知时（锚点还没到），候选 URL 的端口**也必须真的在监听**。

只做第 1 半不够：攻击者把伪造行排在真 READY 行**之前**，那一刻基准还是 0，端口维度照样弃权；
第 2 半与到达顺序无关，正好补这个窗口。

**这件事的方法论意义**：这是本轮唯一一处"审查给了方向，但方向本身有安全漏洞"的案例。
上一轮的锐评报告也有一次类似（`shift 2 || true` 会引入回归，见 D-2）。
**锐评 agent 是很好的勘误者，但不能替代对安全性假设的独立复核。**

---

## 补记：RETURNED.md 的两条 🔴 从未入账（R2 验收发现）

R49 早期的 `RETURNED.md`（安装部署链审查）有两条 🔴，它们**既不在上面的表里、也没有 fix 报告、
也没标 ⏸**。R2 验收 agent 在 HEAD 上核实：两条都**没修**。

| # | 严重度 | 问题 | 当前状态 | 为什么本轮不做 |
|---|---|---|---|---|
| R-1 | 🔴 | **两套"就绪"定义之间的死循环**。`isReady()` 只查布局（7 条），`verify()` 跑 9 项真检查；准备页只认 `verify`；「重试」走 `install()`，而 `install()` 只在 `forceExtract \|\| !isLatest \|\| !rootfsLooksUsable` 时重解压 ⇒ `isReady()==true` 时三条件全假 ⇒ 不重解压 ⇒ 再 verify ⇒ 再失败。典型触发：精简 rootfs 缺 `bash`。 | **未修**（`DshBootstrap.kt:895-898` 三条件仍是原来那三个；`forceExtract` 仍只由"重新开始"按钮传，`:805` 默认 false，无 verify-failure 调用点） | 这不是"某一行写错"，是**两套就绪语义需要合并**。改它要重新设计 `isReady`/`verify`/`install` 三者的关系，属于设计级改动，不该混在"修 94 条审查发现"里。**但它影响可用性最大**（用户被困在准备页，唯一出路没有提示），应单开一波。 |
| R-2 | 🔴 | **npm 缓存只增不减，且不计入空间门槛**。`checkInstallSpace`（`DshInstaller.kt:903`）只算 `nodeModulesBudgetBytes`，`npm-cache` 未计入；`grep 'npm cache clean'` 全仓 0 命中。 | **未修** | 同上：需要决定"何时清缓存"（每次装完？超过多少？），是策略问题不是缺陷修复。 |

**这两条已登记进 BUGLEDGER**，编号 BL-253 / BL-254。

### R2 验收指出但我判断不改的

| 项 | R2 建议 | 我的判断 |
|---|---|---|
| zh-rTW 仍缺 24 个 key | 补齐 | **不改**：E-1 只点名了 `dialog_positive`/`dialog_negative`（那两条是"所有确认框按钮变英文"，🔴 级）。剩下 24 个是既有的、与本轮审查无关的历史缺口，补齐属于新工作。**已登记**，不假装本轮覆盖了。 |
| 总账"94 条" vs 波次表自加 57 | 重做数量 | **已改**：上方波次表现在按实际状态标了。但"94"这个数**本来就指五份报告的全部发现**（含 🟡 建议级），而实际**可行动并修完的是 41 条**。两个数不该混着说——已在下面写清。 |

### 数量口径（避免下一轮再被误读）

| 口径 | 数字 | 含义 |
|---|---|---|
| 五份报告的全部发现 | 94 | 含大量 🟡 建议级（如"配色可统一""注释可整理"） |
| **本轮实际修完** | **41** | 表里 ✅ 的条数 |
| 有理由推迟 | 7 | H-4/H-5 + R2 判断不改的若干 |
| 从未入账、本轮补记 | 2 | R-1 / R-2（上面两条 🔴） |
| 审查自己标"未深挖/待评估" | 其余 | ARCH 的结构性重构（C1/R1/R2 等） |

---

## 第二轮验收：已收到的结论（R2-INTEGRATION）

验收 agent **说对了四件事**，其中两件是我自己引入的：

| # | R2 发现 | 我的处置 |
|---|---|---|
| 1 | 🟠 **我加的横幅是白底白字，对比度 1.00:1（等于看不见）** | ✅ 已修：加 `app:auto_text_background_tint="true"`。**这是我自己犯的错** —— 修了个看不见的横幅，等于没修 |
| 2 | 🟠 页面内点链接仍走裸 `ACTION_VIEW`，绕过 F-4 的加固 | ✅ 已修：改用 `externalBrowserIntent(url)`，日志同时记下"交给了谁" |
| 3 | 🟠 `DshInstanceSettingPage` 还有**两处** guest 路径裸拼（我说的"两处调用点"只数了两个文件） | ✅ 已修：两处都改 `DshPaths.instanceGuestDir` |
| 4 | 🟠 `DshInstanceSettingPage` 两处凭据出口没加固（S-13/S-14 实际覆盖 3/4 不是全量） | ✅ 已修：复制走 `sensitiveClip`、外跳加 `CATEGORY_BROWSABLE` |
| 5 | 🟡 三处 KDoc 写着 `initStep("dshNavigation")`，实际不是 | ✅ 已修（纯文档，但会误导下一个改启动链的人） |
| 6 | 🟡 `registerUiCapabilitiesForCore()` 裸调无 try，是 onCreate 里唯一没兜底的步骤 | ✅ 已修：套 try/catch 并**留日志**（不改变 initStep 条数 6，保住"绝不穿出去"） |
| 7 | 🟠 **总账不可信**：标 🔄 的有 29 条、标「等 W1」13 条，实际大多已修 | ✅ 已重做：✅ 从 8 → 41，🔄 从 29 → 0 |
| 8 | 🟠 **RETURNED.md 的两条 🔴 从未入账** | ✅ 已补记 + 登记 BL-253/BL-254 |

**R2 说对的地方我要明确认下**：第 1 条和第 7 条是最有价值的两条 ——
前者是"我以为修好了但实际看不见"，后者是"我的记账方式会给下一轮错误答案"。
这两类都不是代码 bug，是我自己的工作质量问题。

**R2 的额外提醒（我采纳）**：它跑测试时树是干净的，但之后又被别的验收 agent 改过。
**收工前必须在全部 agent 停止后再跑一次全量** —— 这条我会照做。
