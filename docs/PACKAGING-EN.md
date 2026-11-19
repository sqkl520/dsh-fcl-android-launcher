[简体中文](PACKAGING.md) | English

# Packaging notes (runtime base + building an APK)

> ## ⚠️ Partially outdated (marked 2026-10-01, reviewed 2026-10-05)
>
> **What is void**: the entire section at the end, 〔Building a complete APK on arm64 (qemu forwarding the NDK toolchain)〕 —
> that qemu wrapping trick is only needed on **arm64 Linux**. When developing on **Windows x64**,
> the cmake/ninja/clang bundled with the SDK run natively; **no qemu is involved**.
>
> **About `:proot-engine`**: `design/proot-engine-integration.md` once planned to switch to it,
> but it was **ultimately not adopted** — the project kept the "home-grown proot layer + pre-packaged rootfs" route (stages A~E-1 have landed,
> and 0.1.1 already shipped an APK). So the "runtime base" chapters below are **still applicable**; just follow the 「Windows packaging」 section below when operating.
>
> ---

> This document has two parts:
> 1. **Runtime base**: how to prepare the two kinds of large platform files that go into the APK (the proot binaries and the rootfs archive),
>    where they go, and what happens after first launch.
> 2. **Building an APK**: how to produce a complete, usable arm64 APK (native libraries included). See 〔Windows packaging〕 and the section at the end, 〔Building a complete APK on arm64〕.
>
> Corresponding code: `FCL/src/main/assets/dsh/` (extracting the base) + `com.dsh.core.DshBootstrap` (first-launch extraction and self-check).
> That assets directory also holds a minimal note, `FCL/src/main/assets/dsh/README.md`, which points to this document.

```powershell
# Prerequisite: toolchain installed per ENVIRONMENT-EN.md; rootfs prepared per ROOTFS-EN.md:

#       <repo root>/FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz   ← 142MB, not in Git

cd <repo root>
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:assembleRelease
```

Output: `FCL/build/outputs/apk/release/dsh-fcl-android-launcher-<ver>-arm64.apk`.

> **T-D1 (since 2026-10-19)**: shipping goes through the **release variant** (release package name, not debuggable,
> signed with the new private key `release-key/dsh-release.jks`; the password is `dsh.pwd` in `local.properties`,
> a file that, like `release-key/`, is excluded from both repositories).
> The `fordebug` variant is kept as the **internal debug channel** (debuggable + `run-as` to grab logs, see `tools/ondevice.md`);
> calling `:FCL:assembleFordebug` produces a debug build on its own. `build-apk.sh` has already switched to release and carries the three shipping checks
> (package name has no `.debug` / not debuggable / versionName matches).

**Packaging notes**:
- `build.gradle.kts` already declares `noCompress += listOf("xz")`, so the rootfs is **STORED** (uncompressed) inside the package —
  this both avoids aapt's second compression slowing packaging down, and lets us **extract the rootfs losslessly straight out of the APK** (see `ROOTFS-EN.md`)
- Packaging also succeeds when the rootfs is not in place, but the result **will not run** once installed on a phone (the base is a PLACEHOLDER)

## Where outputs land (delivery / version records / build artifacts)

The three locations each have their own job — **do not mix them**:

