#!/usr/bin/env node
// renumber-releases.js —— 把 docs/RELEASES.md 的版本段整体下移一位
//
// 背景：fix16 是实测的空版本（相对 fix15 只有 versionCode/versionName 与
// baseline.prof 变化，四个启动脚本逐字节相同），已删除，后续版本依次前移一位；
// 合并版从 fix27 改称 fix26（紧接 fix25）。
//
// ★ 关键：只对 fix >= 17 的段做 N → N-1。fix <= 15 的段本来就没有 fix5（从未发布），
//   若把 fix15..fix1 也整体减一，会出现 fix0、并把原 fix4 错改成 fix5。
// ★ 非 fix 段（0.1.6-SNAPSHOT / 0.1.5-SNAPSHOT …）一律原样保留，不参与重排。
const fs = require("fs");

const file = process.argv[2];
if (!file) { console.error("用法: node renumber-releases.js <RELEASES.md>"); process.exit(2); }

const text = fs.readFileSync(file, "utf8");
const firstHead = text.indexOf("\n## ");
if (firstHead < 0) { console.error("!! 找不到版本段"); process.exit(1); }
const header = text.slice(0, firstHead);
const body = text.slice(firstHead);

const parts = body.split(/^(?=## )/m).filter(Boolean);

const fixSections = [];   // { n, chunk }
const others = [];        // 其它段（非 fix）
for (const chunk of parts) {
  const m = chunk.match(/^## 0\.1\.6-SNAPSHOT-fix(\d+)\s*$/m);
  if (m) fixSections.push({ n: parseInt(m[1], 10), chunk });
  else others.push(chunk);
}

const before = fixSections.map((s) => s.n).sort((a, b) => b - a);
console.log("原 fix 段: " + before.join(" "));

const out = [];
for (const s of fixSections) {
  if (s.n === 16) { console.log("  删掉 fix16（空版本）"); continue; }
  if (s.n < 17) { out.push({ n: s.n, chunk: s.chunk }); continue; }   // fix1~fix15 原样
  const to = s.n - 1;
  let c = s.chunk.replace(
    new RegExp("^## 0\\.1\\.6-SNAPSHOT-fix" + s.n + "\\s*$", "m"),
    `## 0.1.6-SNAPSHOT-fix${to}`
  );
  c = c.replace(new RegExp(`0\\.1\\.6-SNAPSHOT-fix${s.n}(?![0-9])`, "g"), `0.1.6-SNAPSHOT-fix${to}`);
  out.push({ n: to, chunk: c });
}

out.sort((a, b) => b.n - a.n);
const after = out.map((s) => s.n);
console.log("新 fix 段: " + after.join(" "));

fs.writeFileSync(file, header + out.map((s) => s.chunk).join("") + others.join(""));
console.log(`写回 ${out.length} 个 fix 段 + ${others.length} 个非 fix 段`);
