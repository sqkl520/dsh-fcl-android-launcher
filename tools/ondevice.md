# 真机验证操作手册（On-Device Runbook）

> 产出：T-A5。目的：让作者在真机上照着跑完「出包 → 装机 → 启动 → 抓日志 → 逐条打勾」。
> 配套文件：`tools/adb.sh` / `tools/adb.cmd`（adb 绝对路径包装）、`tools/t6-checklist.md`
> （批 A 真机门逐条打勾）、`tools/bebug-template.md`（日志记录模板）。
>
> **标注约定**：每条命令都标了预期输出。
> - `[已核对·本机]` = 作者机器上无设备也已实跑验证可行（见文末「本机核对记录」）；
> - `[需真机]` = 必须接上手机才能验，预期输出是「应看到类似 …」的描述，**未在真机实跑**。

---

## 0. 前置信息（本仓库实测）

| 项 | 值 | 出处 |
|---|---|---|
| adb 可执行文件 | `D:\Android\Sdk\platform-tools\adb.exe`（**未加 PATH**） | 本机 `ls` 已核对存在 |
| adb 版本 | 1.0.41 / 37.0.1 | 本机 `adb version` |
| 安装后包名 | **fix6 起出货包**：`com.dsh.fcl.androidlauncher`（无后缀）；**调试通道**（`assembleFordebug`）：`com.dsh.fcl.androidlauncher.debug` | `FCL/build.gradle.kts`：`applicationId`（+ fordebug 的 `applicationIdSuffix=".debug"`） |
| 当前版本 | `0.1.6-SNAPSHOT-fix40`（code 146） | `gradle.properties` 的 `dshVersion` / `dshFixLabel` |
| APK 产物 | `output/dsh-fcl-android-launcher-<ver>-arm64.apk` | `build-apk.sh`，本机 `output/` 已有 0.1.3/0.1.4/0.1.5 |
| 应用私有根 | `<filesDir>/dsh/`，debug 包 filesDir = `/data/data/com.dsh.fcl.androidlauncher.debug/files` | `DshPaths.kt` |
| 运行日志 | `<filesDir>/dsh/logs/runtime.log`（DshLogBus 落盘，1MB 轮转） | `DshPaths.kt:90` |
| 实例日志 | `<filesDir>/dsh/logs/instance-<id>.log` | `DshLogBus.kt:262-267` |
| 自检探针成功标志 | 最后一行打印 `dsh-probe-ok` | `probe.sh:150`、`DshBootstrap.kt:369` |

**为什么不加 PATH**：避免污染环境。所有 adb 命令都走包装脚本，脚本里把路径写死：
- Git Bash（推荐，本手册默认）：`sh tools/adb.sh <参数...>`
- cmd.exe：`tools\adb.cmd <参数...>`

两者都把参数**原样透传**给真实 adb。下文一律用 Git Bash 写法；换 cmd 只需把 `sh tools/adb.sh`
换成 `tools\adb.cmd`。

> **主路径免 adb**：装机默认走 GitHub Release（手机浏览器下载安装），**不需要 adb**。
> adb 链路是「想抓详细日志 / 想快速反复装」时的可选增强。

---

## 1. 出包（在电脑上）

```bash
# 版本号读自 gradle.properties 的 dshVersion，不用手填
sh build-apk.sh
```

- **预期输出** `[需真机无关·构建]`：末尾打印
  ```
  == 完成 ==
  -rw-r--r-- ... output/dsh-fcl-android-launcher-<ver>-arm64.apk
  <sha256>  dsh-fcl-android-launcher-<ver>-arm64.apk
  ```
  产物落在 `output/dsh-fcl-android-launcher-<ver>-arm64.apk`。
- 不想自己编：从 GitHub Release 页下载对应版本的 `*-arm64.apk` 附件即可。
- 本手册不触发构建（T-A5 规则：不跑 Gradle）；此节命令仅作流程说明。

---

## 2. 装机（两条路，任选）

### 路 ①：手机浏览器下 Release 附件直接装（主路径，免 adb）

1. 手机浏览器打开本仓库 GitHub Release 页，下载 `dsh-fcl-android-launcher-<ver>-arm64.apk`。
2. 点下载好的 apk → 系统弹「是否安装」→ 允许「未知来源」→ 安装。
3. 覆盖升级同理（同签名直接盖）。

- **预期现象** `[需真机]`：桌面出现启动器图标；首启进「首启前置页」（解压 rootfs）。
- 这是作者默认通道，**不依赖电脑**。

### 路 ②：`adb install`（需 USB 调试，适合反复装 + 抓日志）

