# 运行时 rootfs 的重建步骤

> ⚠️ 这份文档是**事后补写**的：最初的 rootfs 是**用临时命令**构建的，没有留下脚本。
> 下面把当时验证过的步骤固化下来，并把每一步踩过的坑写在同一处。

产物：`rootfs.tar.xz`（约 **300MB**），内含 **Debian 12 bookworm arm64** +
**官方 Node v22.23.3** + **预装的 `@deepseek-ai/dsh`**，供 App 首启解压到 `filesDir/dsh/rootfs`。

- 当前版本号：`debian-bookworm-arm64-node22-dsh-0.1.6-alpha.2`（见 `rootfs-build-info/rootfs.version`）
- 当前校验和：`5d762c30b9117830518571bc4eeadf593182cc56d1f472024ba4490aa90682a7`
- 落点：`FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz`（相对**源码仓根**；gitignore，不进 Git）
  以及 `FCL/src/main/assets/dsh/rootfs/version`（文本，一串版本标记）

## 0. 为什么需要预打包 rootfs

dsh 的原生模块（koffi / node-pty / sharp …）**只发布 linux 版**，Android 的 bionic libc 跑不了；
所以必须用 proot 跑一个 glibc 的 Linux。把 rootfs 预打包进 APK 可以省掉首次启动时的
debootstrap + 联网下载（那在手机上又慢又容易失败）。

## 1. 准备构建环境

```sh
# 需要：arm64 Linux（或 x86_64 上用 qemu-user-static + binfmt 跨架构）
# 需要 root（debootstrap / chroot）；约 3GB 空闲磁盘
apt-get update && apt-get install -y debootstrap qemu-user-static xz-utils curl
```

## 2. debootstrap 出最小 Debian

```sh
export R=$HOME/dsh-rootfs-build/rootfs
mkdir -p "$R"
debootstrap --arch=arm64 --variant=minbase \
  --include=ca-certificates,bash,coreutils,procps,curl,xz-utils \
  bookworm "$R" http://deb.debian.org/debian
```

> 若在 x86_64 上构建，debootstrap 后需把 qemu-user-static 拷进 rootfs 再 chroot：
> `cp /usr/bin/qemu-aarch64-static "$R/usr/bin/"`

## 3. 装 Node 22（官方 linux-arm64 构建）

```sh
cd /tmp
curl -LO https://nodejs.org/dist/v22.23.3/node-v22.23.3-linux-arm64.tar.xz
tar -xJf node-v22.23.3-linux-arm64.tar.xz
mkdir -p "$R/opt/node22"
cp -a node-v22.23.3-linux-arm64/. "$R/opt/node22/"
# 让 PATH 能找到（脚本里也会显式 export）
ln -sf /opt/node22/bin/node "$R/usr/local/bin/node"
ln -sf /opt/node22/bin/npm  "$R/usr/local/bin/npm"
"$R/opt/node22/bin/node" -v   # 期望 v22.23.3
```

## 4. 预装 dsh（关键：装到 `/opt/dsh-preinstalled`）

`setup-node-dsh.sh` 会检查"预装版本 == 请求版本"，命中就跳过下载。

```sh
DSH_VER=0.1.6-alpha.2
mkdir -p "$R/opt/dsh-preinstalled"
cd "$R/opt/dsh-preinstalled"
"$R/opt/node22/bin/npm" install --no-audit --no-fund "@deepseek-ai/dsh@$DSH_VER"
# 记下版本，供 App 侧比对
echo "$DSH_VER" > "$R/opt/dsh-preinstalled/.dsh-version"
```

## 5. 装编译工具（原生模块用）

dsh 的部分依赖要现场编译（node-gyp）：`build-essential` / `python3` / `pkg-config` / `git`。

```sh
chroot "$R" /bin/bash -c '
  apt-get update
  apt-get install -y --no-install-recommends build-essential python3 pkg-config git
  apt-get clean && rm -rf /var/lib/apt/lists/*
'
```

## 6. 清理（⚠️ 每一条都踩过坑）

