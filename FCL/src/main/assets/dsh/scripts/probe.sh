#!/bin/sh
# probe.sh — 运行时自检探针（DshBootstrap.verify 调用）
#
# 目的：在"装机后第一次真正执行 proot"的时刻就把问题暴露出来，而不是等到装 dsh 时才炸。
# 逐项检查并打印 `dsh-probe: <项> = ok|FAIL`，全部通过时最后一行输出 `dsh-probe-ok`。
# 退出码 0 = 整条链路可用；非 0 = 不可用（并给出 FAIL 项）。
#
# 检查项（按重要性）：
#   1) rootfs 基本布局        /bin/sh、/usr/bin/env
#   2) 预装 Node              node 版本必须满足 dsh 要求（^22.19 || >=24）
#   3) bash                   dsh 的 shell 工具依赖它
#   4) child_process 子进程    execSync / spawnSync(bash) / spawnSync(sh) —— 最关键的运行时能力
#   5) npm                    插件安装依赖它
#   6) 预装 dsh                bin.js 存在且能报出版本
#   7) 原生模块 dlopen         .node 能被 Node 加载（插件扩展的前提）
set -u

PATH="/opt/node22/bin:$PATH"
export PATH
DSH_PREINSTALL_DIR="${DSH_PREINSTALL_DIR:-/opt/dsh-preinstalled}"

# 与 start-dsh.sh 保持一致：关闭原生模块加载器的硬链接缓存
NARB_DISABLE_NATIVE_CACHE="${NARB_DISABLE_NATIVE_CACHE:-1}"
export NARB_DISABLE_NATIVE_CACHE

# 前置：rootfs 布局
if [ ! -x /bin/sh ] && [ ! -x /usr/bin/env ]; then
  echo "dsh-probe: rootfs-layout = FAIL（找不到 /bin/sh 或 /usr/bin/env）"
  exit 2
fi
echo "dsh-probe: rootfs-layout = ok ($(uname -s 2>/dev/null || echo unknown) $(uname -m 2>/dev/null || echo unknown))"

if ! command -v node >/dev/null 2>&1; then
  echo "dsh-probe: node = FAIL（PATH 中找不到 node）"
  exit 3
fi

# 把检查逻辑交给 node 跑（避免在 sh 里拼复杂判断）
CHK="${TMPDIR:-/tmp}/dsh-probe-$$.js"
if ! : > "$CHK" 2>/dev/null; then
  # 回退到 /var/tmp（不要写到 /opt/dsh/*：那是宿主 filesDir/dsh 的 bind 挂载点）
  CHK="/var/tmp/dsh-probe-$$.js"
  : > "$CHK" 2>/dev/null || { echo "dsh-probe: tmp = FAIL（无可写临时目录）"; exit 4; }
fi

cat > "$CHK" <<'JSEOF'
const fs = require('fs');
const cp = require('child_process');
const preDir = process.env.DSH_PREINSTALL_DIR || '/opt/dsh-preinstalled';

let critical = 0;
function report(name, ok, extra) {
  console.log('dsh-probe: ' + name + ' = ' + (ok ? 'ok' : 'FAIL') + (extra ? ' (' + extra + ')' : ''));
  return ok;
}
function reportCritical(name, ok, extra) {
  if (!report(name, ok, extra)) critical++;
  return ok;
}

// 2) Node 版本
const v = process.versions.node.split('.').map(Number);
const nodeOk = (v[0] === 22 && v[1] >= 19) || v[0] >= 24;
reportCritical('node', nodeOk, 'v' + process.versions.node + (nodeOk ? '' : '，需要 ^22.19 || >=24'));

// 3) bash
let bashOk = false, bashVer = '';
try {
  const r = cp.spawnSync('/bin/bash', ['-lc', 'bash --version | head -1']);
  bashOk = r.status === 0 && /bash/i.test(String(r.stdout));
  bashVer = String(r.stdout).split('\n')[0].trim();
} catch (e) { bashOk = false; }
reportCritical('bash', bashOk, bashVer);

// 4) child_process —— 最关键的运行时能力
try {
  const out = cp.execSync('echo exec-ok').toString().trim();
  reportCritical('child_process.execSync', out === 'exec-ok', out);
} catch (e) {
  reportCritical('child_process.execSync', false, String(e.message).slice(0, 80));
}
try {
  const r = cp.spawnSync('/bin/bash', ['-lc', 'echo bash-ok']);
  const out = String(r.stdout).trim();
  reportCritical('child_process.spawnSync.bash', r.status === 0 && out === 'bash-ok', out);
} catch (e) {
  reportCritical('child_process.spawnSync.bash', false, String(e.message).slice(0, 80));
}
try {
  const r = cp.spawnSync('/bin/sh', ['-c', 'echo sh-ok']);
  const out = String(r.stdout).trim();
  reportCritical('child_process.spawnSync.sh', r.status === 0 && out === 'sh-ok', out);
} catch (e) {
  reportCritical('child_process.spawnSync.sh', false, String(e.message).slice(0, 80));
}

// 5) npm
try {
  const nv = cp.execSync('npm --version').toString().trim();
  reportCritical('npm', /^\d+\./.test(nv), 'v' + nv);
} catch (e) {
  reportCritical('npm', false, 'npm 不可用');
}

// 6) 预装 dsh
const binJs = preDir + '/node_modules/@deepseek-ai/dsh/lib/bin.js';
let dshVer = '';
try {
  dshVer = JSON.parse(fs.readFileSync(preDir + '/node_modules/@deepseek-ai/dsh/package.json', 'utf8')).version;
} catch (e) { /* 读不到就只报 FAIL */ }
reportCritical('dsh.preinstalled', fs.existsSync(binJs), dshVer ? ('v' + dshVer) : 'bin.js 缺失');

// 7) 原生模块 dlopen（插件扩展的前提）
const candidates = [
  preDir + '/node_modules/@deepseek-ai/node-addon-system-linux-arm64/bin/glibc/system.node',
  preDir + '/node_modules/@koromix/koffi-linux-arm64/linux_arm64/koffi.node',
  preDir + '/node_modules/node-pty/prebuilds/linux-arm64/pty.node',
];
let dlOk = false, dlWhich = '';
for (const p of candidates) {
  if (!fs.existsSync(p)) continue;
  try {
    process.dlopen({ exports: {} }, p);
    dlOk = true; dlWhich = p.split('/node_modules/')[1] || p;
    break;
  } catch (e) {
    dlWhich = (p.split('/node_modules/')[1] || p) + ' 加载失败: ' + String(e.message).slice(0, 60);
    break;
  }
}
reportCritical('native.dlopen', dlOk, dlWhich || '未找到可测试的 .node');

process.exit(critical === 0 ? 0 : 1);
JSEOF

node "$CHK"
rc=$?
rm -f "$CHK" 2>/dev/null || true

if [ "$rc" -eq 0 ]; then
  echo "dsh-probe-ok"
else
  echo "dsh-probe: 自检未通过（见上面的 FAIL 项）"
fi
exit "$rc"
