# APK 归档

不同版本按版本号分别存放，作为**版本快照**（可回滚）：

```text
apk-archive/
└── <version>/
    ├── dsh-fcl-android-launcher-<version>-arm64.apk
    └── SHA256SUMS
```

## 与「交付目录」的关系

| 位置 | 用途 | 是否入 Git |
|---|---|---|
| `<工作区>/output/` | **交付 / 取件**（设备端可访问），只放当前版本 | 否（工作区文件） |
| `<仓库>/apk-archive/<version>/` | **版本快照**（保留历史版本，便于回滚） | 二进制否；`SHA256SUMS`/`README.md` 是 |
| `<仓库>/FCL/build/outputs/apk/` | Gradle 原始产物；`build-apk.sh` 会把它**移动**到 `output/`，不留第三份 | 否 |

`build-apk.sh` 会自动完成三件事：打包 → 落 `output/` → 存快照到本目录并写 `SHA256SUMS`。

版本变更事实记录在 `docs/CHANGELOG.md`；打包约定见 `docs/PACKAGING.md`。
