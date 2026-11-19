[简体中文](README.md) | English

<div align="center">

# DSHarness-FCL-Launcher

**Run [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (dsh) on an unrooted Android phone.**
A dsh launcher built on top of [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher).

![Version](https://img.shields.io/badge/version-0.1.6--SNAPSHOT-orange?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Android%20arm64-blue?style=flat-square)
![No Root](https://img.shields.io/badge/root-not%20required-success?style=flat-square)
![Tests](https://img.shields.io/badge/unit%20tests-1060%20passing-brightgreen?style=flat-square)
![License](https://img.shields.io/badge/license-GPL--3.0-green?style=flat-square)

**No root** · **No PC** · **No cost** — install one APK and dsh runs on your phone.

**[⬇️ Download the latest build](https://github.com/sqkl520/dsh-fcl-android-launcher/releases)** ·
[Docs index](docs/README-EN.md) ·
[Report an issue](https://github.com/sqkl520/dsh-fcl-android-launcher/issues)

</div>

> ⚠️ **This is an early-stage app built by a high-school student with AI assistance (vibe coding).**
> Issues and suggestions are welcome — please [open an Issue](https://github.com/sqkl520/dsh-fcl-android-launcher/issues).
> If you find it useful, a star means a lot ⭐
>
> **The [Releases page](https://github.com/sqkl520/dsh-fcl-android-launcher/releases) is the source of truth for versions.**
> **The latest publicly released build is `0.1.6-SNAPSHOT-fix26`.**
> This README does not maintain a version number on its own — historically it mentioned stale values
> like `fix4`/`fix6`, and **whenever it disagrees with the Releases page, the Releases page wins**.
> After installing, check the version suffix on the About screen (e.g. `-fix26`) to confirm you
> installed the right package; only files named with `-fixN` are verified builds.

---

## ✨ Features

**Runtime and instance management**

- **Runs without root** — proot provides the Linux environment that runs the Node build of dsh, while `targetSdk` stays at 34 (no downgrade)
- **Multiple instances** — install, start, stop and delete several independent dsh instances without interference
- **Version management** — pulls the version list from the npm registry and installs any dsh version in one tap
- **One-tap launch** — captures the access token automatically and opens the dsh UI inside the app
- **Works in an external browser too** — the instance detail page can open dsh in a browser (with the current credentialed URL) or copy the access link for you to paste yourself
- **Customisable start directory** — the dsh directory picker starts from a directory you choose (the instance directory by default); takes effect after a restart
- **An escape hatch for old WebViews** — when the device WebView is too old, the gate dialog no longer offers only "force it or leave": you can copy the credentialed link into a browser with a newer engine, or tap "Later" to decide another time (an outdated external browser engine will show a blank page too — no guarantee)

**Interface and experience**

- **Three-page shell** — Instances / Versions / Settings, in FCL's original visual style, with light and dark themes
- **Task area** — every install / start / stop is visible both while running and when it ends (done / failed / cancelled)
- **Per-instance log routing** — each instance has its own log plus an app-wide log, both written to disk
- **Three languages** — Simplified Chinese / Traditional Chinese / English
- **Faster UI opens** — dsh front-end static assets are served with `cache-control: immutable`, so reopening the same UI no longer re-downloads everything

## 🚀 Quick start

1. Download the latest APK from [Releases](https://github.com/sqkl520/dsh-fcl-android-launcher/releases)
   (`dsh-fcl-android-launcher-*-arm64.apk`; files with a `-fixN` suffix are the currently verified builds — install those)
2. Install it on an arm64 Android phone (**no root needed**). Reserve roughly **1.5 GB** of free space for the first install
   (the in-app prompt is computed from the same threshold, dynamically)
3. On first launch, wait for the runtime to finish extracting (a few minutes — keep the app in the foreground)
4. Go to **Versions**, install a dsh version → back to **Instances**, start it → enter your API key in the dsh UI and start chatting
   (API keys are managed entirely by dsh itself: dsh UI → Settings → Models)

## ❓ FAQ

| Question | Answer |
|---|---|
| The UI says `dsh web authentication required` | Go back into the app and tap "Open UI" (it attaches the current token automatically). To use an external browser, open that instance's detail page → "Run" → "Copy access link" and paste the credentialed link into the browser. Note that restarting an instance can change both the link and the port, which invalidates the old link |
| It says "WebView is too old" | Update "Android System WebView" (on some devices, Chrome). In a hurry, tap "Copy link" in the gate dialog and paste the credentialed address into an already-updated browser (an outdated external engine shows a blank page too). If you would rather not decide now, tap "Later" to return to the Instances page |
| Blank screen / can't select a model | Same cause — most likely an outdated WebView engine. The newer dsh front end needs 119+ in practice (a community/device-reported value, not a hard-coded gate) |
| How long does the first run take? How much space? | About 1.5 GB free and a few minutes (depending on flash speed). Keeping the app in the foreground or letting the screen lock are both fine during extraction |
| Do I have to start over if a download or install is interrupted? | No — downloaded content is cached, so retrying does not restart from zero |
| Branding / package name | **From fix6**: release package `com.dsh.fcl.androidlauncher` with a dedicated signing key (not debuggable). **fix1–fix5**: verification builds whose package name carries a `.debug` suffix and uses the public debug key — these are **different apps and can coexist** (fix6 does not inherit data from earlier builds) |
| Do scripts need re-extraction after an over-the-top update? | Only when necessary, and automatically: on startup the app compares the local scripts' version marker against the one inside the APK once, and if the local copy is behind it goes straight to the "Preparing runtime" screen and re-extracts just the `scripts` subdirectory (about 45 KB, sub-second) **without touching rootfs**. This path only became live in fix10 (earlier users would not have triggered it on upgrade). **fix22 changed the scripts** (a new front-end asset caching plugin), so upgrading from an earlier build really does trigger one re-extraction |

## 🔧 How it works

```
Android App (this repository)
   └─ proot (rootless Linux environment emulation, syscall translation via ptrace)
        └─ Linux rootfs (Debian 12 bookworm arm64, bundled Node 22 + preinstalled dsh)
             └─ Node.js
                  └─ dsh (DeepSeek Harness)
                       └─ calls the DeepSeek cloud API over HTTPS
```

The UI follows FCL's visual style and GUI framework (`fcllibrary` + `ThemeEngine`).

### Tech stack

| Layer | What it uses |
|---|---|
| App | Kotlin (68 source files) + Java (410 from the FCL base), Android SDK 34 |
| Linux environment | **proot** (`libproot.so` + `libproot-loader.so`), no root required |
| Distribution | **Debian 12 bookworm arm64** (about 148 MB after slimming, with Node 22) |
| Runtime | **Node.js 22** + npm, used to install dsh and its dependencies |
| UI | Android **WebView** loading `http://127.0.0.1:<port>/` |
| Tests | Home-grown MiniRunner (1060 unit tests) + runtime script self-check (33 checks) |

For technology choices see [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT-EN.md);
for the rootless mechanism, see "W^X bypass" below.

<details>
<summary><b>Key design: W^X bypass (PROOT_LOADER) — why we don't lower targetSdk</b></summary>

Android 10+ enables **W^X** for apps with `targetSdk ≥ 29`: it forbids `execve` on files inside the
app's data directory, while proot needs to execute the rootfs's `/bin/sh` and `node` from there.

**Solution**: when executing rootfs programs, proot goes through its own **loader** (the `PROOT_LOADER`
mechanism). The loader is shipped in the APK's `jniLibs` (installed into `nativeLibraryDir`, which SELinux
allows executing), so **there is no need to drop targetSdk to 28**. See
[`docs/design/wx-exec-proot-loader.md`](docs/design/wx-exec-proot-loader.md).

> This mechanism was verified on real hardware (Android 16 / SDK 36) by the similar project
> [`oonid/pr`](https://github.com/oonid/pr).

</details>

## 📌 Current status

> Last updated: 2026-11-19 (fix26). The single source of truth for version numbers is `dshVersion` /
> `dshVersionCode` in `gradle.properties`.

| Area | Status |
|---|---|
| FCL conversion: all Minecraft code and resources removed | ✅ Done (about −226k lines) |
| App shell: three pages + instance detail + right panel + dynamic island | ✅ Done |
| Runtime base: proot + rootfs + PROOT_LOADER | ✅ Integrated, verified on device |
| End-to-end on device: install → start → stop → restart → chat | ✅ Verified (0.1.6, 2026-10-11) |
| Fix rounds fix1 ~ fix9 | ✅ All released (fix5 was verified locally only and never uploaded to Releases — see the "fix5, historical verification build" section of [`docs/RELEASES.md`](docs/RELEASES-EN.md)); current state on [Releases](https://github.com/sqkl520/dsh-fcl-android-launcher/releases) |
| Fix round **fix10** (R23 review rework: four-branch gate dialog + no blank screen after copying + script version comparison wired up) | ✅ Released (code 116, see Releases) |
| Fix round **fix11** (R24 state machine: startup retry and stop reliability) | ✅ Released (code 117, see Releases) |
| Fix round **fix12** (R25 unified criteria: one definition of "is it installed" + failures no longer swallowed) | ✅ Released (code 118, see Releases) |
| Fix round **fix13** (R26 item 3: tests upgraded from "pinning implementation" to "contract + pipeline") | ✅ Released (code 119, see Releases) |
| Fix round **fix14** (R26 wrap-up: cross-implementation consistency tests drop the mirrored implementation, add mutation verification) | ✅ Released (code 120, see Releases) |
| Fix round **fix15** (R27: can't pick a workspace in an external browser — `HOME` points at the instance workspace) | ✅ Released (code 121, see Releases) |
| Fix round **fix16** (R27: open in browser + stable ports, so an external browser can stay authenticated) | ✅ Released (code 122, see Releases) |
| Fix round **fix17** (R27: `HOME` points at the instance directory + customisable start directory) | ✅ Built (code 123) |
| Fix round **fix18** (R28 review rework: comment corrections + reject `..` + auto-create custom directories) | ✅ Built (code 124) |
| Fix round **fix19** (R28 second review: ghost-instance protection + call-site contract tests) | ✅ Built (code 125) |
| Fix round **fix20** (R28 third review: API key exposure warning + diagnosable empty-directory typo) | ✅ Built (code 126) |
| Fix round **fix21** (R28 C3 user perspective: warn on empty directories + correct three inaccurate explanations) | ✅ Built (code 127) |
| Fix round **fix22** (R29: stop re-prompting the WebView gate after copying the link + cache headers for front-end assets, so the UI no longer re-downloads every time) | ✅ Built (code 128) |
| Fix round **fix23** (R31+R32: URL adoption gate, page teardown/rebuild, script version marker) | ✅ Built (code 129) |
| Fix round **fix24** (R33–R44: stricter log redaction, atomic pid/overlay/delete transactions, orphan cleanup no longer fails silently) | ✅ Built (code 130) |
| Fix round **fix25** (R45: gate dialog body no longer clipped at large font sizes, and it scrolls) | ✅ Built (code 131) |
| Fix round **fix26** (R45: `launcher.patch.yml` is no longer overwritten wholesale, so your configured providers survive) | ✅ Built (code 132, current verified build) |
| `1.0.0` (can reliably start / download / manage dsh) | ⬜ Pending promotion from 0.1.6 |

**Quality baseline**: build BUILD SUCCESSFUL · unit tests **1060/1060** · script self-check 33/33
(full suite runs every round; every key fix is pinned by assertions)

> The single source of truth for version numbers remains `dshVersion` / `dshVersionCode` in
> `gradle.properties`; **the downloadable builds are whatever the Releases page lists**. This table
> only tracks round progress and does not promise which fixN has been publicly released.

## 📦 Building

**Requirements**: JDK 17, Android SDK 35, NDK 27; toolchain wiring in [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT-EN.md) §1.

```powershell
# On Windows (the primary environment today), use Gradle directly:
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:compileDebugKotlin :FCL:compileDebugJavaWithJavac `
  :FCL:processDebugResources :FCL:processDebugMainManifest
.\gradlew.bat --no-daemon :FCL:testFordebugUnitTest           # unit tests
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:assembleRelease   # release build (rootfs must be prepared first)
```

> Shipping uses `assembleRelease` (since T-D1: release package name / not debuggable / dedicated key;
> key wiring is in the key section of `docs/PACKAGING.md`). The debug channel `assembleFordebug`
> (debuggable, for grabbing logs) is still available.

On the phone (arm64 Linux sandbox), use `run-compile.sh` / `run-tests.sh` / `build-apk.sh` from the same
directory (plain POSIX sh, detects the source tree automatically); `sh release.sh` produces a full build
in one command.

> These `run-*.sh` / `build-apk.sh` / `release.sh` scripts live in the **workspace snapshot repository
> root**, not in the Android source repository clone (the source repository root has no `.sh` files at all).
> If you only cloned the source repository, just run the Gradle commands above — they do exactly the same
> thing; details in [`CONTRIBUTING.md`](CONTRIBUTING-EN.md) §6.2.

Before packaging you must provide `FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz` (142 MB, not in Git;
the easiest way to obtain it is described in [`docs/ROOTFS.md`](docs/ROOTFS-EN.md)).
**APKs are not committed to Git**: new versions ship as GitHub Release assets.

## 🤝 Development and contributing

This is a small project a high-school student builds in their spare time. The code is not beautiful,
but every fix round is pinned by unit tests. If you would like to make it better — you are welcome
to join in; Issues and PRs are both accepted.

- **Contributing guide**: [`CONTRIBUTING.md`](CONTRIBUTING-EN.md) — how to report problems, how to send PRs, code style and test requirements.
- **Build and test**: `.\gradlew.bat --no-daemon :FCL:testFordebugUnitTest` (Windows, unit tests);
  `sh run-compile.sh` → `sh run-tests.sh` (arm64 Linux / phone sandbox, compile check + unit tests). Environment wiring in [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT-EN.md).
- **Issues and PRs**: when reporting a bug please include the **version suffix shown on the About screen**
  (e.g. `0.1.6-SNAPSHOT-fix26`), device model, Android version and reproduction steps. If you want to change
  code, open an Issue first so your work is not wasted.

[![Contributors](https://contrib.rocks/image?repo=sqkl520/dsh-fcl-android-launcher)](https://github.com/sqkl520/dsh-fcl-android-launcher/graphs/contributors)

This project is based on [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher) and is likewise released under **GPL-3.0** (see [`LICENSE`](LICENSE)).

## 📚 Documentation

> The complete documentation index is in [`docs/README.md`](docs/README-EN.md)

| Document | Contents |
|---|---|
| [`SECURITY-EN.md`](SECURITY-EN.md) | **Security policy**: permission surface, where it connects, where credentials live, what this launcher cannot protect against |
| [`docs/PLAN.md`](docs/PLAN-EN.md) | Project charter and overall design |
| [`docs/ROADMAP.md`](docs/ROADMAP-EN.md) | Milestones M1–M6 |
| [`docs/TASKS.md`](docs/TASKS-EN.md) | To-do list |
| [`docs/PACKAGING.md`](docs/PACKAGING-EN.md) / [`docs/ROOTFS.md`](docs/ROOTFS-EN.md) | Packaging notes / rootfs build and slimming |
| [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT-EN.md) | Development environment wiring (JDK / SDK / NDK) |
| [`docs/design/app-shell.md`](docs/design/app-shell.md) | App shell conversion design (including the hard rule that the UI follows FCL) |
| [`docs/design/wx-exec-proot-loader.md`](docs/design/wx-exec-proot-loader.md) | W^X restrictions and the PROOT_LOADER bypass |
| [`docs/design/proot-engine-integration.md`](docs/design/proot-engine-integration.md) | **【Rejected alternative】** kept as a decision record |
| [Releases](https://github.com/sqkl520/dsh-fcl-android-launcher/releases) | **APK downloads** (public repository, no token needed) |

## ⚖️ License and acknowledgements

This project is based on [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher) (**GPL-3.0**)
and is therefore also released under **GPL-3.0**. See [`LICENSE`](LICENSE).

The `libproot.so` / `libproot-loader.so` / `libbusybox.so` bundled into the APK and the `ptyjni` bridge come
from [`oonid/pr`](https://github.com/oonid/pr) (`:proot-engine` and `pr-cli` are MIT; the patched proot itself
is GPL-2.0-or-later). This project is already GPL-family, so this adds no new obstacle.
> Note: the integration of `:proot-engine` as a **Gradle module** was **ultimately not adopted** (see
> `docs/design/proot-engine-integration.md`); what is referenced here is the **origin of the binaries and
> the PTY bridge**.

- [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher) — UI framework and starting point for the conversion
- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) — the agent this launcher hosts
- [oonid/pr](https://github.com/oonid/pr) — proot adaptation for targetSdk 35+ (PROOT_LOADER / seccomp / CLONE stripping)
- [proot](https://github.com/proot-me/proot) / [Termux](https://github.com/termux) — rootless Linux environments

## ⭐ Star History

<a href="https://www.star-history.com/?repos=sqkl520%2Fdsh-fcl-android-launcher&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=sqkl520%2Fdsh-fcl-android-launcher&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=sqkl520%2Fdsh-fcl-android-launcher&type=date&theme=light&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=sqkl520%2Fdsh-fcl-android-launcher&type=date&legend=top-left" />
 </picture>
</a>
