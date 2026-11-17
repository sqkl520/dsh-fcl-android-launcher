[简体中文](ROOTFS.md) | English

# Steps to rebuild the runtime rootfs

> ⚠️ This document was **written after the fact**: the original rootfs was built **with ad-hoc commands**, and no script was kept.
> Below, the steps that were verified at the time are frozen in place, with the pitfalls hit along the way written down in the same spot.

Output: `rootfs.tar.xz` (**142MB** after slimming; about 300MB originally, see the size comparison table in §9), containing **Debian 12 bookworm arm64** +
**official Node v22.23.3** + **a preinstalled `@deepseek-ai/dsh`**, which the app extracts to `filesDir/dsh/rootfs` on first launch.

- Current version string: `debian-bookworm-arm64-node22-dsh0.1.6-alpha.2-slim1` (see `rootfs/version`; note there is **no**
  hyphen between `dsh` and the version number — the old string is kept, because changing it would trigger a full re-extraction)
- Current checksum: `216cd9ef915a3512b9fedadfed7d5be6119422d120005c86b54d9e3b8ccea3f6`
  (the old `5d762c30…` can still be recovered from the 0.1.2 Release asset)
- Location: `FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz` (relative to the **source repository root**; gitignored, not in Git)
  and `FCL/src/main/assets/dsh/rootfs/version` (text, a version marker string)

> ⚠️ **The current archive has one known impurity**: the top level of `rootfs.tar.xz` contains `./ztest/` and `./ztest/f.txt` (2-byte test leftovers,
> i.e. the extracted rootfs has one extra `/ztest`). Harmless, and **do not bump `rootfs/version` just for it** (an upgrade triggers a full
> re-extraction on every installed device); the cleanup list in §6 will remove it the next time you rebuild following this document.

## 0. Why the rootfs must be pre-packaged

dsh's native modules (koffi / node-pty / sharp …) **only publish linux builds**, and Android's bionic libc cannot run them;
so a glibc Linux has to run under proot. Pre-packaging the rootfs into the APK saves the debootstrap + online download on first launch
(which on a phone is both slow and failure-prone).

## 1. Prepare the build environment

```sh
# Requires: arm64 Linux (or qemu-user-static + binfmt for cross-arch on x86_64)
# Requires root (debootstrap / chroot); about 3GB of free disk
apt-get update && apt-get install -y debootstrap qemu-user-static xz-utils curl
```

## 2. debootstrap a minimal Debian

```sh
export R=$HOME/dsh-rootfs-build/rootfs
mkdir -p "$R"
debootstrap --arch=arm64 --variant=minbase \
  --include=ca-certificates,bash,coreutils,procps,curl,xz-utils \
  bookworm "$R" http://deb.debian.org/debian
```

> When building on x86_64, after debootstrap you must copy qemu-user-static into the rootfs before chrooting:
> `cp /usr/bin/qemu-aarch64-static "$R/usr/bin/"`

## 3. Install Node 22 (official linux-arm64 build)

```sh
cd /tmp
curl -LO https://nodejs.org/dist/v22.23.3/node-v22.23.3-linux-arm64.tar.xz
tar -xJf node-v22.23.3-linux-arm64.tar.xz
mkdir -p "$R/opt/node22"
cp -a node-v22.23.3-linux-arm64/. "$R/opt/node22/"
# Make it findable on PATH (the scripts also export it explicitly)
ln -sf /opt/node22/bin/node "$R/usr/local/bin/node"
ln -sf /opt/node22/bin/npm  "$R/usr/local/bin/npm"
"$R/opt/node22/bin/node" -v   # expect v22.23.3
```

## 4. Preinstall dsh (key point: install it into `/opt/dsh-preinstalled`)

`setup-node-dsh.sh` checks "preinstalled version == requested version" and skips the download on a hit
(it reads `version` from `$DSH_PREINSTALL_DIR/node_modules/@deepseek-ai/dsh/package.json`).

```sh
DSH_VER=0.1.6-alpha.2
mkdir -p "$R/opt/dsh-preinstalled"
cd "$R/opt/dsh-preinstalled"
"$R/opt/node22/bin/npm" install --no-audit --no-fund "@deepseek-ai/dsh@$DSH_VER"
# No separate version text file is needed: the install script compares against the version in package.json directly
```

