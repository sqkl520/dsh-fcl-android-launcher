// relabel-apk-inline.js —— 在 ZIP 层面原地改 APK 里的 fix 标签，全程不落盘解包
//
// 为什么不用 unzip + zip：Windows 文件系统不区分大小写，APK 里同时存在
// res/2F.xml 与 res/2f.xml，unzip 解包时会互相覆盖，实测 1017 个条目只解出 960 个。
// 所以在内存里读原始 zip 的每个条目、按需替换字节、再写出新 zip，完全不碰文件系统。
//
// ★ 只做等长替换（fix24/fix25 都是 5 字符），不改变任何长度与偏移。
// ★ 保留原条目顺序、压缩方式与 extra 字段；.so 与 resources.arsc 仍为 STORED。
// ★ 改完必须重新签名。
const fs = require("fs");
const zlib = require("zlib");

const [,, apkPath, fromLabel, toLabel] = process.argv;
if (!apkPath || !fromLabel || !toLabel) {
  console.error("用法: node relabel-apk-inline.js <apk> <旧标签> <新标签>");
  process.exit(2);
}
if (fromLabel.length !== toLabel.length) {
  console.error("!! 标签长度不等，拒绝原地替换");
  process.exit(2);
}

const fromVer = "0.1.6-SNAPSHOT-" + fromLabel;
const toVer = "0.1.6-SNAPSHOT-" + toLabel;
const encOf = (name) => (name === "AndroidManifest.xml" ? "utf16le" : "latin1");

let crcTable = null;
function crc32(b) {
  if (!crcTable) {
    crcTable = new Int32Array(256);
    for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crcTable[n] = c; }
  }
  let c = -1;
  for (let i = 0; i < b.length; i++) c = (c >>> 8) ^ crcTable[(c ^ b[i]) & 0xff];
  return (c ^ -1) >>> 0;
}

// ---- 读原始 zip 的中央目录 ----
const buf = fs.readFileSync(apkPath);
let eocd = -1;
for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65558); i--) {
  if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
}
if (eocd < 0) { console.error("!! 找不到 EOCD"); process.exit(1); }

const count = buf.readUInt16LE(eocd + 10);
let cdOff = buf.readUInt32LE(eocd + 16);

const entries = [];
let p = cdOff;
for (let i = 0; i < count; i++) {
  if (buf.readUInt32LE(p) !== 0x02014b50) { console.error("!! 中央目录损坏"); process.exit(1); }
  const method = buf.readUInt16LE(p + 10);
  const crc = buf.readUInt32LE(p + 16);
  const compSize = buf.readUInt32LE(p + 20);
  const uncompSize = buf.readUInt32LE(p + 24);
  const nameLen = buf.readUInt16LE(p + 28);
  const extraLen = buf.readUInt16LE(p + 30);
  const commentLen = buf.readUInt16LE(p + 32);
  const localOff = buf.readUInt32LE(p + 42);
  const name = buf.toString("utf8", p + 46, p + 46 + nameLen);
  entries.push({ name, method, crc, compSize, uncompSize, localOff,
                 extra: buf.slice(p + 46 + nameLen, p + 46 + nameLen + extraLen),
                 flags: buf.readUInt16LE(p + 8) });
  p += 46 + nameLen + extraLen + commentLen;
}

// ---- 逐条重建 ----
const chunks = [];
const centralParts = [];
let offset = 0;
let patched = 0;

for (const e of entries) {
  const lo = e.localOff;
  if (buf.readUInt32LE(lo) !== 0x04034b50) { console.error("!! 本地头损坏: " + e.name); process.exit(1); }
  const lNameLen = buf.readUInt16LE(lo + 26);
  const lExtraLen = buf.readUInt16LE(lo + 28);
  const dataStart = lo + 30 + lNameLen + lExtraLen;
  const raw = buf.slice(dataStart, dataStart + e.compSize);

  let data = e.method === 8 ? zlib.inflateRawSync(raw) : Buffer.from(raw);

  // 替换版本串
  const enc = encOf(e.name);
  const needle = Buffer.from(fromVer, enc);
  const repl = Buffer.from(toVer, enc);
  let cnt = 0, idx = data.indexOf(needle);
  while (idx >= 0) { repl.copy(data, idx); cnt++; idx = data.indexOf(needle, idx + needle.length); }
  if (cnt > 0) patched += cnt;

  const comp = e.method === 8 ? zlib.deflateRawSync(data, { level: 9 }) : data;
  const crc = crc32(data);
  const nameBuf = Buffer.from(e.name, "utf8");

  const local = Buffer.alloc(30);
  local.writeUInt32LE(0x04034b50, 0);
  local.writeUInt16LE(20, 4);
  local.writeUInt16LE(e.flags, 6);
  local.writeUInt16LE(e.method, 8);
  local.writeUInt16LE(0, 10);
  local.writeUInt16LE(0, 12);
  local.writeUInt32LE(crc, 14);
  local.writeUInt32LE(comp.length, 18);
  local.writeUInt32LE(data.length, 22);
  local.writeUInt16LE(nameBuf.length, 26);
  local.writeUInt16LE(0, 28);

  chunks.push(local, nameBuf, comp);

  const cd = Buffer.alloc(46);
  cd.writeUInt32LE(0x02014b50, 0);
  cd.writeUInt16LE(20, 4);
  cd.writeUInt16LE(20, 6);
  cd.writeUInt16LE(e.flags, 8);
  cd.writeUInt16LE(e.method, 10);
  cd.writeUInt16LE(0, 12);
  cd.writeUInt16LE(0, 14);
  cd.writeUInt32LE(crc, 16);
  cd.writeUInt32LE(comp.length, 20);
  cd.writeUInt32LE(data.length, 24);
  cd.writeUInt16LE(nameBuf.length, 28);
  cd.writeUInt16LE(0, 30);
  cd.writeUInt16LE(0, 32);
  cd.writeUInt16LE(0, 34);
  cd.writeUInt16LE(0, 36);
  cd.writeUInt32LE(0, 38);
  cd.writeUInt32LE(offset, 42);
  centralParts.push(cd, nameBuf);

  offset += local.length + nameBuf.length + comp.length;
}

const cdBuf = Buffer.concat(centralParts);
const eocdNew = Buffer.alloc(22);
eocdNew.writeUInt32LE(0x06054b50, 0);
eocdNew.writeUInt16LE(0, 4);
eocdNew.writeUInt16LE(0, 6);
eocdNew.writeUInt16LE(entries.length, 8);
eocdNew.writeUInt16LE(entries.length, 10);
eocdNew.writeUInt32LE(cdBuf.length, 12);
eocdNew.writeUInt32LE(offset, 16);
eocdNew.writeUInt16LE(0, 20);

const out = apkPath.replace(/\.apk$/, "") + ".relabeled.apk";
fs.writeFileSync(out, Buffer.concat([...chunks, cdBuf, eocdNew]));

console.log(`条目: ${entries.length}  替换: ${patched} 处`);
console.log(`产出: ${out}  (${fs.statSync(out).size} bytes)`);
