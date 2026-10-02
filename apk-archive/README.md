# APK 归档

不同版本的 APK 按版本号分别存放：

```text
apk-archive/
└── <version>/
    ├── dsh-fcl-android-launcher-<version>-arm64.apk
    └── SHA256SUMS
```

说明：APK 二进制默认不提交到 Git，归档目录用于本地/工作区保存不同版本产物；版本变更事实记录在 `docs/CHANGELOG.md`。
