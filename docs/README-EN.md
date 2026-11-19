[简体中文](README.md) | English

# Documentation index

> ## 📦 Project
> **Repository**: <https://github.com/sqkl520/dsh-fcl-android-launcher>
> **Download**: <https://github.com/sqkl520/dsh-fcl-android-launcher/releases>
>
> This project is **based on [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher) (GPL-3.0)**
> and is likewise released under **GPL-3.0**.
>
> **Current version: `0.1.6-SNAPSHOT`** (code 132; fix26 has been built; for the latest build see the top of
> [`RELEASES.md`](RELEASES.md)) — it will not be labelled `1.0.0` until dsh can be started, downloaded and
> managed reliably.

> **A launcher for DeepSeek Harness (dsh) on unrooted Android phones.**
> proot provides the Linux environment that runs the Node build of dsh; the UI follows FCL's visual style and
> widget system.

---

## Documentation map

| File | Contents | When to read it |
|---|---|---|
| `RELEASES.md` | **Release notes (for users)**: what each version adds, changes, fixes, and upgrade notes; GitHub Release bodies are taken from here | You want to know "what changed in this version, should I upgrade" |
| `RELEASE-NOTES-GUIDE.md` | **Release-note writing rules**: structure, eight hard rules, self-check list. **Shared by every product in this project** | You are writing or editing any release notes |
| [`../SECURITY-EN.md`](../SECURITY-EN.md) | **Security policy**: which permissions are requested, where it connects, where credentials live, and **what this launcher cannot protect against** (the rootfs is not a sandbox) | You want to know "is this thing safe, and where does my API key live" |
| `PLAN.md` | **Overall design**: architecture, directory layout, key flows, security design | You want the big picture |
| `ROADMAP.md` | **Roadmap**: milestones M1–M6 (device bring-up → stability → experience → engineering → distribution → extension) | You want to know where this is heading |
| `TASKS.md` | **To-do list**: things clearly intended but not yet done | You want to know what is still missing |
| `PACKAGING.md` | **Packaging notes**: how to prepare the runtime base (proot / rootfs) and build an APK | You want to build it yourself |
| `ROOTFS.md` | **rootfs build and slimming**: the full steps to produce Debian + Node + preinstalled dsh, with reproducible slimming commands | You need to rebuild or trim the runtime environment |
| `ENVIRONMENT.md` | **Development environment**: JDK / Android SDK / NDK requirements and wiring | You are setting up a compiler toolchain |
| `design/app-shell.md` | **App shell design**: converting an MC launcher into a dsh launcher; **§2.5 = UI, buttons and theme always follow FCL** | Required reading before changing UI |
| `design/wx-exec-proot-loader.md` | **W^X restrictions and the PROOT_LOADER bypass**: how to execute programs inside the rootfs under targetSdk 34 | You want to know "why not just lower targetSdk" |
| `design/multi-version.md` | **Multi-version / multi-instance management** design | You care about instances and version downloads |
| `design/proot-chain.md` | A reproducible proot launch chain | You care about "how to get Linux running on Android" |
| `design/proot-engine-integration.md` | **【Rejected alternative】** a second proot integration approach, kept as a decision record (its W^X reasoning is still valid) | You want to understand the technology trade-offs |
| `design/ui-manifest.md` | UI assembly + Manifest registration + layered verification method | You care about UI and component registration |
| `design/fcl-ui-restoration.md` | **Faithful FCL UI restoration plan**: structure mapping, keep/delete/replace lists, acceptance criteria | Before doing UI restoration |
| `design/setup-flow-and-task-area.md` | First-run prerequisite page + home task area design | Before working on these two features |

---

## Current status

- **Code**: `com/dsh/` **44 files** (`core` 22 + `ui` 22) + the FCL base
  - `com/tungsten/fcllibrary/` 44 (UI framework, keeps the FCL look)
  - `com/tungsten/fclcore/` 373 (`fakefx` / `util` / `task` / `event`)
  - `com/tungsten/fcl/` 4 (`FCLApp` / `SplashActivity` / `RuntimeUtils` etc.)
  - `com/tungsten/fclauncher/utils/` 2, `com/mio/util/` 5, `ZipFileSystem/` 12 (+ 2 resource files)
- **Resources**: `FCL/src/main/res` 105 files · assets hold only `dsh/` (3 POSIX sh scripts + 1 front-end
  caching plugin `launcher-assets.mjs` + `version` markers at each level; `rootfs.tar.xz` is not in Git) ·
  3 jniLibs (`libproot.so` / `libproot-loader.so` / `libbusybox.so`)
- **Gradle modules**: `:FCL` + `:ZipFileSystem`
- **Verification**: build (Kotlin+Java+resources+Manifest) **BUILD SUCCESSFUL**; unit tests **1060/1060**;
  script self-check **33/33**
- **Entry points**: `SplashActivity` → first-run prerequisite page (`DshSetupActivity`, when the base is not
  ready) → **`DshMainActivity`** (three-page shell: Instances / Versions / Settings; no MC runtime gate,
  no EULA, no MC main screen)

### Runtime base

**A home-grown proot layer + a pre-packaged rootfs** (Debian 12 + Node 22 + preinstalled dsh).
**targetSdk stays at 34**, using **PROOT_LOADER** to bypass the W^X execution restriction introduced in
Android 10+ (see `design/wx-exec-proot-loader.md`).

The rootfs was slimmed on 2026-10-06: `rootfs.tar.xz` **314 MB → 142 MB (−52.7%)**, extracted
1436 MB → 862 MB; the APK correspondingly went from 311 MiB to 153 MiB (steps in `ROOTFS.md` §10).

---

## Where the code lives

| Location | Description |
|---|---|
| `FCL/src/main/java/com/dsh/` | **The dsh launcher itself** (`core` 22 + `ui` 22), 44 files |
| `FCL/src/main/java/com/tungsten/` | The FCL base (`fcllibrary` UI framework + `fclcore` utilities) |
| `FCL/src/main/assets/dsh/` | Runtime base: `scripts/` (3 POSIX sh + `launcher-assets.mjs`) + `rootfs/` (tar.xz not in Git) |
| `FCL/src/test/java/com/dsh/` | JVM unit tests |
| `docs/` | This directory |
