# dsh 运行时底座 assets（速查）

> **完整打包说明见仓库文档目录的 `docs/PACKAGING.md`。**
> 本文件只保留"这里有什么、动哪个文件会触发重解压"的最小提示，方便只拿到 `FCL/` 源码的人也能看懂。

本目录在 App 首启时被 `com.dsh.core.DshBootstrap` 解压到 App 私有目录（`<filesDir>/dsh`），
运行时通过 `--bind` 暴露成 rootfs 内的 `/opt/dsh`。

| 放什么 | 放哪 | 备注 |
|---|---|---|
| proot 主程序 + loader（arm64） | **已就绪**：`FCL/src/main/jniLibs/arm64-v8a/libproot.so`、`libproot-loader.so`（busybox 为 `libbusybox.so`） | 系统安装 App 时自带执行位，最稳。**不要**再往 `dsh/proot/` 放二进制（见下） |
| rootfs 压缩包 | 本目录下 `rootfs/rootfs.tar.xz` | 实测在用的是 **Debian 12 bookworm arm64 + Node 22 + 预装 dsh**（142 MB，不入 Git）；包内必须是"根"的形态（顶层就是 `bin/`、`usr/`…） |

## `scripts/`（已就绪，无需你处理）

| 文件 | 作用 |
|---|---|
| `setup-node-dsh.sh` | 装 dsh；打印 `[setup] STAGE=...` 供界面显示进度，装完硬校验 |
| `start-dsh.sh` | 启动 dsh；打印 `READY url=...` / `FAILED reason=...`；信号转发；支持 `PORT=0` |
| `probe.sh` | 自检探针，`DshBootstrap.verify` 调用，必须打印 `dsh-probe-ok` |
| `launcher-assets.mjs` | **dsh 插件**（不是 shell 脚本）：给 `/assets` 下的前端静态资源补 `cache-control: immutable`，否则外部浏览器每次打开都重下整个前端。由 `start-dsh.sh` 通过 `--patch` 挂上 |
| `version` | scripts 子项版本（**当前 12**） |

以上 `.sh` 严格 POSIX sh（真机由 proot 里的 `/bin/sh` 执行，`dash` 兼容）；
`.mjs` 由 rootfs 里的 Node 加载。**行尾必须 LF**（见 `docs/ENVIRONMENT.md` §1.3）。

## `--patch` overlay（启动器自己那一层）

启动时会往**实例目录**写一份 `launcher.patch.yml`（JSON 内容，JSON 是合法 YAML），
由 `start-dsh.sh` 用 `dsh web --patch <该文件> --no-open ...` 传进去：

- 内容由 `com.dsh.core.DshInstances.launcherPatchOverlayJson()` 生成 —— 一条把
  `launcher-assets` 插件挂上，一条把开发用热更新轮询 `client-hmr.pollIntervalMs` 从 500 调到 5000；
- **不写** `$DSH_HOME/cordis.patch.yml`：那是 dsh 定义的用户层，会对用户的其它 profile 生效；
- 原子写（先 `.tmp` 再 rename），半截文件会让 dsh 直接启动失败；
- **失败不影响启动**：写不出来 / 文件不存在时脚本就不传 `--patch`；带 `--patch` 却在就绪前退出时，
  脚本会去掉它自动重试一次。

## 版本与重解压

改 `scripts/version`、`rootfs/version` 里对应的那个值，即可让已装设备**只重解这一项**
（两份文件格式不同：`scripts/version` 是递增整数，`rootfs/version` 是版本串
`debian-bookworm-arm64-node22-dsh0.1.6-alpha.2-slim1`，按**字符串**比较）。

**为什么不推荐把 proot 放 assets**：targetSdk ≥ 29 起，App 私有数据目录里的可执行文件普遍受
W^X/SELinux 限制，多数新机型上会 exec 失败。启动器会在预检阶段直接提示改用 jniLibs 方案，
而不是静默失败。

细节（自检流程、密钥与令牌约定、排查方法）见 `docs/PACKAGING.md`。
