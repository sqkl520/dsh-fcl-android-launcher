# dsh 运行时底座 assets（占位说明）

> **完整打包说明见仓库文档目录的 `docs/PACKAGING.md`。**
> 本文件只保留"在这里放什么"的最小提示，方便只拿到 `FCL/` 源码的人也能看懂。

本目录在 App 首启时被 `com.dsh.core.DshBootstrap` 解压到 App 私有目录。需要你补齐**两类平台大文件**：

| 放什么 | 放哪 | 备注 |
|---|---|---|
| proot 主程序 + loader（arm64） | **推荐**：`FCL/src/main/jniLibs/arm64-v8a/`，命名为 `libproot.so`、`libproot_loader.so` | 系统安装 App 时自带执行位，最稳。放这里就不必再放 `proot/` 目录 |
| rootfs 压缩包 | 本目录下 `rootfs/rootfs.tar.xz` | 精简 Ubuntu/Alpine（arm64），**建议内置 Node 22/24**；包内必须是"根"的形态（顶层就是 `bin/`、`usr/`…） |

已就绪、无需你处理的部分：`scripts/`（`setup-node-dsh.sh` / `start-dsh.sh` / `probe.sh`，严格 POSIX sh）。

放入后把对应子目录的 `version`（`proot/version`、`rootfs/version`）改成更大的整数，即可触发重新解压。

**为什么不推荐把 proot 放 assets**：targetSdk ≥ 29 起，App 私有数据目录里的可执行文件普遍受
W^X/SELinux 限制，多数新机型上会 exec 失败。启动器会在预检阶段直接提示你改用 jniLibs 方案，
而不是静默失败。

细节（自检流程、密钥与令牌约定、排查方法）见 `docs/PACKAGING.md`。
