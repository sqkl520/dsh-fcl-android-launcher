package com.dsh.core

/**
 * tar 条目「链接目标」的还原策略 —— 只放一处，便于单测守住。
 *
 * ## 为什么单独抽出来（第十一轮发现的一个 P1）
 * [com.tungsten.fcl.util.RuntimeUtils.uncompressTarXZ] 解压 rootfs 时，对符号链接条目做过一次
 * 「把链接目标里的 `..` 替换成宿主解压目录的绝对路径」的处理：
 *
 * ```java
 * Os.symlink(entry.getLinkName().replace("..", dest.getAbsolutePath()), linkPath);
 * ```
 *
 * 这行来自 FCL 上游（为 JRE 资产写的），对本项目的 rootfs 是**错的**：
 * 内核解析相对链接是「相对链接所在目录」，而 `replace("..", dest)` 会把相对目标拼成宿主绝对路径，
 * 语义完全改变。实测本项目 rootfs 里几百个链接命中，其中几个是**致命**的：
 *
 * ```
 * opt/node22/bin/npm  -> ../lib/node_modules/npm/bin/npm-cli.js
 *   正确目标: <rootfs>/opt/node22/lib/node_modules/npm/bin/npm-cli.js   （存在）
 *   实际写入: <rootfs>/lib/node_modules/npm/bin/npm-cli.js             （不存在 → 断链）
 * opt/dsh-preinstalled/node_modules/.bin/dsh -> ../@deepseek-ai/dsh/lib/bin.js
 *   正确目标: <rootfs>/opt/dsh-preinstalled/node_modules/@deepseek-ai/dsh/lib/bin.js
 *   实际写入: <rootfs>/@deepseek-ai/dsh/lib/bin.js                     （不存在 → 断链）
 * ```
 *
 * 后果：真机首启解压后 `/opt/node22/bin/npm` 是断链 → `npm --version` 失败 →
 * `probe.sh` 的 `npm` 项 FAIL → [DshBootstrap] 自检不通过 → **底座永远不就绪**，
 * 安装/启动全挂。而 chroot 里直接验证 rootfs 时（不经过 [RuntimeUtils] 解压）是好的，
 * 所以此前几轮都没暴露。
 *
 * ## 正确做法
 * **原样保留链接目标**。tar 的语义就是如此（GNU tar 亦如此）；相对目标由内核按链接所在目录解析，
 * 绝对目标（如 `/usr/bin/foo`）则由 proot 在 guest 内翻译。任何「改写目标」的行为都会破坏它。
 */
object TarLinkPolicy {

    /**
     * 返回写入文件系统的符号链接目标。
     *
     * 当前实现是恒等函数 —— 这是**刻意**的：它记录「不做任何改写」这条决定，
     * 并给单测一个抓手，防止再次引入 `replace("..", dest)` 那类改写。
     */
    @JvmStatic
    fun symlinkTarget(linkName: String): String = linkName
}
