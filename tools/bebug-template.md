# 真机日志记录模板（bebug）

> 固化 `bebug.txt` 的记录风格：**时间戳 + 原始输出行，一个字都不加工**。
> 复制下面「记录块」到一个新文件（建议命名 `bebug-<ver>-<日期>.txt` 或贴进反馈），逐段填。
>
> **铁律（照抄自 DEV-PLAN §6 收尾）**：
> - 原样粘贴，**不要**转述、翻译、截断、重排、「帮忙总结」。日志里的中文/英文/堆栈原样留。
> - token 已被应用脱敏成 `?token=***`，保持原样即可，不要反查真实 token。
> - 一次失败 = 一个记录块；多次就多个块，别混在一起。

---

## 怎么取「原始行」（命令见 `tools/ondevice.md`）

- 应用运行日志（首选，等同 `bebug.txt` 来源）：
  `sh tools/adb.sh shell run-as com.dsh.fcl.androidlauncher.debug cat files/dsh/logs/runtime.log`
- 某实例日志：
  `sh tools/adb.sh shell run-as com.dsh.fcl.androidlauncher.debug cat files/dsh/logs/instance-<id>.log`
- 崩溃 / 原生层（Java 日志抓不到时）：
  `sh tools/adb.sh logcat -d > bebug-logcat-<日期时间>.txt`
- 懒人路径：应用内「设置 → 日志」/「实例详情 → 日志」长按复制。

> 原始行形如（来自 `bebug.txt`，`HH:MM:SS␠␠[tag] 内容`）：
> ```
> 05:24:17  added 540 packages in 3m
> 05:24:17  [setup] DONE version=0.2.0-rc.2 source=instance
> 05:36:57  Error: listen EADDRINUSE: address already in use 127.0.0.1:34725
> ```

---

## 记录块（复制这一段，逐次填写）

```
=== bebug 记录块 ===
版本(ver)   : <如 0.1.6-SNAPSHOT>
设备/系统   : <机型 / Android 版本>
日期时间    : <YYYY-MM-DD HH:MM 本地时区>
验证人      : <谁>
对应门/场景 : <t6-checklist 门1~5 的哪一条，或自由描述>
操作步骤    : <第几步做了什么，一句话>
现象(一句)  : <失败/异常的一句话概括；原始日志在下面>
来源        : <runtime.log | instance-<id>.log | logcat | 应用内复制>
空间(df)    : <当时 /data 可用，如 1.1G>

--- 原始输出开始（逐行照贴，不加工）---
<把 cat / logcat 的输出整段贴这里，保留原始时间戳与换行>
--- 原始输出结束 ---

补充截图    : <截图文件路径，可选>
```

---

## 填好的样例（取自 `bebug.txt` 的真实历史，示范「不加工」）

```
=== bebug 记录块 ===
版本(ver)   : 0.1.2-SNAPSHOT（旧 rootfs，历史）
设备/系统   : 作者真机（Android）
日期时间    : 2026-10-05 05:36 本地
验证人      : 作者
对应门/场景 : 门4（停止后再次启动）
操作步骤    : 启动 0.2.0-rc.2 → 停止 → 再次启动同一实例
现象(一句)  : 二次启动报 EADDRINUSE，webserver 插件起不来（停止没杀干净 node）
来源        : runtime.log
空间(df)    : 未记录

--- 原始输出开始（逐行照贴，不加工）---
05:36:44  [runtime] 启动 dsh 0.2.0-rc.2（dsh 0.2.0-rc.2，profile=web，端口=34725）
05:36:57  dsh: startup failed: 2 required plugins did not activate
05:36:57    webserver (required)
05:36:57      Error: listen EADDRINUSE: address already in use 127.0.0.1:34725
05:36:57  [start-dsh] FAILED reason=node-exit code=1
--- 原始输出结束 ---

补充截图    : （无）
```