## 5. Install build tools (for native modules)

Some dsh dependencies have to be compiled on the spot (node-gyp): `build-essential` / `python3` / `pkg-config` / `git`.

```sh
chroot "$R" /bin/bash -c '
  apt-get update
  apt-get install -y --no-install-recommends build-essential python3 pkg-config git
  apt-get clean && rm -rf /var/lib/apt/lists/*
'
```

## 6. Cleanup (⚠️ every line here has bitten us)

```sh
# ① Do not carry the host's DNS configuration into the package (the app writes it at runtime per the current network, see DshDns)
rm -f "$R/etc/resolv.conf"

# ② The sandbox storage entities must not be packed: they are symlink loops, and the rootfs is dead once they are inside
find "$R" -name '.l2s.*' -exec rm -f {} +

# ③ Broken symlinks produced during the build (a "dangling link" from the host's point of view)
#    ★ Careful: absolute symlinks inside the guest look broken on the host (e.g. the relative chain under /bin -> usr/bin),
#      so delete only the ones **confirmed to be build junk** (perl/gunzip/bzip2 alternatives leftovers, dpkg status-old)
rm -f "$R/usr/bin/perl" "$R/usr/bin/gunzip" "$R/usr/bin/bzip2" "$R/var/lib/dpkg/status-old" 2>/dev/null || true

# ④ npm cache and temporary files
rm -rf "$R/root/.npm" "$R/tmp/"* 2>/dev/null || true

# ⑤ Build-time test leftovers (the current archive has one `./ztest/`, see the known-impurity note at the top)
rm -rf "$R/ztest"
```

## 7. Pack and verify

```sh
cd "$R"
# --numeric-owner preserves uid/gid; exclude .l2s (belt and braces)
tar --numeric-owner --exclude='.l2s.*' -cJf "$HOME/dsh-rootfs-build/rootfs.tar.xz" .

sha256sum rootfs.tar.xz | tee rootfs.tar.xz.sha256
```

**Unpack self-check** (chroot into the rootfs on the host and run a minimal chain):

```sh
mkdir -p /tmp/v && tar -xJf rootfs.tar.xz -C /tmp/v
# Note: nesting proot makes the inner stat fail → verify with chroot, not proot
chroot /tmp/v /bin/bash -lc '
  node -v && npm -v
  node -e "require(\"child_process\").execSync(\"echo ok\")" && echo child_process-ok
  /opt/dsh-preinstalled/node_modules/.bin/dsh --help | head -3
'
```

Expected: `v22.23.3` / `10.x` / `child_process-ok` / dsh help output.

## 8. Put it back into the project and update the version marker

> Paths are relative to the **repository root**.

```sh
cp rootfs.tar.xz FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz
echo -n 'debian-bookworm-arm64-node22-dsh0.1.6-alpha.2-slim1' \
  > FCL/src/main/assets/dsh/rootfs/version
```

The contents of the `version` file take part in the app-side readiness decision (`RuntimeUtils.isLatest` does a **string comparison** —
an earlier implementation used `Long.parseLong` to parse semantic version strings, threw an exception outright, and left the base never ready).

## 9. Size reference

**Before slimming (0.1.0 ~ 0.1.2)**:

| Component | Size |
|---|---|
| Debian minbase + toolchain | ~600MB |
| Node 22 | ~200MB |
| Preinstalled dsh and dependencies | ~500MB |
| **Total extracted** | **~1.4GB (1436 MB)** |
| **After tar.xz compression** | **~300MB (314,360,800 bytes)** |

**After slimming (from 0.1.3)**:

| Component | Size |
|---|---|
| `/opt` (Node 22 + preinstalled dsh) | 605 MB |
| `/usr` | 213 MB |
| `/var` + the rest | 44 MB |
| **Total extracted** | **~862 MB (904,335,360 bytes)** |
| **After tar.xz compression** | **142 MB (148,637,136 bytes, −52.7%)** |

