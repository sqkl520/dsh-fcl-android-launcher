# 设计：集成 oonid/pr 的 `:proot-engine`（替换自研 proot 层）

> ## 🚫 本文方案**最终没有采用**（2026-10-06 标注）
>
> 项目实际走的是**自研 proot 层 + 预打包 rootfs**（阶段 A~E-1 已于 2026-10-03 落地，
> 0.1.1 起出的包含完整 rootfs）。本文保留作**决策记录** —— 想了解"当时为什么考虑换、
> 后来为什么没换"时读它。
>
> **仍然有效**的部分：W^X 限制的分析、PROOT_LOADER 绕过原理、`ptyjni` 的来历
> （`FCL/src/main/cpp/ptyjni/` 确实取自本方案）、以及"若真机验证失败可退回来做这条路"的备选价值。
>
> **当前实现看哪里**：`PACKAGING.md`（打包）· `ROOTFS.md`（rootfs 重建）·
> `design/wx-exec-proot-loader.md`（W^X 原理）· `ROADMAP.md` M1（只差真机验收）。

> **状态**：架构决策文档，方案 B 已确定；实施按本文件 §11 分阶段推进。
> **目标优先级**：① dsh 稳定运行；② 插件 / `.node` / shell 子进程正常；③ 体积、速度、自定义性。

## 1. 最终运行时组合

```text
Debian arm64 slim + glibc
+ 官方 Node.js 22.19.x 或更高 Node 22 LTS
+ bash/npm/ca-certificates/coreutils
+ 预打包 rootfs
+ oonid/pr patched proot + PROOT_LOADER + PtyNative
+ 保留现有 DshRuntime/DshInstaller/DshInstances 业务层
```

第一版不采用 Alpine/musl、Node 自编译、首次联网下载 rootfs、自研 proot 绕过层、完整 pr-cli/OCI/多发行版管理。

## 2-10. 已有背景

W^X、PROOT_LOADER、seccomp、API、License、rootfs 与子进程风险见本文历史章节及 `wx-exec-proot-loader.md`。

## 11. 分阶段实施流程（当前执行计划）

### 阶段 A：固定底座版本与产物（当前）

1. 拉取 `oonid/pr` 源码到仓库外的参考目录，不直接覆盖 FCL。
2. 固定 commit，记录到本文件。
3. 核对以下产物的构建方式和许可证：
   - patched `proot`
   - standalone `loader`
   - `PtyNative`
   - busybox（如采用）
4. 先在沙箱中完成源码静态审查和 arm64 构建可行性检查。
5. **本阶段不改 dsh 业务代码、不打包 APK。**

### 阶段 B：制作可复现 Debian rootfs（进行中）

已完成第一版 rootfs 构建：

```text
Debian 12.15 arm64
Node.js v22.23.3 / npm 10.9.9
bash 5.2 / coreutils / ca-certificates / curl / xz / procps
@deepseek-ai/dsh 0.1.6-alpha.2
14 个 .node 原生模块存在
rootfs.tar.xz：279MB
sha256：16badbfc94112218a98c684ac88734c96303ff4b50cf4260f7e83204b98e1e77
```

基础验证已通过：`node --version`、`npm --version`、`bash --version`、`child_process.execSync`、
`child_process.spawnSync('/bin/bash')`、`dsh --help`。

后续仍需在 patched proot + Android 真机环境复验 `.node` 加载和 dsh web。

1. 选择 Debian arm64 slim，固定发行版快照/版本。
2. 安装 bash、npm、ca-certificates、coreutils、tar、xz、procps 等基础工具。
3. 放入官方 Node.js 22.19.x 或更新的 Node 22 LTS arm64 二进制。
4. 检查：
   ```bash
   node --version
   npm --version
   bash --version
   /usr/bin/env
   ```
5. 在 rootfs 中固定安装 dsh 版本，记录 `dsh` 包版本和 lock/依赖摘要。
6. 检查 dsh 运行所需 `.node` 原生模块是否完整。
7. 打包为 `rootfs.tar.xz`，校验内容布局必须直接包含 `bin/sh` 或 `usr/bin/env`。

### 阶段 C：宿主侧最小运行链

1. 将 `proot` / `loader` / `PtyNative` 接入 arm64 构建产物。
2. `proot` 与 loader 放 `jniLibs/arm64-v8a/`；rootfs 放预打包 assets。
3. 设置 `PROOT_LOADER=nativeLibraryDir/libproot-loader.so`。
4. 优先改造 `DshBootstrap` / `DshPaths` / `ProotCommand`，保留实例目录、workspace、home 和插件目录。
5. 不引入完整 OCI/多发行版 UI。

### 阶段 D：子进程与插件验收

按顺序测试：

```bash
node -e "require('child_process').execSync('echo child-ok')"
node -e "require('child_process').spawnSync('/bin/bash', ['-lc', 'echo shell-ok'])"
npm install
```

然后测试：

- `dsh web`
- dsh plugin 加载
- `.node` 原生模块 `dlopen`
- shell tool / bash tool
- agent 执行命令
- npm 安装插件
- 多实例隔离
- 息屏保活与前台服务

### 阶段 E：Android 真机验收

1. targetSdk 34 安装启动。
2. 首次 rootfs 解压、断点/失败恢复、磁盘不足提示。
3. dsh Web UI、token/cookie、DeepSeek API。
4. 子进程、插件、`.node`、shell tool。
5. 运行中旋转策略（当前全锁横屏）、息屏、返回、通知停止。
6. 失败后查看日志并重新启动。

### 阶段 F：稳定后再优化

只有 A-E 全部通过后，才考虑：

- rootfs 压缩与解压速度
- npm cache 复用
- APK 体积
- Alpine/musl 试验
- 可下载 rootfs
- 多发行版/OCI
- 更细的 runtime 自定义设置

## 12. 当前实施状态

- 方案 B 组合已确定。
- **阶段 A 进行中**：已拉取 `oonid/pr` 并固定 commit。
- 运行时仍未真机跑通；`assets/dsh/rootfs/` 当前是 PLACEHOLDER，不能把失败当作代码故障。

## 13. 固定来源（阶段 A 成果）

| 项 | 值 |
|---|---|
| 仓库 | <https://github.com/oonid/pr> |
| commit | `fcf25cb2396361f0be2edfc96fdd61a6e738c9d9` |
| 本地参考副本 | 仓库内 `oonid-pr-reference/`（不参与 FCL 构建） |

已接入项目的产物（随 APK 的 `jniLibs/arm64-v8a/` 提供）：

| 文件 | 大小 | sha256 |
|---|---|---|
| `libproot.so` | 2665552 | `67a51b200d3804af361e4df934280519cd49135dcbc30c595d3e3ec70284529a` |
| `libproot-loader.so` | 18184 | `bb6a367bdce77a5778f8462b2f0fb46d03bf3c1ef4779ff463b06339ab08f607` |
| `libbusybox.so` | 1115944 | `e383c8bc25a1137b8ee88718cc6df1f1e84c54521d6045fc837385995dcdf031` |

`libpr-cli.so`（Rust CLI，3390760 B）**暂未接入**：第一版 rootfs 预打包、沿用现有 `DshInstaller`，
不需要 `pr-cli` 的发行版/OCI 管理。

自带源码（随本项目编译）：

- `src/main/cpp/ptyjni/ptyjni.c` —— PTY 原生桥接（MIT，来自 `:proot-engine`）
- `src/main/java/com/dsh/core/PtyNative.kt` —— 对应 Kotlin 接口

编译验证：`run-compile.sh` 现已包含原生 ptyjni 构建检查（`libptyjni.so`，36440 B）。