| Location | Purpose | In Git |
|---|---|---|
| `<repo root>/output/` | **local delivery / pickup** directory; accumulates every historical build; **never deleted** (see REPOS.md) | No (.gitignore'd) |
| `<repo root>/apk-archive/<version>/` | **version records**: only `SHA256SUMS` (fingerprints) | **Yes** |
| `<repo root>/FCL/build/outputs/apk/` | raw Gradle output; the packaging script **moves** it to `output/` | No |

> User-facing **APK distribution goes through GitHub Release assets** (not into Git history), see
> <https://github.com/sqkl520/dsh-fcl-android-launcher/releases>.


Naming rule: `dsh-fcl-android-launcher-<version>-arm64.apk` (e.g.
`dsh-fcl-android-launcher-0.1.6-SNAPSHOT-arm64.apk`; files with a `-fixN` suffix are fix verification builds of the same version,
e.g. `…-0.1.6-SNAPSHOT-fix1-arm64.apk`).

`SHA256SUMS` records only the **file name** (the same name in both places), so both `output/` and `apk-archive/<version>/`
can be verified directly with `sha256sum -c`.

> ⚠️ **Do not use `/tool_outputs`**: it is not a mount point, just a temporary directory inside the sandbox, and does not exist on device.
> Early packaging once put APKs there; it was later unified into `output/`.

After the second hardening round (2026-09-22), `version` and `scripts/version` in assets were raised to **2**
(a change in a sub-item version triggers incremental re-extraction). **The scripts have been iterated many times since, and `scripts/version` is currently 13**;
the rootfs `version` has changed to a version-string form (`debian-…-slim1`, see `ROOTFS-EN.md`) and is no longer an integer.

> **fix22 (code 128)** added `scripts/launcher-assets.mjs` and bumped `scripts/version` from 11 to 12 —
> so upgrading from fix21 or earlier **will** trigger one `scripts` re-extraction (about 45 KB, seconds, rootfs untouched).
> The `scripts` directory now contains: `probe.sh` / `setup-node-dsh.sh` / `start-dsh.sh` / `launcher-assets.mjs` / `version`.

## The `--patch` overlay (the launcher's own configuration layer)

Before starting, the launcher writes `launcher.patch.yml` into the **instance directory** (the content is JSON — JSON is valid YAML);
when `start-dsh.sh` sees that file it passes it in as `dsh web --patch <that file> --no-open ...`:

- generated by `com.dsh.core.DshInstances.writeLauncherPatchOverlay()`, with two entries: mount the `launcher-assets`
  plugin (`/assets` cache headers), and raise `client-hmr.pollIntervalMs` from 500 to 5000 (saves power, no functional impact);
- it **does not write** `$DSH_HOME/cordis.patch.yml` — that is dsh's user layer and would take effect for all of the user's profiles;
- atomic write (`.tmp` + rename): a half-written file makes dsh fail to start outright;
- a failed write / a missing file **does not affect startup** (the script then simply does not pass `--patch`); if it exits before becoming ready while `--patch` was used,
  the script drops it and retries once; `--patch` must come after `web` and before `--no-open`.

## Layout (inside the `FCL/src/main/assets/dsh/` directory)

```
dsh/
  version               base overall version (informational; extraction is incremental per sub-item version)
  proot/
    version            proot sub-item version
    PLACEHOLDER.txt    [placeholder] proot is actually shipped via jniLibs (see option A below); no binaries live in assets
  rootfs/
    version            rootfs sub-item version (version-string form, e.g. `debian-…-slim1`)
    rootfs.tar.xz      [in place · not in Git] Debian 12 bookworm arm64 + Node 22 + preinstalled dsh (142MB after slimming, see ROOTFS-EN.md)
  scripts/
    version            (currently 13)
    setup-node-dsh.sh  in place (prints STAGE=... progress; hard verification after install)
    start-dsh.sh       in place (prints READY url=/FAILED reason=...; signal forwarding; supports PORT=0;
                       passes launcher.patch.yml to dsh via `--patch` when present, and retries once without it if it cannot start with --patch)
    probe.sh           in place (self-check probe, called by DshBootstrap.verify)
    launcher-assets.mjs in place (**a dsh plugin**, not shell: adds cache-control: immutable to /assets)
```

## The two kinds of large platform files

The code paths (extraction, setting the executable bit, incremental update, atomic replacement, rootfs layout self-healing, self-check, proot command assembly) are all in place
and have passed compile verification. **Both kinds of files are now in the repository**: the three proot pieces are in `FCL/src/main/jniLibs/arm64-v8a/`
(already in Git), and the rootfs archive is in `FCL/src/main/assets/dsh/rootfs/` (142 MB, not in Git, you prepare it yourself).
What follows describes **how to re-prepare them when changing machines / versions**.

### 1) proot binaries (**current state: already in jniLibs, option A**)

**Option A (current): ship them as jniLibs**
- `libproot.so`, `libproot-loader.so` and `libbusybox.so` all live in `FCL/src/main/jniLibs/arm64-v8a/` and are in Git.
- Naming uses a **hyphen**, `proot-loader` (the old assets spelling with an underscore, `_loader`, is abandoned; the code's fallback path
  is unified on the hyphen as well).
- During installation the system extracts them into `nativeLibraryDir` and **sets the executable bit itself** — the most robust route.
- `DshPaths.resolveProotBin/Loader` prefer this location; `ProotCommand.preflight` therefore passes as well.
- Under `dsh/proot/` there is only a `PLACEHOLDER.txt` and a `version`; **no binaries**.

**Option B (not recommended, historical note only): ship them as assets**
- Put the binaries into the assets directory: `FCL/src/main/assets/dsh/proot/libproot.so`, `.../libproot-loader.so`.
- `DshBootstrap` calls `setExecutable(true)` after extraction. But note: **since targetSdk ≥ 29, executables in the app's private data directory
  are generally restricted by W^X/SELinux, and exec fails on most newer devices**.
- In that case the launcher **no longer fails silently**: the preflight check tells you directly "the proot binary has no execute permission, ship it via jniLibs instead",
  and the self-check (`DshBootstrap.verify` / "Runtime self-check" on the Settings page) reports the concrete exit code and output.

proot binary sources: prebuilt artifacts from the proot-distro project, termux's proot package, or cross-compile it yourself.

### 2) rootfs.tar.xz

> **Current state**: already done — **Debian 12 bookworm arm64 + official Node v22.23.3 + preinstalled `@deepseek-ai/dsh`**,
> right in `FCL/src/main/assets/dsh/rootfs/` (**142MB** after slimming, not in Git; sha256 `216cd9ef…`).
> If you lose it, see `ROOTFS-EN.md` for how to restore it. Below is the rationale for a from-scratch rebuild.

- An arm64 **Debian** rootfs (glibc; dsh's native modules only ship glibc/musl builds, Android's bionic cannot run them — see `PLAN.md §1`).
- **It is strongly recommended to preinstall Node 22/24 into the rootfs before packaging**, saving the first-launch online Node install.
- Pack it as `.tar.xz` (`DshBootstrap` extracts it with `RuntimeUtils.uncompressTarXZ`, which handles symlinks correctly).
- Put it at `dsh/rootfs/rootfs.tar.xz`.
- **The archive must be in "root" form** (top level is `bin/`, `usr/`, `etc/`…). If an extra directory layer sneaks in
  (e.g. the top level is `ubuntu/`), `DshBootstrap` **hoists it automatically** after extraction and logs one line;
  if it is still unusable it gives a clear error instead of letting proot throw an obscure one.

> Note: large files like the rootfs archive (tens to hundreds of MB) are not tracked in this repository. After placing it, change the
> corresponding sub-directory's `version` to trigger re-extraction (**changing the `scripts/` or `rootfs/` version only re-extracts that item**;
> `scripts/version` is an increasing integer, `rootfs/version` is a version string compared as a string).

