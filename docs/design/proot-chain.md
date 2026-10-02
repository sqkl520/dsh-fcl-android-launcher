# 设计：可复现的 proot 启动链（dsh on Android via proot）

目标：在未 root 的安卓手机上，用 FCL 同款的 proot + rootfs 技术，把 DeepSeek Harness(`dsh`)
一键跑起来。本目录是"能在手机 proot 里起 dsh"这条主链路的脚本化 + 选型说明。

> 结论先行：**这条链路已在等价环境（aarch64 + proot + glibc/Ubuntu24.04 + Node22.23）端到端实测通过**——
> dsh 的 arm64 原生模块正常加载、`dsh web` 返回完整 Web UI(HTTP 200)、headless 用真实 DeepSeek key
> 调 `deepseek-flash` 成功返回。见文末"实测证据"。

---

## 1. rootfs 选型

dsh 的原生模块只发布了 `linux-arm64` 的 **glibc** 和 **musl** 两种产物（无 android/bionic），
所以 rootfs 必须是标准 Linux 发行版，二选一：

| 维度 | Debian/Ubuntu (glibc) | Alpine (musl) |
|---|---|---|
| 命中 dsh 原生产物 | ✓ glibc/system.node | ✓ musl/system.node |
| rootfs 基础体积 | ~30–80MB | ~5–8MB |
| Node 获取 | NodeSource 有现成 22.x | apk 源，需留意版本够不够新(^22.19) |
| 兼容性/踩坑概率 | 低（生态最主流） | 中（musl 偶有原生模块兼容问题） |
| 实测状态 | **已验证** | 未实测（原生包已确认随附 musl 版） |

**推荐默认走 Debian/Ubuntu(glibc)**：兼容性最稳，我已完整验证。
**追求极致体积**再考虑 Alpine(musl)，但要自己确认 apk 的 Node ≥ 22.19，且多测原生模块。

体积预算参考（实测）：单个 dsh 版本的 `node_modules` ≈ **303MB**（190 个顶层包）。
一个实例（rootfs + Node + 一个 dsh 版本）大致 **400–600MB**。多版本时用独立目录隔离并提供清理入口。

---

## 2. 引导链（App 视角的三层）

```
[安卓 App / native 层]
   └─(1) proot-run.sh  ← 用 proot 把 rootfs 兜成新根 (FCL 同款技术)
          └─[进入 rootfs 内部]
               ├─(2) setup-node-dsh.sh  ← 首次/装新版本时：备好 Node + 装指定版 dsh
               └─(3) start-dsh.sh       ← 每次启动：起 dsh web，打印带 token 的本地 URL
                        └─ http://127.0.0.1:3080/?token=XXXX
                             └─[安卓 WebView 加载这个 URL] ← 完整聊天/编码界面
```

- **(1) proot-run.sh**：设备侧包装器，等价于启动器 native 层要做的事。绑定 `/dev /proc /sys`、
  伪装 root(`-0`)、`--link2symlink`(绕开安卓 fs 无硬链接)。
- **(2) setup-node-dsh.sh**：在 rootfs 内确保 Node 满足 `^22.19||>=24`，并把
  `@deepseek-ai/dsh@<版本>` 装进独立实例目录（多版本隔离的基础）。
- **(3) start-dsh.sh**：在 rootfs 内启动 `dsh web`。stdout 会打印
  `http://127.0.0.1:3080/?token=XXXX`，**启动器要捕获这行、把 URL 交给 WebView**。

---

## 3. 快速使用

真机（Termux 或 App native 层）：
```sh
# 一次性：进入 rootfs 装好 Node + dsh
PROOT=./proot ROOTFS=./ubuntu-arm64 \
  ./scripts/proot-run.sh /opt/dsh/scripts/setup-node-dsh.sh
#  ↑ 其中 INSTANCE_DIR/DSH_VERSION 通过环境变量传入

# 把 key 写进实例的 credentials.env（权限 600），再启动
PROOT=./proot ROOTFS=./ubuntu-arm64 \
  ./scripts/proot-run.sh /opt/dsh/scripts/start-dsh.sh
```

