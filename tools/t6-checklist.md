# 批 A 真机门 · 逐条打勾表（T6 / DEV-PLAN §6）

> 用途：批 A「能点亮」的真机验收门。**五条全过**才算批 A 真机通过。
> 判据照 DEV-PLAN §6「真机门 A 清单」。装机/抓日志的具体命令见 `tools/ondevice.md`。
> 失败按 `tools/bebug-template.md` 记录（时间戳 + 原始输出，不加工），丢回主编排开修复任务。
>
> 填写：`通过`栏打 ✅/❌；`现象`栏贴**原始**日志行/截图路径，不要转述加工。
> 命令里的包名固定为 `com.dsh.fcl.androidlauncher.debug`；`<ver>` 用实际出包版本。

**本轮验证信息（填写）**
- APK 版本 `<ver>`：________　来源：□ `sh build-apk.sh` 自编　□ GitHub Release 下载
- 设备型号 / Android 版本：________
- 装机前 `df` 可用空间（需 ≥1.7GB）：________　（命令：`sh tools/adb.sh shell df -h /data`）
- 日期 / 验证人：________

---

## 门 1 · 首启解压完成 + 自检 `dsh-probe-ok`

- **操作**：全新安装后首启，走完「首启前置页」的解压 rootfs；再进「设置 → 运行时自检」。
- **判据**：解压无中断；自检对话框最后一行出现 `dsh-probe-ok`（前面各 `dsh-probe: <项> = ok`）。
- **怎么验**：
  - 应用内：设置 → 运行时自检，看对话框文字。
  - 落盘：`sh tools/adb.sh shell run-as com.dsh.fcl.androidlauncher.debug cat files/dsh/logs/runtime.log`
    里应有解压完成与 probe 相关行。

| 通过 | 现象（原始行 / 截图路径） |
|---|---|
| ⬜ | |

---

## 门 2 · 装 dsh 一个版本 → READY

- **操作**：进「版本/下载」页，装一个版本（预装基线 `0.1.6-alpha.2`，或下载页里的 `0.2.x`）。
- **判据**：实例状态走到 **READY**（已安装、可启动）；任务区该行落到「完成」。
- **怎么验**：
  - 应用内：实例列表该实例显示就绪态。
  - 落盘：`... cat files/dsh/logs/instance-<id>.log` 应见 `DONE version=<v> source=instance`
    与 `node_modules 体积: ...M`（参考 `bebug.txt` 第 18-22 行）。

| 通过 | 现象（原始行 / 截图路径） |
|---|---|
| ⬜ | |

---

## 门 3 · 启动 → token URL → WebView 出界面 → 真实 Key 对话一次

- **操作**：对 READY 实例点「启动」；等 WebView 加载；在 dsh 界面 → 设置 → 模型里填**真实 API Key**，发一句对话。
- **判据**：
  - 日志抓到 `READY url=http://127.0.0.1:<port>/?token=***`；
  - WebView 正常出界面（非白屏/非错误页）；
  - 用真实 Key **成功对话一次**（有模型回复）。
- **怎么验**：
  - 落盘：`... cat files/dsh/logs/instance-<id>.log` 找 `READY url=` 行（参考 `bebug.txt` 第 34-37 行）。
  - 崩溃时：`sh tools/adb.sh logcat -d AndroidRuntime:E CrashReporter:E '*:S'`。
- **注意**：`bebug.txt` 里历史问题是**当时无 API Key**（第 23 行「未配置可用 API Key」），对话从未成功过——本门就是补这一刀。

| 通过 | 现象（原始行 / 截图路径） |
|---|---|
| ⬜ | |

---

## 门 4 · 停止 → 通知消失 → 再次启动不报 EADDRINUSE

- **操作**：停止运行中的实例；确认前台通知消失；**再次启动同一实例**。
- **判据**：
  - 停止后前台服务通知消失；
  - 再次启动**不报** `EADDRINUSE`（端口被占）——这是历史最大坑（`bebug.txt` 第 60-85 行）。
- **怎么验**：
  - 落盘：再次启动的 `instance-<id>.log` 不应出现 `EADDRINUSE: address already in use`。
  - 进程层：停止后确认无孤儿 node 占端口：
    `sh tools/adb.sh shell run-as com.dsh.fcl.androidlauncher.debug ps -A 2>/dev/null | grep -i node`
    （看不到残留 node 为佳；`run-as ps` 受限时改用门 5 的 `ps -A | grep npm`）。
- **注意**：历史现象是 node 没被杀掉、二次启动端口冲突（关联缺陷 K01/K02，批 A 另修）。

| 通过 | 现象（原始行 / 截图路径） |
|---|---|
| ⬜ | |

---

## 门 5 · 取消一次安装 → 「已取消」终态 + 无孤儿 npm

- **操作**：开始装一个较大版本（如 `0.2.0-rc.2`，约 540 包 3 分钟），安装进行中点「取消」。
- **判据**：
  - 任务区该行变成 **「已取消」**终态（不是「安装失败」，也不是瞬间消失）；
  - 取消后**没有孤儿 npm 进程**残留。
- **怎么验**：
  - 应用内：任务区看终态文字 + 图标（取消 ≠ 停止，两张不同图标）。
  - 进程层（关键）：
    ```bash
    sh tools/adb.sh shell ps -A 2>/dev/null | grep -iE 'npm|node'
    ```
    **预期：空**（无输出）。若仍列出 npm/node → 有孤儿（关联缺陷 K01，批 A 修）。
    （部分机型 `ps` 需 `-A`/`-e`；`toybox ps` 默认只列当前 shell，务必加 `-A`。）

| 通过 | 现象（原始行 / 截图路径） |
|---|---|
| ⬜ | |

---

## 汇总

- 五门结果：门1 ⬜　门2 ⬜　门3 ⬜　门4 ⬜　门5 ⬜
- 批 A 真机门：**□ 通过（五门全 ✅）　□ 未通过**
- 失败清单（按 `tools/bebug-template.md` 另附原始日志）：________