**先连设备**：
```bash
sh tools/adb.sh devices
```
- **预期输出** `[需真机]`：
  ```
  List of devices attached
  <序列号>	device
  ```
  - 只看到标题行没有设备 → 手机没插好 / 没开 USB 调试（设置 → 开发者选项 → USB 调试）。
  - 设备行显示 `unauthorized` → 手机上会弹「允许 USB 调试」，勾「一律允许」再点确定。
- `[已核对·本机]`：无设备时此命令正常返回「List of devices attached」后空列表（不报错）。

**安装（`-r` 保留数据覆盖装）**：
```bash
sh tools/adb.sh install -r output/dsh-fcl-android-launcher-<ver>-arm64.apk
```
- **预期输出** `[需真机]`：最后一行 `Success`。
- 常见失败：
  - `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（签名/版本不兼容）→ 先卸载再装：
    ```bash
    sh tools/adb.sh uninstall com.dsh.fcl.androidlauncher.debug
    sh tools/adb.sh install output/dsh-fcl-android-launcher-<ver>-arm64.apk
    ```
    注意：卸载会清掉应用私有目录（含已解压 rootfs 和实例），**会触发重新解压**。
  - `INSTALL_FAILED_INSUFFICIENT_STORAGE` → 见 §4 清空间。
  - APK 约 160MB，`install` 传输 + 校验需十几秒到一分钟，属正常。

---

## 3. 抓日志

> **证据主次**：应用自己的运行日志（DshLogBus，就是 `bebug.txt` 那种 `时间戳 [tag] …` 格式）
> 是**第一证据**；logcat 主要用于「应用直接崩了 / 原生 proot/pty 层报错」这类 Java 层日志抓不到的情况。
> 记录一律按 `tools/bebug-template.md` 的格式（时间戳 + 原始行，不加工）。

### 3.1 应用内看（最省事，免 adb）

- 应用内「**设置 → 运行时自检**」：跑 `probe.sh`，对话框里应出现若干
  `dsh-probe: <项> = ok` 行，最后 `dsh-probe-ok` 代表底座自检通过。
  （字符串见 `strings.xml` 的 `dsh_setting_runtime_check` / `dsh_action_verify_runtime`。）
- 应用内「**设置 → 日志**」= 应用级视角（解压/安装/凭据）；
  「**实例详情 → 日志**」= 只看该实例的行。

### 3.2 用 run-as 直接取落盘日志文件（推荐，debug 包可用）

`fordebug` 变体是 debuggable 的，所以 `run-as` 对包 `com.dsh.fcl.androidlauncher.debug` 可用。
（fix6 起**出货包**不再是 debuggable；抓日志请装 `assembleFordebug` 出的调试包 —— 两者可并存。）
run-as 进入后工作目录是 `/data/data/<包名>/`，所以下面用相对路径 `files/dsh/...`。

```bash
# 列出所有落盘日志
sh tools/adb.sh shell run-as com.dsh.fcl.androidlauncher.debug ls -la files/dsh/logs/

# 看应用运行日志（解压/安装/启动/停止）
sh tools/adb.sh shell run-as com.dsh.fcl.androidlauncher.debug cat files/dsh/logs/runtime.log

# 看某个实例的日志（<id> 从上一条 ls 的 instance-<id>.log 里取）
sh tools/adb.sh shell run-as com.dsh.fcl.androidlauncher.debug cat files/dsh/logs/instance-<id>.log

# 导出成本地取证文件（run-as 不能直接 adb pull，用 cat 重定向）
sh tools/adb.sh shell run-as com.dsh.fcl.androidlauncher.debug cat files/dsh/logs/runtime.log > bebug-runtime-$(date +%Y%m%d-%H%M%S).txt
```
- **预期输出** `[需真机]`：`ls` 应看到 `runtime.log` 和若干 `instance-*.log`；
  `cat runtime.log` 应看到类似 `bebug.txt` 的 `HH:MM:SS  [setup]/[runtime]/[start-dsh] …` 行。
- `run-as: Package 'com.dsh...' is not debuggable` → 装错成了非 debug 变体，确认装的是 fordebug 出包。
- `[已核对·本机]`：`run-as` 是设备侧命令，无设备**无法实跑**，仅按包名/路径静态核对。

### 3.3 logcat（抓崩溃 / 原生层）

```bash
# ① 开跑前先清缓冲，排掉历史噪音
sh tools/adb.sh logcat -c

# ② 做一次完整操作（启动→对话→停止）后，按进程 PID 过滤最可靠
sh tools/adb.sh logcat --pid=$(sh tools/adb.sh shell pidof -s com.dsh.fcl.androidlauncher.debug)

# ③ 不想按 PID，就在电脑侧用关键字过滤（dsh/proot/pty/崩溃）
sh tools/adb.sh logcat | grep -iE 'dsh|proot|pty|CrashReporter|AndroidRuntime'