## What happens after first launch (for troubleshooting)

1. `DshBootstrap.install()`: when the rootfs needs extracting it first checks disk space (about **1.5 GB** free for a first install /
   about **960 MB** when only upgrading the rootfs; otherwise it reports that directly) — the threshold comes from `DshBootstrap.requiredFreeBytes`,
   it is not a fixed constant, so do not hard-code some other number here
   → then handles each item: **scripts** are compared by version and extracted only if behind; **proot/loader are no longer extracted from assets**
   (the three jniLibs are taken straight from `nativeLibraryDir`, and a missing one is reported by name); **rootfs** is extracted into
   `rootfs.tmp`, verified, then atomically replaced.
2. **Self-check**: run `scripts/probe.sh` inside proot; it must print `dsh-probe-ok`;
   otherwise it reports "self-check failed: <reason>" (this step surfaces missing proot / permission / loader / rootfs layout problems before installing dsh).
3. Install dsh: `setup-node-dsh.sh` prints `[setup] STAGE=...` for the UI to show progress;
   the npm cache is shared at `<filesDir>/dsh/npm-cache`.
4. Start: before launching, `launcher.patch.yml` is written into the instance directory (see above); once `start-dsh.sh` detects readiness it prints
   `[start-dsh] READY url=http://127.0.0.1:<port>/?token=...`.

## Keys and tokens (security conventions)

