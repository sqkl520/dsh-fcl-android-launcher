#!/bin/sh
# 量 dsh 里那两条"慢查询"的真实开销（round48，插件页/内置插件页加载慢）。
#
# ## 为什么需要它
#
# 现象已实测：插件页约 4 分钟、设置→内置插件约 3 分钟才出内容，
# 而同机的通用设置/Agent 预设/自动化任务都是秒开。
#
# 读源码得出的假设是：`listBundles()` 对每个 bundle 名做
# `createRequire().resolve.paths()` 路径枚举 + 每条路径一次 `existsSync` +
# 一次 `readFileSync`，全同步顺序执行；在 proot 下每次 syscall 都要穿 ptrace
# 拦截 + 路径重写，累积成秒级。
#
# **但这只是假设。** 本脚本就是去拿数据验证它 —— 在设备上的 proot 里真跑一遍，
# 分别计时：
#   A. 纯 resolve.paths（只做路径枚举，不 stat）
#   B. resolve + existsSync（加上逐条 stat）
#   C. resolve + existsSync + readFileSync（完整的一轮 bundleManifest）
#   D. readdir 一遍 node_modules（"建索引"方案的基线）
#
# 有了 A/B/C/D 四个数字就能判断：慢在路径枚举、慢在 stat、还是慢在读文件，
# 以及"readdir 建索引"那个方案能省多少。
#
# ## 用法
#
#   sh tools/measure-plugin-slowdown.sh            # 默认量当前实例
#   sh tools/measure-plugin-slowdown.sh <instance>
#
# 需要设备已授权（adb devices 显示 device 而不是 unauthorized）。
set -u

REPO=$(cd "$(dirname "$0")/.." && pwd)
ADB="$REPO/tools/adb.sh"
PKG=com.dsh.fcl.androidlauncher

# 实例目录（guest 内路径）。默认取最近活跃的那个；可用 $1 覆盖。
INST=${1:-}

a() { MSYS_NO_PATHCONV=1 "$ADB" shell "$@" 2>/dev/null; }

if ! a "echo ok" | grep -q ok; then
  echo "!! 设备不可用：先让 adb devices 显示 device（不是 unauthorized）"
  exit 1
fi

if [ -z "$INST" ]; then
  # 从正在跑的进程里读 INSTANCE_DIR（最可靠：就是 dsh 真正在用的那个）
  INST=$(a "for p in /proc/[0-9]*/cmdline; do tr '\0' '\n' < \$p 2>/dev/null | grep -m1 '^INSTANCE_DIR=' ; done" | head -1 | cut -d= -f2-)
fi
if [ -z "$INST" ]; then
  echo "!! 找不到实例目录（实例没在跑？）—— 也可以手动传：sh tools/measure-plugin-slowdown.sh /opt/dsh/instances/<id>"
  exit 1
fi

echo "== 实例目录: $INST =="
echo ""

# 这段 node 脚本在 proot 内跑（通过 dsh 的 node）。
# 注意：要在 dsh 的工作目录里跑，这样 require 解析到实例的 node_modules。
NODE_SCRIPT='
const fs = require("fs");
const path = require("path");
const { createRequire } = require("module");

const inst = process.env.INSTANCE_DIR;
const nm = path.join(inst, "node_modules");

function time(label, fn) {
  const t0 = process.hrtime.bigint();
  let r;
  try { r = fn() } catch (e) { r = { error: String(e && e.message || e).slice(0, 120) } }
  const ms = Number(process.hrtime.bigint() - t0) / 1e6;
  console.log(label + " = " + ms.toFixed(1) + " ms" + (r && r.error ? "  [" + r.error + "]" : ""));
  return r;
}

// 先看实例里有多少个包
const names = time("readdir(node_modules)   [D 基线]", () => {
  const out = [];
  for (const e of fs.readdirSync(nm, { withFileTypes: true })) {
    if (e.name.startsWith(".")) continue;
    if (e.name.startsWith("@")) {
      const scope = path.join(nm, e.name);
      try {
        for (const s of fs.readdirSync(scope, { withFileTypes: true })) {
          if (s.isDirectory()) out.push(e.name + "/" + s.name);
        }
      } catch {}
    } else if (e.isDirectory()) {
      out.push(e.name);
    }
  }
  return out;
});
const pkgNames = Array.isArray(names) ? names : [];
console.log("包数量 = " + pkgNames.length);
console.log("");

if (pkgNames.length === 0) {
  console.log("（node_modules 里没包，下面的测量无意义 —— 实例可能还没装包）");
  process.exit(0);
}

const anchor = path.join(inst, "package.json");
const req = createRequire(fs.existsSync(anchor) ? anchor : path.join(inst, "index.js"));

// A. 只做路径枚举，不碰文件系统
time("A resolve.paths x" + pkgNames.length + "        [纯路径枚举]", () => {
  let n = 0;
  for (const name of pkgNames) {
    const paths = req.resolve.paths(name) || [];
    n += paths.length;
  }
  return { n };
});

// B. 路径枚举 + 每条路径一次 existsSync
time("B resolve+existsSync x" + pkgNames.length + "   [加了逐条 stat]", () => {
  let hit = 0;
  for (const name of pkgNames) {
    for (const sp of (req.resolve.paths(name) || [])) {
      if (fs.existsSync(path.join(sp, name, "package.json"))) { hit++; break }
    }
  }
  return { hit };
});

// C. 完整一轮 bundleManifest 的核心动作
const dirs = [];
time("C resolve+exists+read x" + pkgNames.length + "  [完整 bundleManifest]", () => {
  let ok = 0;
  for (const name of pkgNames) {
    for (const sp of (req.resolve.paths(name) || [])) {
      const dir = path.join(sp, name);
      if (fs.existsSync(path.join(dir, "package.json"))) {
        try { JSON.parse(fs.readFileSync(path.join(dir, "package.json"), "utf8")); ok++; dirs.push(dir) } catch {}
        break;
      }
    }
  }
  return { ok };
});

console.log("");
console.log("结论口径：");
console.log("  B - A  = stat 的开销（proot 路径重写主要体现在这里）");
console.log("  C - B  = 读文件 + JSON.parse 的开销");
console.log("  D      = readdir 一遍的成本（若 ≪ B，则\"建索引\"方案有效）");
'

echo "== 在 proot 内执行测量（约 1~3 分钟，请耐心） =="
# 通过 start-dsh.sh 那套 proot 环境跑：复用 dsh 的 node（/opt/node22/bin/node）
a "cd '$INST' && INSTANCE_DIR='$INST' ./node_modules/.bin/../../ 2>/dev/null; true"
a "INSTANCE_DIR='$INST' /opt/node22/bin/node -e '$NODE_SCRIPT'" 2>&1 | sed 's/^/  /'

echo ""
echo "== 提示 =="
echo "如果 /opt/node22/bin/node 不存在，改用："
echo "  adb shell \"INSTANCE_DIR='$INST' node -e '<脚本>'\""