```sh
# ① 宿主的 DNS 配置不要带进包（App 运行时会按当前网络写入，见 DshDns）
rm -f "$R/etc/resolv.conf"

# ② 沙箱存储实体不能打包：它们是软链环，打进去 rootfs 就废了
find "$R" -name '.l2s.*' -exec rm -f {} +

# ③ 构建期产生的坏软链（宿主视角的"断链"）
#    ★ 小心：guest 内的绝对软链在宿主看起来就是断的（例如 /bin -> usr/bin 下的相对链），
#      只删**确认是构建垃圾**的那些（perl/gunzip/bzip2 的 alternatives 残留、dpkg status-old）
rm -f "$R/usr/bin/perl" "$R/usr/bin/gunzip" "$R/usr/bin/bzip2" "$R/var/lib/dpkg/status-old" 2>/dev/null || true

# ④ npm 缓存与临时文件
rm -rf "$R/root/.npm" "$R/tmp/"* 2>/dev/null || true
```

## 7. 打包与校验

```sh
cd "$R"
# --numeric-owner 保留 uid/gid；排除 .l2s（双保险）
tar --numeric-owner --exclude='.l2s.*' -cJf "$HOME/dsh-rootfs-build/rootfs.tar.xz" .

sha256sum rootfs.tar.xz | tee rootfs.tar.xz.sha256
```

**解包自检**（在宿主上 chroot 进 rootfs 跑一遍最小链路）：

```sh
mkdir -p /tmp/v && tar -xJf rootfs.tar.xz -C /tmp/v
# 注意：嵌套 proot 会让内层 stat 失败 → 用 chroot 验证，不要用 proot
chroot /tmp/v /bin/bash -lc '
  node -v && npm -v
  node -e "require(\"child_process\").execSync(\"echo ok\")" && echo child_process-ok
  /opt/dsh-preinstalled/node_modules/.bin/dsh --help | head -3
'
```

预期：`v22.23.3` / `10.x` / `child_process-ok` / dsh 帮助输出。

## 8. 放回工程并更新版本标记

> 路径相对**仓库根**。

```sh
cp rootfs.tar.xz FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz
echo -n 'debian-bookworm-arm64-node22-dsh-0.1.6-alpha.2' \
  > FCL/src/main/assets/dsh/rootfs/version
```

`version` 文件的内容会参与 App 侧的就绪判定（`RuntimeUtils.isLatest` 做**字符串比较** ——
早先实现用 `Long.parseLong` 解析语义化版本号，直接抛异常导致底座永远不就绪）。

## 9. 体积参考

**瘦身前（0.1.0 ~ 0.1.2）**：

| 组成 | 大小 |
|---|---|
| Debian minbase + 工具链 | ~600MB |
| Node 22 | ~200MB |
| 预装 dsh 及依赖 | ~500MB |
| **解压后合计** | **~1.4GB（1436 MB）** |
| **tar.xz 压缩后** | **~300MB（314,360,800 字节）** |

**瘦身后（0.1.3 起）**：

| 组成 | 大小 |
|---|---|
| `/opt`（Node 22 + 预装 dsh） | 605 MB |
| `/usr` | 213 MB |
| `/var` + 其余 | 44 MB |
| **解压后合计** | **~862 MB（904,335,360 字节）** |
| **tar.xz 压缩后** | **142 MB（148,637,136 字节，−52.7%）** |

> 打包进 APK 时需在 `build.gradle.kts` 里对 `xz` 关闭二次压缩：
> `androidResources { noCompress += "xz" }` —— 否则打包会极慢且体积更大。

## 10. 瘦身（2026-10-06）：在现有包上直接剔除，不重打包

`rootfs.tar.xz` 里 **`/opt`（605MB）已经是绝对主体**（Node 22 + 预装 dsh），
外围能删的主要是**编译开发环境与文档**。做法上**不要重新 debootstrap** ——
用 GNU tar 的 `--delete` 在原包条目上直接剔，`/opt` 与所有软链原样不动：