- **The shipping signing private key is not in the repository (T-D1, since 2026-10-19)**: `release-key/dsh-release.jks` (password in
  `dsh.pwd` in `local.properties`) — both repositories' exclusion lists have `/release-key/` added; this machine additionally keeps
  an offline backup at `D:\Backup\dsh-release-key-2026-10-19\` (restore from the backup when changing machines / for disaster recovery; do not rely on a single location).
  The `debug-key.jks` used to sign old builds (same blob as the FCL upstream one, public password) **is now used only for the fordebug debug channel**.
- **The API key no longer touches disk and no longer passes through the launcher**: since 0.1.5 the key is managed entirely by dsh itself
  (the launcher **no longer stores it, no longer injects it, no longer checks it before starting**; the old `credentials.env` / `credentials.enc`
  are deleted automatically at startup, see the 0.1.5 upgrade notes in `RELEASES.md`).
  The historical approach of "passing the key through a subprocess environment variable" was removed along with 0.1.5 — only non-sensitive items go through environment variables.
- **The startup token never enters the logs**: every line entering the log bus (including the on-disk `logs/runtime.log`) is redacted to `token=***`.
- `dsh.pid` records only `pid` and `port`, never the URL/token.

---

## Building a complete APK on arm64 (qemu forwarding the NDK toolchain)

**Problem**: FCL has a native CMake build (46 C/C++ sources producing `libfcl.so`, `libpojavexec.so` and 12 libraries in total).
But the Android SDK/NDK build toolchain (`cmake`, `ninja`, `clang`, `ld.lld`) **is x86_64-only**,
the build machine is aarch64, and `binfmt_misc` cannot be registered under proot. Running AGP's `externalNativeBuild` directly
crashes in `configureCMakeDebug[arm64-v8a]` (CMake is exec'd directly as x86_64 → SIGILL, exit code 132).

**Solution** (verified): use `qemu-x86_64-static` to **transparently forward the whole NDK toolchain**, and use
**native arm64 cmake/ninja** for configuration and driving, bypassing the step where AGP itself calls the x86_64 cmake in the SDK.

### Key points

1. **Wrap the NDK toolchain**: replace the x86_64 ELFs under `${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/`
   with wrapper scripts (renaming the real binaries `*.real`):
   ```sh
   #!/bin/sh
   exec /usr/bin/qemu-x86_64-static -0 "$0" <absolute path>/<name>.real "$@"
   ```
   - **`-0 "$0"` is mandatory**: `lld` decides its driver type from `argv[0]` (`ld.lld`/`lld-link`…), and
     `clang` enters C++ mode based on the name `clang++`. Without `-0` you get
     `lld is a generic driver` / or everything compiled as C.
   - **Skip symlinks**: `clang` → `clang-18` and `ld.lld` → `lld` are symlinks;
     wrap only real files, otherwise you get "double wrapping", where `clang.real` points at a script and qemu errors out.
   - `-0 "$0"` also preserves the full invocation path, from which clang resolves `InstalledDir` to find the linker in the same directory.

2. **Native cmake/ninja**: `apt install cmake ninja-build` (this is the aarch64-native one, not slowed down by qemu).
   Pass `-DCMAKE_MAKE_PROGRAM=/usr/bin/ninja` when configuring.

3. **prefab dependency**: `find_package(bytehook REQUIRED CONFIG)` in `CMakeLists.txt` comes from AGP's prefab.
   First run the repository once so AGP generates `FCL/.cxx/<variant>/<hash>/prefab/<abi>/prefab`,
   then add `-DCMAKE_FIND_ROOT_PATH=<that prefab directory>` when configuring.
   (When it is missing you can also hand-assemble `bytehookConfig.cmake` from the `bytehook-*` directory in the Gradle cache.)

4. **Provide `libc++_shared.so`**: `libandroidnsbypass.so` depends on it; take it from
   `$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so`
   and put it into the jniLibs staging directory along with the built libraries.

5. **Hand it to AGP for packaging**: temporarily comment out the two `externalNativeBuild` blocks in `build.gradle.kts`,
   add `sourceSets.getByName("main") { jniLibs.srcDir("/tmp/fcl-jnilibs") }`,
   then `./gradlew --no-daemon -Darch=arm64 :FCL:assembleFordebug`. **Remember to restore `build.gradle.kts` afterwards**.
   (At the time the shipping channel was still `fordebug`; shipping now goes through `assembleRelease`, see the top of this document and 〔Windows packaging〕.)

### Measured result (2026-09-29)

- Toolchain wrapping: 38 real binaries under the NDK bin were wrapped (symlinks preserved).
- Native configure: Clang 18.0.1 ABI detection passed, 14 seconds.
- Native build: **58 build steps finished in 43 seconds**, producing 12 arm64 `.so` files.
- Packaging: `assembleFordebug` BUILD SUCCESSFUL, producing
  `FCL-fordebug-1.3.3.3-arm64-v8a.apk` (about 189MB, signed).
- Content verification: **35** arm64 libraries inside the APK (including the previously missing `libfcl.so`/`libpojavexec.so`/
  `libpojavexec_awt.so`/`libc++_shared.so`), **30** dsh classes in dex, and all dsh assets present in the package.

> One-command reproduction: `sh build-apk.sh` (idempotent wrapping, configure, compile, staging, packaging, restore).
> Disk requirement: about 3~4GB of headroom during the build.
