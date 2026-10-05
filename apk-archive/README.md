# APK 归档

本目录**只存指纹，不存二进制** —— 每个版本一个 `SHA256SUMS`，记录"这个版本出过、指纹是多少"。

```text
apk-archive/
├── 0.1.0/SHA256SUMS
├── 0.1.1-SNAPSHOT/SHA256SUMS
├── 0.1.2-SNAPSHOT/SHA256SUMS
└── README.md
```

## APK 本体在哪

| 版本 | 位置 |
|---|---|
| **0.1.2-SNAPSHOT 及以后** | **GitHub Release 附件**：<https://github.com/sqkl520/dsh-fcl-android-launcher/releases> |
| 0.1.0 / 0.1.1-SNAPSHOT / legacy | 快照仓 `apk/<版本>/` 的分片（`sh apk/reassemble.sh` 拼回）—— **历史存档，不再新增** |

> **为什么改**：APK 是 300MB+ 的二进制，Git 存的是"每个文件的每个版本" ——
> 改一行代码就是一个全新的 300MB 对象，旧的还永远留着。旧做法（切 90MB 分片提交）
> 实测已在快照仓历史里压了 **833 MiB**，每出一版再涨 300MB+。
> 分发走 Release 附件后，仓库历史不再增长，手机端也能免 token 直接下载。

## 交付目录

| 位置 | 用途 | 是否入 Git |
|---|---|---|
| `<项目文件夹>/output/` | **交付 / 取件**（设备端可访问），只放当前版本 | 否 |
| `apk-archive/<version>/SHA256SUMS` | 版本指纹记录 | **是** |
| `FCL/build/outputs/apk/` | Gradle 原始产物；`build-apk.sh` 会**移动**到 `output/`，不留第三份 | 否 |

出包一条命令搞定：`release.cmd`（电脑）或 `sh release.sh`（手机）—— 见根下 `REPOS.md` §5。
版本变更事实记录在 `docs/CHANGELOG.md`；打包约定见 `docs/PACKAGING.md`。
