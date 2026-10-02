# FCL 原汁原味界面还原方案

## 目标

本项目不是简单套一个 FCL 外壳，而是：

> **保留 FCL 的界面结构、控件行为、主题系统、设置组织方式和页面交互；剔除 Minecraft 专属功能，替换成 dsh 对应能力。**

参考基线：本地 git 提交 `647919c` 中的 FCL 原版源码。由于上游仓库体积较大，沙箱单次网络传输会中断，本轮不重复 clone 大仓库。

## FCL 设置页的真实结构

FCL 原版设置不是单个表单，而是以下结构：

```text
SettingUI
├── 全局/版本设置页容器
├── LauncherSettingPage
│   └── LauncherSettingAdapter
│       ├── SettingGroup
│       ├── 开关行
│       ├── 按钮行
│       ├── Spinner 行
│       ├── SeekBar 行
│       ├── 编辑行
│       └── 线程/数值行
├── 插件设置页
└── 关于页
```

行布局通过 RecyclerView 复用，组内相邻行使用统一间距和分割线；控件使用 fcllibrary 控件并由 ThemeEngine 统一刷新。

## dsh 页面映射

| FCL 原页面/设置 | dsh 处理 |
|---|---|
| 全局/启动器设置 | **保留 FCL 结构**，改成 dsh 启动器设置 |
| 版本设置 | 替换为 dsh 实例详情设置（API Key / 模型 / profile / 端口） |
| 插件设置 | 管理页预留，后续放 dsh 插件管理 |
| 关于页 | 保留 FCL 风格，内容替换为项目名称、版本、仓库地址、许可 |
| 语言 | 保留 |
| 主题模式 | 保留，直接使用 ThemeEngine |
| 亮色/暗色主题色 | 保留 |
| 背景图 | 保留，使用 FCL background_light/dark |
| 视频背景 | 暂不恢复，dsh 当前没有必要 |
| 游戏启动后退出 | 删除 |
| Java/JRE | 替换为 Node / proot / rootfs 状态 |
| 游戏资源/模组缓存 | 替换为 dsh 实例 / npm / rootfs 缓存管理 |
| 日志导出 | 保留并连接 DshLogBus |
| MC 账户 | 删除，替换为 DeepSeek API Key 状态入口 |

## 当前与目标的差距

### 当前

- `DshSettingsUI` 是一个按实例的表单页
- 设置 tab 与实例设置逻辑混在一起
- 主题背景已接回 ThemeEngine
- 控件已经使用 fcllibrary，但行组织方式还不是 FCL 原版设置列表

### 目标

- 设置 tab 改为 FCL 风格的启动器设置列表
- 实例详情设置继续独立存在，从实例卡“更多 → 设置”进入
- 设置行使用统一的 `DshLauncherSettingAdapter`
- 设置按 `DshSettingGroup` 分组
- 每行状态从 SharedPreferences / ThemeEngine / DshPaths / DshBootstrap 读取
- 改动即时写入，主题项即时刷新 ThemeEngine
- 返回、页面重建、横屏切换后状态与 FCL 一样可恢复

## dsh 设置分组草案

### 通用

- 语言
- 关于
- 仓库地址
- 当前版本

### 主题

- 主题模式：跟随系统 / 亮色 / 暗色
- 亮色主题色
- 暗色主题色
- 主背景图
- 深色背景图
- 动画速度
- 全屏模式

### 运行时

- proot 状态
- rootfs 状态
- Node.js 状态
- 运行时自检
- 运行时日志
- 清理运行时缓存

### dsh

- 默认 profile
- 默认模型
- 默认端口
- npm registry
- dsh 版本下载
- 实例管理

### 安全

- API Key 配置入口
- 清除 API Key
- API Key 测试连接

## 实施顺序

1. 新建 `DshLauncherSettingAdapter` 与 dsh 设置行模型
2. 新建 FCL 风格设置页布局，替换当前全局设置占位页
3. 把 ThemeEngine 设置迁移到设置行
4. 把 dsh runtime 状态、自检、日志入口接入设置行
5. 保留 `DshSettingsActivity` 作为实例详情页
6. 新建 FCL 风格关于页
7. 逐项对照 FCL 原版：间距、圆角、分组、按钮反馈、主题刷新、横屏布局
8. 编译验证，再进行真机视觉核对

## 验收标准

- 不出现 Material 控件
- 设置页结构与 FCL 的 RecyclerView 分组设置一致
- 主题切换后页面无需重启即可刷新
- 横屏下设置列表不会被右侧面板压缩到不可用
- 实例设置与全局启动器设置职责分离
- MC 专属设置不重新引入
