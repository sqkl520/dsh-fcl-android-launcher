#!/bin/sh
# probe.sh — 运行时自检探针（DshBootstrap.verify 调用）
#
# 目的：在"装机后第一次真正执行 proot"的时刻就把问题暴露出来，而不是等到安装 dsh 时：
#   - proot 二进制缺失 / 没有执行权限（assets 方案在 Android 10+ 常见的 W^X 限制）
#   - rootfs 解压不完整、或者多套了一层目录导致 -r 指错根
#   - proot 缺少 loader（PROOT_LOADER）
#
# 输出一行 `dsh-probe-ok` 表示整条链路可用；退出码非 0 表示不可用。
set -u

printf 'dsh-probe: %s %s\n' "$(uname -s 2>/dev/null || echo unknown)" "$(uname -m 2>/dev/null || echo unknown)"

if [ ! -x /bin/sh ] && [ ! -x /usr/bin/env ]; then
  echo "dsh-probe: 找不到 /bin/sh 或 /usr/bin/env（rootfs 不完整）" >&2
  exit 2
fi

if command -v node >/dev/null 2>&1; then
  printf 'dsh-probe: node %s\n' "$(node -v 2>/dev/null)"
else
  echo "dsh-probe: 警告：rootfs 内没有 node（安装 dsh 时会联网安装）" >&2
fi

echo "dsh-probe-ok"