> When packing into the APK, second-level compression must be disabled for `xz` in `build.gradle.kts`:
> `androidResources { noCompress += "xz" }` — otherwise packaging is extremely slow and the result is bigger.

## 10. Slimming (2026-10-06): delete in place on the existing archive, do not repack

Inside `rootfs.tar.xz`, **`/opt` (605MB) is already the absolute bulk** (Node 22 + preinstalled dsh);
what can be removed around it is mainly the **build/development environment and documentation**. Method: **do not re-run debootstrap** —
use GNU tar's `--delete` to strip entries in place on the original archive; `/opt` and all symlinks stay exactly as they are:

```sh
# ① Decompress into a bare tar (rootfs.tar.xz is a single-layer xz + tar, about 1.4GB)
xz -dc rootfs.tar.xz > rootfs.tar
cp rootfs.tar rootfs-slim.tar

# ② Strip by "exact path" (★ directory names in the tar carry a trailing slash; writing ./usr/lib/gcc reports Not found)
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

# ③ Remove the leftover symlinks pointing at deleted targets (otherwise you leave dangling links)
tar --delete -f rootfs-slim.tar \
  ./usr/lib/aarch64-linux-gnu/libasan.so.8 ./usr/lib/aarch64-linux-gnu/libtsan.so.2 \
  ./usr/lib/aarch64-linux-gnu/liblsan.so.0 ./usr/lib/aarch64-linux-gnu/libubsan.so.1 \
  ./usr/lib/aarch64-linux-gnu/libhwasan.so.0 ./usr/lib/aarch64-linux-gnu/libcc1.so.0 \
  ./usr/bin/lto-dump-12 ./usr/bin/aarch64-linux-gnu-lto-dump ./usr/bin/lto-dump \
  ./usr/bin/git-upload-archive ./usr/bin/git-receive-pack ./usr/bin/git-upload-pack \
  ./usr/lib/bfd-plugins/liblto_plugin.so ./usr/bin/pdb3.11 ./usr/bin/pdb3

# ④ Compress it back (-T0 uses every core)
xz -T0 -6 -c rootfs-slim.tar > rootfs-slim.tar.xz
sha256sum rootfs-slim.tar.xz   # expect 216cd9ef915a3512b9fedadfed7d5be6119422d120005c86b54d9e3b8ccea3f6
```

**After stripping you must check that "nothing got broken"** (all three were done and are reproducible):

```sh
# ① Are the key paths still there
tar -tf rootfs-slim.tar | grep -qxF ./opt/node22/bin/npm
tar -tf rootfs-slim.tar | grep -qxF ./opt/dsh-preinstalled/node_modules/.bin/dsh

# ② Did any dangling symlinks get left behind (should be 0)
#    Resolve each symlink's relative target → is it inside the archive; absolute targets do not count as dangling (proot translates them inside the guest)
tar -tvf rootfs-slim.tar | grep ' -> ' | awk '{print $6"\t"$8}' > links.tsv   # do not miscount the fields: $6=name $8=target

# ③ Diff the entry lists line by line, confirming "everything deleted is in the list and the list is fully deleted"
tar -tf rootfs.tar      | LC_ALL=C sort -u > list-full.txt
tar -tf rootfs-slim.tar | LC_ALL=C sort -u > list-slim.txt
comm -23 list-full.txt list-slim.txt    # these are exactly what was deleted
```

> ⚠️ **Three pitfalls** (all actually hit):
> 1. **The `--delete` names must match the tar exactly** (directories with a trailing slash, symlinks by their link name) —
>    a mismatch reports `Not found in archive` and exits non-zero, so a script has to decide whether that line is a "real error".
> 2. **On Windows, `tar -xJf` extracting to disk fails and exits on the batch of absolute symlinks under `/etc/ssl/certs`**
>    (`Cannot create symlink to '/lib/...'`). The `--delete` route avoids extraction entirely.
> 3. **Both sides must be `LC_ALL=C sort -u` before a `comm` comparison**, otherwise different locale ordering produces a mass of false differences;
>    also, directory entries carry a trailing slash, so normalize with `sed 's|/$||'` before comparing, or you get another batch of false dangling links.
