# 真机实测记录（2026-11-23 / fix40）

> 12 个 agent 并行做架构锐评期间的真机测试。只做可逆操作。

## 时间点与前置状态

| 项 | 值 |
|---|---|
| 时间 | 2026-11-23 03:20~03:45 |
| 设备 | KKG-AN00 / Android 10 / API 29 / 内核 4.14.141+ |
| 版本 | 0.1.6-SNAPSHOT-fix40（code 146） |
| 磁盘 | 98G/112G 已用（88%） |

---

## P1 · 探针缓存机制 ✅ 通过

**验的**：`start-dsh.sh` 的三个探针（sandbox/perf/preset）是否按 `PROBE_REV` 缓存、不重复跑。

**实测**：

启动实例后，日志里**没有** `sandbox-probe: start` / `perf-probe: start` / `preset-probe: start` 任何一行。

这说明 `PROBE_REV=3` 的缓存判据生效了（上一轮 fix40 首跑时写入缓存，7 天内不再重探）。

**同时确认还在正常工作的**：
- `[runtime] cleared 2 stale session lock(s) before start` —— 会话锁清理逻辑跑着
- `[start-dsh] inode shim : /opt/dsh/scripts/proot-inode-shim.cjs` —— inode shim 加载着

---

## P2 · 会话锁 ✅ 通过（这次真发成功了）

### 怎么绕过去的

之前卡在拿 token。最后成功的路径是**华为浏览器**（不是 Edge）：

1. App 里点「复制访问链接」→ 确认
2. 打开华为浏览器 → 长按地址栏 → 全选 → 点「**粘贴并进入**」
3. 进入 dsh（完整 token 生效）

**关键差异**：华为浏览器长按地址栏会出现「粘贴并进入」这个**一步到位**的按钮；
Edge 没有（它给的是历史建议 + 需要二次确认的粘贴浮层）。

### 验证结果

| 步骤 | 结果 |
|---|---|
| 输入「hi」 | ✅ 正常进输入框（`input text` + 回车确认候选词可行） |
| 点发送 | ✅ 消息发送成功 |
| **模型响应** | ✅ **9 秒完成，完整回复** |
| 会话状态 | ✅ 底部 `1 轮 1 步 · 622 tokens · 9.9K tok 缓存命中` |

**回复内容**确认了工作区正确：
> We're currently in `/opt/dsh/instances/inst-1795013784782-1-ab52/workspace/test`,
> and I have full access to that workspace.

**证据**：`docs/reports/round49/SESSION-WORKS.png`

### 结论

**BL-247 的会话锁修复（proot inode shim）在真实使用下有效。**
不只是"能发出去"，而是完整走通了「发送 → 模型推理 → 回复渲染」全链路。

---

## P2b · BL-225 第三次复现（🔴 仍未修）

**操作**：实例运行中 → 实例详情 → 运行页 → 点「在浏览器中打开」。

**结果**：Edge 打开 `127.0.0.1:38501`（**没有 query，连 `http://` 都没了**），页面显示：

```
dsh web authentication required; reopen the URL printed by dsh web.
```

**证据**：`docs/reports/round49/BL225-reproduced.png`

**代码侧已核实**：`DshInstanceSettingPage.openInBrowser()` 用的是
`Intent(ACTION_VIEW, Uri.parse(url))`，**URL 本身是完整的**。
所以问题在 Edge 收下 Intent 之后的行为，不在我们这边。

**但这仍然是用户可见的缺陷**：按钮存在、点了、结果是 401。

### 「复制链接」后到浏览器粘贴也不顺

| 浏览器 | 长按地址栏的行为 |
|---|---|
| **Edge（横屏）** | 只给历史建议；再长按出现「粘贴」浮层，但点它弹的是 URL 补全 |
| **Edge（竖屏）** | 同左 |
| **华为浏览器** | 长按 → 全选 → 「**粘贴并进入**」一步到位 ✅ |

**结论**：从 App 到外部浏览器的路径，**依赖用户装的是哪个浏览器**。
这是个该在文档里说明、或者该在 App 里改提示的问题。

---

## P3/P4/P5 未执行

P2 通过后时间已经用掉较多（03:20~04:15），且 12 个 agent 随时可能返回结果。
P3（编辑器边界）、P4（卸载痕迹）、P5（长时间运行）留到下一轮。

---

## 追加发现（顺手记）

### fix40 的 setup 脚本版本号逻辑有效

`scripts/version = 23`，设备上的脚本随之更新（探针代码能跑就是证明）。
这条链路（assets → 设备）是通的。

### 磁盘 88% 已用需要留意

14G 剩余。dsh 的 npm install 需要数百 MB，rootfs 解压要 ~900MB。
如果用户磁盘再紧张，安装会失败。**这个 warning 阈值该不该在 App 里提示**，值得评估。
