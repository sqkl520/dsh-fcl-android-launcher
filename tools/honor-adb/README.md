# tools/honor-adb —— 让 Windows 认荣耀手机的 ADB 接口

> 用途：荣耀/华为手机插上后只认 MTP，`adb devices` 永远是空的。
> 根因是系统里**没有 VID_12D1 的驱动**（设备管理器里 `HDB Interface` 报 `CM_PROB_FAILED_INSTALL`）。

## 背景（2026-10-31 实测）

在 HONOR X10 Max / KKG-AN00 / Android 10 上遇到：

```
HDB Interface          Status: Error      ← CM_PROB_FAILED_INSTALL
Remote NDIS            Status: OK         （网络共享，正常）
USB Composite Device   Status: OK         （MTP 走这条，所以文件能传）
```

- MTP 传文件能用，`adb devices` 为空
- `bcdedit /set testsigning on` + 自改 INF 能强行绑驱动，但**要重启两次**
- 系统自带的 `winusb.inf` 没有 VID_12D1 的匹配条目
- Google USB Driver（`usb_driver_r13-windows.zip`）只认 `VID_18D1`（Google 原生），**不认华为/荣耀**

## 官方解法（推荐，不用重启）

1. 从荣耀官网下 **Honor Suite**（约 46 MB）：
   <https://www.honor.com/global/support/suite/>
   直链示例（2026-09 版）：`https://iknow.service.hihonor.com/weknow/servlet/download/public?contextNo=W00066136`
   解包后是 `Software/HonorSuite_11.0.0.738_OVE.exe`
   **验证签名**：签发者必须是 `Honor Device Co., Ltd.`
   ```powershell
   (Get-AuthenticodeSignature 'HonorSuite_11.0.0.738_OVE.exe').Status   # 应为 Valid
   ```
2. 正常安装。装完可以不让它常驻——**它只是用来装驱动的**。
3. 手机上开：`设置 → 关于手机 → 连点 7 次版本号` → 开发人员选项
   → **USB 调试** + **仅充电模式下允许 ADB 调试**
4. 插上手机 → 顶部下拉选「传输文件」→ 手机上点「允许 USB 调试」（勾「始终允许」）
5. `sh tools/adb.sh devices` 应该能看到设备

## 备选：强绑驱动（需重启两次）

`tools/honor-adb/` 里那两个 `.cmd` 是这条路（本机实测到"驱动入库失败"就停了，
因为改过的 INF 与 `.cat` 哈希对不上，需要 `testsigning`）。**一般用不上，官方驱动足够。**

- `install-honor-adb.cmd` —— 第一次重启**之后**跑（管理员）
- `finish-honor-adb.cmd` —— 确认 adb 能用之后跑，关闭测试模式再重启一次

## 装完之后

```bash
sh tools/adb.sh devices          # 确认能看到设备
sh tools/e2e-device.sh           # 跑真机自动化
```

注意：`e2e-device.sh` 里读日志那组靠 `run-as`，**出货包（非 debug）用不了**，会标 `[SKIP]`。