# ④ 只抓崩溃级（闪退后用）：
sh tools/adb.sh logcat -d AndroidRuntime:E CrashReporter:E DEBUG:F '*:S'

# ⑤ 一次性 dump 存成文件（-d 导完即退）
sh tools/adb.sh logcat -d > bebug-logcat-$(date +%Y%m%d-%H%M%S).txt
```
- **预期输出** `[需真机]`：
  - ② 应持续刷出本进程日志；崩溃时会出现 `FATAL EXCEPTION` / `AndroidRuntime` 堆栈。
  - 原生 proot/pty 问题会以 `libproot` / `ptyjni` 等关键字出现。
- 说明：应用主日志走 DshLogBus 落盘文件（见 §3.2），**不保证进 logcat**；
  logcat 里 `com.dsh` 作为**包名**无法当 tag 直接过滤，故用 `--pid=` 或电脑侧 `grep` 更稳。
  `CrashReporter` 是 fcllibrary 的崩溃 tag（`CrashReporter.java`），确实走 logcat。
- `[已核对·本机]`：`logcat -c/-d` 需设备，无设备报 `error: no devices/emulators found`，**未在真机实跑**。

---

## 4. 设备空间（跑之前必查）

当前真机 `/storage/emulated/0` 111.9G 只剩 **1.1G**（DEV-PLAN §1.2/§6）。
跑之前要清出 **≥1.7GB**（rootfs 解压 862MB + 脚本/临时 + 一个实例 node_modules 约 523MB 的裕量）。

```bash
# 查剩余空间（应用私有目录在 /data 分区；多数机型 /data 与 /storage/emulated/0 同一物理分区）
sh tools/adb.sh shell df -h /data /storage/emulated/0
```
- **预期输出** `[需真机]`：应看到类似
  ```
  Filesystem      Size  Used Avail Use% Mounted on
  /dev/block/...  112G  111G  1.1G  99% /data
  ```
  关注 `Avail` 列；低于 ~1.7G 就先去手机里清缓存/删大文件，再重跑。
- 空间不足的直接后果：解压 rootfs 中途失败、或 `npm install` 写盘失败。
- `[已核对·本机]`：`df` 需设备，**未在真机实跑**；数字 862MB/523MB 引自 DEV-PLAN，1650MB 旧常量是 K21（批 A 另修）。

---

## 5. 一次完整真机验证的推荐顺序

1. 电脑：`sh build-apk.sh` 出 `<ver>` 包（或从 Release 下载）。
2. 手机：清出 ≥1.7GB（§4）。
3. 装机：路①（浏览器）或路②（`adb install -r`）。
4. 抓日志开关：`sh tools/adb.sh logcat -c` 清缓冲（走 adb 时）。
5. 照 `tools/t6-checklist.md` 逐条做、逐条打勾。
6. 任何失败：按 `tools/bebug-template.md` 记录（§3.2 的 run-as cat 导出 + §3.3 的 logcat dump），丢回主编排。

---

## 本机核对记录（无设备，2026-10-10）

| 事实 | 核对命令 | 结果 |
|---|---|---|
| adb.exe 存在 | `ls -la "D:/Android/Sdk/platform-tools/adb.exe"` | `-rwxr-xr-x ... 8273560 ... adb.exe`（存在） |
| adb 可运行（免设备） | `"D:/Android/Sdk/platform-tools/adb.exe" version` | `Android Debug Bridge version 1.0.41 / 37.0.1` |
| 包名带 `.debug` | 读 `FCL/build.gradle.kts:72,102` | `com.dsh.fcl.androidlauncher` + `.debug` |
| 版本与产物名 | 读 `gradle.properties`、`ls output/` | `0.1.5-SNAPSHOT`，`output/dsh-fcl-android-launcher-0.1.5-SNAPSHOT-arm64.apk` 等 |
| 日志路径 | 读 `DshPaths.kt:90`、`DshLogBus.kt:262-267` | `logs/runtime.log`、`logs/instance-<id>.log` |
| 自检标志 | 读 `probe.sh:150`、`DshBootstrap.kt:369` | `dsh-probe-ok` |
| 包装脚本透传 | `sh tools/adb.sh version` / `tools\adb.cmd version` | 转发到真实 adb，输出同 `adb version`（见报告） |

**需真机才能验**（本手册中全部 `[需真机]` 标注项）：`adb devices` 列出设备、`adb install`、
`adb logcat -c/-d/--pid`、`adb shell df`、`adb shell run-as cat/ls`、`adb shell pidof/ps`，
以及 §5 的整条 T6 流程。这些在无设备环境下只能静态核对命令形态与路径，**未在真机实跑**。