rootfs 内部直接测（本仓库脚本就是这么验证的）：
```sh
INSTANCE_DIR=/opt/dsh/instances/default DSH_VERSION=latest ./scripts/setup-node-dsh.sh
echo 'DEEPSEEK_API_KEY=sk-xxxx' > /opt/dsh/instances/default/credentials.env && chmod 600 $_
INSTANCE_DIR=/opt/dsh/instances/default PORT=3080 ./scripts/start-dsh.sh
```

---

## 4. 关键坑（都已实测确认）

1. **`--expose-internals` 必须直接传给 node**：默认 web profile 的 `dsh-hmr` 插件要求它，
   且该 flag **不允许放进 `NODE_OPTIONS`**（会报 `--expose-internals is not allowed in NODE_OPTIONS`）。
   所以脚本里不能用 `.bin/dsh` 软链，而是 `node --expose-internals .../bin.js web`。
   备选：用 `headless`/`sdk` profile（默认关 HMR），或用 `cordis.patch.yml` 关掉 `dsh-hmr`。
2. **token → cookie 鉴权**：首次访问 `/?token=XXXX` 会 302/303 跳转并 set-cookie，之后靠 cookie。
   - 带 cookie（浏览器/WebView 默认行为）→ **200**
   - 不带 cookie 直接请求 `/` → **401**
   WebView 默认存 cookie，所以只要加载一次带 token 的 URL 就能进，无需额外处理。
3. **绑 127.0.0.1，别绑 0.0.0.0**：本地 WebView 自用，绑回环避免暴露到局域网。
4. **Node 版本卡 `^22.19 || >=24`**：rootfs 里 Node 必须够新，setup 脚本会校验。
5. **Landlock 沙箱多半 unusable**：proot + 多数安卓内核测不到可用 Landlock，隔离会退化。
   自用+审批弹窗可接受，别拿它跑不可信任务（dsh 官方 SAFETY 亦如此声明）。

---

## 5. 安全

- **API key**：只从实例目录的 `credentials.env`(权限 600) 读取，脚本不硬编码、不打印。
  安卓端建议存 Android Keystore，启动时再写入 rootfs 内的临时凭据文件。
- **保活**：安卓会杀后台，需前台服务(Foreground Service)+常驻通知维持 `dsh web` 进程。

---

## 6. 实测证据（本次 PoC）

环境：`aarch64` / `Ubuntu 24.04` / `glibc 2.39` / **proot**（≈手机上 FCL rootfs 形态）/ `Node v22.23.2`。

- `npm i @deepseek-ai/dsh` 自动拉到 arm64 原生包：
  `@deepseek-ai/node-addon-system-linux-arm64`(含 `glibc/system.node`+`musl/system.node`+`landlock-run`)、
  `koffi-linux-arm64`(glibc+musl)、`node-pty` linux-arm64、`sharp-linux-arm64`。
- 直接 `require` glibc 的 `system.node` → 成功（导出 `tryLock`）；`node-pty` → 成功。
- `dsh web` → 打印 `http://127.0.0.1:3080/?token=...`；带 token+cookie 访问 → **HTTP 200，27KB 完整 Web UI**（真实 `dsh-client-ui-*` 前端模块在预加载）。
- `dsh --profile headless "Reply pong"` + 真实 key → 返回 `pong`（走 `deepseek-flash`）。
- 安装体积：`node_modules` ≈ 303MB。

> 沙箱自身就在 proot 内（日志有 `proot info: vpid 1 terminated`），无法再嵌套 proot，
> 故 `proot-run.sh` 是面向真机的参考实现；rootfs **内部**的 setup/start 流程均已实测通过。