```sh
# ① 解压成裸 tar（rootfs.tar.xz 是单层 xz + tar，约 1.4GB）
xz -dc rootfs.tar.xz > rootfs.tar
cp rootfs.tar rootfs-slim.tar

# ② 按「精确路径」剔除（★ 目录名在 tar 里带尾斜杠，写 ./usr/lib/gcc 会报 Not found）
tar --delete -f rootfs-slim.tar \
  ./usr/lib/gcc ./usr/libexec/gcc ./usr/lib/python3.11 \
  ./usr/include ./usr/share/doc ./usr/share/man ./usr/share/info \
  ./usr/share/perl ./usr/share/perl5 \
  ./usr/lib/aarch64-linux-gnu/perl ./usr/lib/aarch64-linux-gnu/perl-base \
  ./usr/bin/perl ./usr/bin/perl5.36.0 ./usr/bin/perlthanks ./usr/bin/perlbug \
  ./usr/bin/cpan ./usr/bin/perldoc \
  ./root/.cache ./var/cache/apt ./var/lib/apt \
  ./usr/lib/git-core ./usr/share/gitweb \
  ./usr/bin/git ./usr/bin/git-shell ./usr/bin/scalar \
  ./usr/lib/aarch64-linux-gnu/libasan.so.8.0.0 \
  ./usr/lib/aarch64-linux-gnu/libtsan.so.2.0.0 \
  ./usr/lib/aarch64-linux-gnu/liblsan.so.0.0.0 \
  ./usr/lib/aarch64-linux-gnu/libubsan.so.1.0.0 \
  ./usr/lib/aarch64-linux-gnu/libhwasan.so.0.0.0 \
  ./usr/lib/aarch64-linux-gnu/libcc1.so.0.0.0 \
  ./usr/bin/aarch64-linux-gnu-lto-dump-12 \
  ./usr/lib/aarch64-linux-gnu/gconv \
  ./usr/share/locale/fr ./usr/share/locale/ru ./usr/share/locale/uk \
  ./usr/share/locale/sv ./usr/share/locale/es ./usr/share/locale/de

# ③ 清掉指向已删目标的残留软链（否则留断链）
tar --delete -f rootfs-slim.tar \
  ./usr/lib/aarch64-linux-gnu/libasan.so.8 ./usr/lib/aarch64-linux-gnu/libtsan.so.2 \
  ./usr/lib/aarch64-linux-gnu/liblsan.so.0 ./usr/lib/aarch64-linux-gnu/libubsan.so.1 \
  ./usr/lib/aarch64-linux-gnu/libhwasan.so.0 ./usr/lib/aarch64-linux-gnu/libcc1.so.0 \
  ./usr/bin/lto-dump-12 ./usr/bin/aarch64-linux-gnu-lto-dump ./usr/bin/lto-dump \
  ./usr/bin/git-upload-archive ./usr/bin/git-receive-pack ./usr/bin/git-upload-pack \
  ./usr/lib/bfd-plugins/liblto_plugin.so ./usr/bin/pdb3.11 ./usr/bin/pdb3

# ④ 压回（-T0 用满所有核）
xz -T0 -6 -c rootfs-slim.tar > rootfs-slim.tar.xz
sha256sum rootfs-slim.tar.xz   # 期望 216cd9ef915a3512b9fedadfed7d5be6119422d120005c86b54d9e3b8ccea3f6
```

**剔了之后必须核对"没删坏"**（三条都做过，都可复现）：

```sh
# ① 关键路径还在吗
tar -tf rootfs-slim.tar | grep -qxF ./opt/node22/bin/npm
tar -tf rootfs-slim.tar | grep -qxF ./opt/dsh-preinstalled/node_modules/.bin/dsh

# ② 有没有留下断链软链（应为 0）
#    解析每条软链的相对目标 → 看它是否在包内；绝对目标不算断链（proot 在 guest 内翻译）
tar -tvf rootfs-slim.tar | grep ' -> ' | awk '{print $6"\t"$8}' > links.tsv   # 字段别数错：$6=名字 $8=目标

# ③ 逐条 diff 条目清单，确认"被删的都在清单里、清单里的都删干净了"
tar -tf rootfs.tar      | LC_ALL=C sort -u > list-full.txt
tar -tf rootfs-slim.tar | LC_ALL=C sort -u > list-slim.txt
comm -23 list-full.txt list-slim.txt    # 这些就是被删的
```

> ⚠️ **三个坑**（都实测踩过）：
> 1. **`--delete` 的名字必须与 tar 内完全一致**（目录带尾斜杠、软链用链接名）——
>    不一致会报 `Not found in archive` 并以非 0 退出，脚本里要判断这行是不是"真错误"。
> 2. **Windows 上 `tar -xJf` 解压到磁盘会因 `/etc/ssl/certs` 那批绝对软链报错退出**
>    （`Cannot create symlink to '/lib/...'`）。用 `--delete` 路线可以完全绕开解压。
> 3. **`comm` 比较前两边都要 `LC_ALL=C sort -u`**，否则 locale 排序不同会产出大量假差异；
>    另外目录条目带尾斜杠，比较时先 `sed 's|/$||'` 归一化，否则又会有一批假断链。
