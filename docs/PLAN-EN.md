[简体中文](PLAN.md) | English

# DeepSeek Harness Android Launcher — Complete Implementation Plan

> ## ⚠️ Staleness notice (annotated 2026-10-01, corrected 2026-10-06)
>
> This document is the **master plan from the early stage**, written before the shell rework. Many
> descriptions have already been implemented or have changed route, and **it is preserved verbatim only
> so that the decisions can be traced**. For current status see:
>
> | What you want to know | Which file to read |
> |---|---|
> | Current status / version / where the code is | `README.md` (this directory) |
> | What to do next, and in what order | `ROADMAP.md` |
> | The runtime base's **current implementation** (home-grown proot layer + pre-packaged rootfs) | `PACKAGING.md` + `ROOTFS.md` |
> | Technology choices and trade-offs | `docs/PLAN.md` |
>
> **Three corrections** (where this document conflicts with the newer documents, the following wins):
> 1. **The runtime base did not switch to `:proot-engine`** — on 2026-10-01 a decision was briefly made to
>    integrate oonid/pr's `:proot-engine`, but it was **ultimately not adopted**; the project continues with
>    "home-grown proot layer + pre-packaged rootfs" (phases A~E-1 are done).
>    `design/proot-engine-integration.md` is kept as a **rejected alternative route**. **The conclusion —
>    keep targetSdk at 34 and bypass W^X with PROOT_LOADER — is unchanged** (see
>    `design/wx-exec-proot-loader.md`).
> 2. **Most sections of this document are now implemented or out of date**: the app shell (the description
>    in §Architecture of "an MC launcher + long-press to enter dsh"), the proot launch chain, rootfs
>    preparation (a ready-made rootfs is already in place, see `ROOTFS.md`), and the on-device integration
>    steps (§8.5). Among them, **the "fill in the large proot/rootfs files" step in §8.5 is no longer
>    necessary** (the files are in place); the other steps are still usable for reference.
> 3. **The API Key no longer goes through the launcher** (2026-10-07, since 0.1.5): §4.2's
>    `credentials.env` / `credentials.enc`, §4.3's `DshCredentials.kt`, the write and wipe in §5.2 steps
>    1/6, and the whole key-management design in §6 are **all void** —
>    the Key is managed entirely by dsh itself (entered in the dsh UI), `DshCredentials.kt` has been
>    deleted, and the old files are cleaned up automatically at startup.
>    See the "Changes" and "Upgrade notes" of 0.1.5 in `RELEASES.md`.

---

> Build a launcher/manager app for DeepSeek Harness (dsh) on an ordinary, unrooted Android phone.
> Borrowing FoldCraftLauncher (FCL)'s techniques; models go through the DeepSeek cloud API, and the
> phone only runs dsh itself plus the UI.
>
> This document consolidates the design and measured conclusions of Task 1 (proot launch chain),
> Task 2 (multi-version / instance management + download) and Task 3 (runtime keep-alive + WebView)
> into a single implementation reference.

---

## 0. TL;DR

- **Feasibility has been proven in practice**: in an equivalent environment (aarch64 + proot + Ubuntu 24.04/glibc + Node 22.23),
  dsh's arm64 native modules load correctly, `dsh web` returns the full Web UI (HTTP 200), and headless
  calling `deepseek-flash` with a real key returns a result. The whole chain works end to end.
- **The second hardening round (2026-09-22) is complete**: fixed 10 fatal/severe bugs (no API Key entry
  point, no way into the log page, fake install success, install being interrupted as soon as the UI exits,
  deleting 300MB on the main thread always ANRs, orphan processes holding the port, no startup timeout,
  WebView spinning forever…), 6 performance problems (O(n²) logging, full redraws…), 11
  reliability/security problems (plaintext key written to disk, key in argv, token in logs…), and filled
  in the interaction layer (new instance settings page, log entry point, base guidance banner, install
  progress, failure exits, hot recovery).
- **Core architecture**: Android App (borrowing FCL's download/instance/foreground-service patterns)
  + a bundled proot rootfs (running Node+dsh) + WebView (loading dsh's own Web UI) + cloud DeepSeek API.
- **Key constraint**: dsh's native modules are published only for glibc/musl (no android/bionic), so
  **proot is mandatory** — Termux's bionic Node cannot be used, nor can in-process dlopen the way FCL does it.
- **Code progress**: `com.dsh.core` (17 files) + `com.dsh.ui` (7 files) = **24 Kotlin files ≈ 4310 lines**, plus
  **7 layout xml files**, Manifest registration, Chinese/English strings, the assets base directories, and 1 JVM unit-test file.
- **A full Android compile has passed** (including the second-round changes): `./gradlew :FCL:compileDebugKotlin` **BUILD SUCCESSFUL**,
  dsh files **0 error / 0 warning** (see §7.1).
- **Already packaged into an APK** (first-round output): `assembleFordebug` produced a signed APK; the
  second-round changes have been verified by a real compile, and repackaging needs one more run in an
  environment with network access and no time limit.
- The core logic has been **actually run**: the scripts ran for real in 4 scenarios (including 200/401 and
  signal forwarding), 30 assertions ran directly against the compiled output, and all 14 JVM unit tests passed.

---

## 1. Background and goals

### 1.1 The two projects
- **dsh (DeepSeek Harness)**: DeepSeek's open-source agent harness. A **Node.js/TypeScript** project (pnpm monorepo,
  Cordis-driven "everything is a plugin"). Running `npx @deepseek-ai/dsh web` starts the Web UI at `http://127.0.0.1:3080`.
  Models run in the cloud; there is no local LLM inference.
- **FCL (FoldCraftLauncher)**: a Minecraft: Java Edition launcher for Android. Kotlin/Java,
  applicationId=`com.tungsten.fcl`.

### 1.2 Target shape (decided)
- Option A: build an Android launcher for dsh; FCL is only a technical reference.
- Bundle a proot rootfs (closest to a finished product — the user installs the APK and it just works, without installing Termux first).
- Cloud DeepSeek API (the user has a key).
- Ordinary unrooted phone.
- Only the two capability classes "chat/coding agent" and "shell/file operations" — **not** computer-use/browser-use.

---

## 2. Key technical facts (repos pulled for real + PoC verification)

### 2.1 dsh
- Package name `@deepseek-ai/dsh`; 22 versions on npm, dist-tags: `latest/next=0.1.5-rc.2`, `alpha=0.1.6-alpha.2`
  (⚠️ **outdated**: a September 2026 snapshot; for the current version list, query `npm view` live).
- **engines requires Node `^22.19.0 || >=24.0.0`** (fairly new — the rootfs must ship a new enough Node).
- **Native modules are published only for `linux-x64` / `linux-arm64` (both glibc and musl) / `darwin`, with no android/bionic.**
  This is what settles it: you must proot a standard Linux (glibc Ubuntu or musl Alpine).
- After starting `dsh web`, stdout prints `http://127.0.0.1:<port>/?token=XXXX`.
- **Authentication is token→cookie**: the first visit with a token returns 302 and sets a cookie; after that the cookie is used.
  With cookie=200, without cookie=401. WebView stores cookies by default, so loading the token-bearing URL once gets you in.
- **Pitfall**: the default web profile's `dsh-hmr` plugin requires node with `--expose-internals`, and that flag
  **may not be put in `NODE_OPTIONS`**. So you cannot use the `.bin/dsh` symlink; you must run `node --expose-internals bin.js`
  directly (the scripts already handle this). Alternatively switch to the headless/sdk profile (HMR off by default).
- A single version's `node_modules` ≈ **303MB** (190 top-level packages).
- DeepSeek model ids: `deepseek-flash`, `deepseek-v4-pro`; API `https://api.deepseek.com/chat/completions`;
  env `DEEPSEEK_API_KEY`.

### 2.2 FCL (source read at main@f4f2624)
- **FCL does not use proot!** It unpacks a bundled JRE from assets into a private directory and runs the JVM
  **in the same process**, using the native `jre_launcher.c` + `VMLauncher.launchJVM` (dlopen `libjvm.so`).
- Conclusion: FCL's "runtime launch layer" **cannot be copied** for us (dsh is an external Node process and its
  native dependencies must match glibc/musl, so proot is required). But FCL's **higher-level management
  facilities** are very much worth copying, and we did copy them (see §4.1).

### 2.3 Landlock sandbox
- dsh uses Linux Landlock for isolation. In a proot environment on most Android kernels **no usable Landlock
  can be detected**, so isolation degrades.
- For personal use with approval dialogs this is acceptable; **do not run untrusted tasks with it** (dsh's own
  SAFETY statement says the same).

---

## 3. Overall architecture

```
┌────────────────────────────── Android APK ───────────────────────────────┐
│                                                                          │
│  Native launcher UI (borrows FCL: instances / download / logs / settings)│
│        │                                                                 │
│        ├── DshInstances (instance repository singleton, StateFlow)       │
│        ├── DshDownloadViewModel ── DshRegistry ──► npm registry          │
│        ├── DshInstaller ─┐                                               │
│        └── DshRuntime ───┤                                               │
│                          ▼                                               │
│                   ProotProcessExecutor (ProcessBuilder + proot)          │
│                          │                                               │
│  ┌──────────────────────────────▼────────────────────────────────┐       │
│  │ proot + rootfs (Ubuntu/Alpine arm64, app private dir, shared) │       │
│  │   Node 22/24 + each version of dsh (per-instance node_modules)│       │
│  │   `dsh web` → 127.0.0.1:<port>/?token=XXXX                    │       │
│  └──────────────────────────────┬────────────────────────────────┘       │
│                                          │ captures the token URL        │
│  DshRuntimeService (foreground keep-alive)   DshWebViewActivity (WebView)│
│                                          │                               │
└──────────────────────────────────────────┼───────────────────────────────┘
                                            ▼
                           DeepSeek cloud API (called by the llm plugin)
```

Three layers of responsibility:
1. **Runtime layer**: proot + rootfs running Node+dsh (Task 1).
2. **Management layer**: instances / versions / download / configuration (Task 2, copying FCL's patterns).
3. **Access layer**: process keep-alive + WebView showing the dsh UI + secure API key storage (Task 3).

---

## 4. Directory and code structure

### 4.1 Borrowed from FCL vs home-grown

| What FCL has | Our counterpart | Relationship |
|---|---|---|
| `FCLauncher.launch` (dlopen libjvm in-process) | `DshRuntime` (ProcessBuilder + proot external process) | **Not copied**, different model |
| `Profiles` (StateFlow repository singleton) | `DshInstances` | pattern copied |
| `Profile.kt` (Gson serialisation unit) | `DshInstance` | pattern copied |
| `FCLPath.java` (centralised paths) | `DshPaths` | pattern copied |
| `DownloadManager`+`DownloadService` (foreground keep-alive) | `DshInstaller`+`DshRuntimeService` | pattern copied |
| `ui/version` (List/Adapter/Item) | `DshVersionListItem`+`DshDownloadViewModel` | pattern copied |
| `HttpRequest` (HttpURLConnection+Gson+async retry) | `DshRegistry` **reuses it** directly | reused, no new dependency |
| `ShellUtil` (ProcessBuilder running sh) | `ProotProcessExecutor` | reference |
| `WebActivity` (WebView+WebViewClient) | `DshWebViewActivity` | reference |

### 4.2 App private directory layout (DshPaths)

```
<filesDir>/dsh/
  rootfs/                shared proot rootfs (Ubuntu/Alpine arm64), reused by every instance
  scripts/               proot scripts unpacked from assets (setup/start)
  instances/<id>/        independent per instance
    node_modules/        that instance's dsh and dependencies (about 300MB)
    package.json
    home/                DSH_HOME (sessions/config/profile)
    ~~credentials.env      plaintext runtime key (mode 600, wiped after use)~~
    ~~credentials.enc      long-term encrypted key (Keystore AES-GCM)~~
  instances.json         instance manifest (persisted by DshInstances)
<cacheDir>/dsh/tarballs/ temporary download area
```
> ⚠️ The two `credentials.*` lines above are **void** (since 0.1.5 the Key is dsh's business, see correction 3 at the top of this document).
> Also: the rootfs is actually Debian 12 (Ubuntu/Alpine was an early either-or choice, since settled).

### 4.3 Kotlin files already written (17 in `com.dsh.core` + 7 in `com.dsh.ui` ≈ 4310 lines)

> ⚠️ **Outdated (counts and list)**: this is a 2026-09-22 snapshot; `com/dsh/` has since grown to **43 files**
> (`core` 21 + `ui` 22, including the `shell/` shell and the `setup/` prerequisite page), and `DshCredentials.kt` has been
> deleted while `DshDownloadViewModel.kt` has moved into `core`. For the current list see "Current status" in `README.md`.

> This section has been updated for the **second hardening round** (2026-09-22).

**Data / management layer**
- `DshPaths.kt` (204) — path hub (rootfs/proot/scripts/npm-cache/logs/tmp/instances) + disk usage helpers
- `DshRegistry.kt` (181) — npm registry client + semantic version sorting + 10-minute cache
- `DshInstance.kt` (79) — instance data model (port=0 auto-allocation, lastError, model/profile constants)
- `DshInstances.kt` (277) — instance repository singleton (StateFlow + async atomic persistence + **state self-healing `repair()`** + unique ids + async delete)
- `DshInstaller.kt` (284) — version installer (npm inside proot) + single-flight/timeouts/stage progress/hard validation after install
- `DshVersionListItem.kt` (47) — download row model (tag priority)
- `DshDownloadViewModel.kt` (123) — download page data flow (cache/timeouts/offline fallback)
- `DshLogBus.kt` (172) — **new**: log bus (ring buffer, ~5Hz coalesced flush, write-to-disk, token redaction)
- `DshAppScope.kt` (22) — **new**: process-level coroutine scope (long tasks do not die with the UI)
- `DshServices.kt` (26) — **new**: process-level service locator (installer singleton)
- `DshBootstrap.kt` (266) — base first-run extraction (incremental/idempotent/atomic replace/rootfs promotion/disk check/**self-check probe**)

**Runtime / access layer**
- `ProotCommand.kt` (162) — **new**: the single place the proot command line is built + preflight + env var whitelist (keys never in argv)
- `ProotProcessExecutor.kt` (197) — real `ProotExecutor` implementation + `ProotRunner` (long-lived process handle / graceful termination)
- `DshRuntime.kt` (487) — long-lived `dsh web` process management: **state machine / startup watchdog / port read-back / claiming orphan processes**
- `DshRuntimeService.kt` (186) — foreground keep-alive service (specialUse, notification can stop it, automatic wrap-up when the process exits)
- `DshCredentials.kt` (153) — ⚠️ **deleted** (since 0.1.5 the Key is dsh's business, see correction 3 at the top of this document).
  Back then it was: secure API key storage (Keystore encryption + AAD + state distinction; **plaintext no longer written to disk**)
- `DeepSeekApi.kt` (80) — **new**: DeepSeek `/models` key validation (the "Test connection" in settings)

**UI layer (`com.dsh.ui`, 7 files)**
- `DshInstancesActivity.kt` (304) — instance list (complete entries + base banner + install progress + failure exits + claiming)
- `DshInstanceAdapter.kt` (187) — instance row (DiffUtil, progress, failure reason, overflow menu, size cache)
- `DshDownloadActivity.kt` (174) — download page (cache refresh, base preparation progress, double-tap guard)
- `DshVersionAdapter.kt` (92) — version row (DiffUtil, installing state)
- `DshLogsActivity.kt` (94) — log page (revision-driven redraw, auto-scroll toggle, copy, on-disk path)
- `DshSettingsActivity.kt` (241) — **new**: instance settings page (API Key / name / model / profile / port / self-check / reinstall / delete)
  > ⚠️ **No longer exists** (2026-10-07): turned into a **temporary page inside the instance page** (3 tabs in the page: Run / Logs / Config),
  > there is no longer a standalone Activity. The API Key row has also been removed from the detail page (the Key is now filled in on dsh's own page).
  > (Note the distinction from the "settings page Activity" of the same name in phase 2 — that one was the forerunner of the shell's Settings tab.)
- `DshWebViewActivity.kt` (272) — WebView (full state machine, 401 fallback, cookie persistence, tightened security)

### 4.4 Scripts

Early PoC script directory (at the time the same source as `FCL/src/main/assets/dsh/scripts/`, script version 2; the script version is now at 7):
- `setup-node-dsh.sh` — prepare Node inside the rootfs (validate ^22.19||>=24) + install the specified dsh version into the instance directory;
  prints `STAGE=...` for the UI to show progress; shared npm cache; hard validation after install
- `start-dsh.sh` — start `dsh web` inside the rootfs; **actively probes for readiness** and prints `READY url=/port=`,
  on failure prints `FAILED reason=...`; signal forwarding; supports `PORT=0`
- `probe.sh` — self-check probe (called by `DshBootstrap.verify`)
- `proot-run.sh` — device-side wrapper (runs the rootfs with proot), the on-device reference implementation

---

## 5. Key flows

### 5.1 Installing a dsh instance for the first time
1. The UI calls `DshDownloadViewModel.refresh()` → `DshRegistry.fetchVersions()` to pull the npm version list (abbreviated metadata).
2. The user picks a version → `installVersion()` → `DshInstances.create()` creates the instance directory + metadata.
3. `DshInstaller.install()` → `ProotProcessExecutor` runs `setup-node-dsh.sh` inside proot
   (`npm install @deepseek-ai/dsh@<version>`), progress reported back to the UI through StateFlow.
4. On success → the instance state is set to READY and the real version number is recorded.

### 5.2 Starting and using it

> ⚠️ **Outdated (the credential part)**: step 1's write of `credentials.env` and step 6's wipe are **void**
> (since 0.1.5 the Key is dsh's business, see correction 3 at the top of this document). The rest of the start/keep-alive/capture-URL flow is still valid.

1. ~~`DshCredentials.writeRuntimeCredential()` writes the plaintext key into `credentials.env` (mode 600).~~ (void)
2. `DshRuntime.start()` → runs `start-dsh.sh` inside proot to start `dsh web`.
3. `DshRuntimeService` (foreground service) is brought up to keep the process alive.
4. `DshRuntime` reads the output in the background and captures `http://127.0.0.1:<port>/?token=XXXX` with a regex.
5. `DshWebViewActivity` subscribes to the URL becoming ready → `loadUrl()` → the full dsh Web UI appears, ready for chat/coding.
6. Stop: `DshRuntime.stop()` kills the process + stops the foreground service. (The original "wipe the plaintext" step is void, see the note at the top of this section.)

---

## 6. Security design

> ⚠️ **Outdated (the API key bullet)**: the first bullet below, "Keystore-encrypted on disk + decrypted and written before start", is **void**
> (since 0.1.5 the Key is dsh's business, see correction 3 at the top of this document). The other bullets (loopback binding, command-injection protection, isolation disclosure, process decoupling) are still valid.

- ~~**API key**: long-term storage encrypted with Android Keystore AES-GCM (only the ciphertext `credentials.enc` is stored);
  decrypted and written into `credentials.env` (mode 600) only before start, wiped after stop. Plaintext only ever lands in a restricted file for the duration of a run.~~ (void)
- **Network binding**: `dsh web` binds only to `127.0.0.1` and is not exposed to the LAN.
- **Command-injection protection**: proot commands use array arguments (no string concatenation); injected environment variables go through a key whitelist,
  blocking dangerous variables such as `LD_PRELOAD`.
- **Isolation degradation disclosure**: Landlock is mostly unusable under proot/Android kernels; the UI should warn "do not run untrusted tasks",
  and dsh's approval dialogs are kept.
- **Process/page decoupling**: closing the WebView does not stop the process; stopping is an explicit action in the list. When the service is reclaimed by the system, the process is killed as a fallback to prevent zombies.

---

## 7. Verified vs unverified

### 7.1 Measured and passing
| Item | Method | Result |
|---|---|---|
| dsh arm64 native module loading | require in a proot+glibc environment | node-addon-system/koffi/node-pty/sharp all OK |
| `dsh web` starting the Web UI | real launch + curl | HTTP 200, a complete 27KB front end |
| token→cookie authentication | curl with/without cookie | with cookie=200, without cookie=401 |
| Cloud API end to end | headless + a real key | calling deepseek-flash returns a result |
| start-dsh.sh | real run reusing an instance | loads the key correctly, starts the service, prints the token URL |
| registry version fetch | real request | 22 versions, dist-tags correct |
| token URL capture regex | fed real dsh output | captured successfully |
| **Full Android compile** | **Android SDK35+NDK27+Gradle8.14.4, `./gradlew :FCL:compileDebugKotlin`** | **BUILD SUCCESSFUL** (17 dsh files + 6 layouts all pass type checking) |
| **APK packaging (native skipped)** | **`assembleFordebug` (externalNativeBuild temporarily commented out)** | **BUILD SUCCESSFUL**, produced a signed 362MB APK; all 18 dsh classes are in the dex, 5 components in the Manifest, 6 layouts in the resource table (verified with zipfile/aapt2) |
| **dsh assets included in the package** | verified the `mergeFordebugAssets` intermediates | `dsh/scripts/*.sh` + version all made it into merged assets; `com/dsh/*` made it into the dexBuilder output |
| First-run extraction orchestration | stub Context + stub RuntimeUtils running the real DshBootstrap | 12/12 passed (first-run extraction / idempotence / re-extraction on version upgrade / proot path fallback) |
| rootfs tar.xz symlinks | real tar+xz pack/unpack round trip | symlinks preserved correctly (critical for the rootfs) |
| Instance repository functional tests | stub Context running the real code | 31/31 passed (including persistence round trip, port allocation, selection correction) |
| registry parsing/sorting | real data driving real Kotlin | 13/13 assertions passed |
| XML/resource static consistency | script cross-check | 9 XML files well-formed; R.string/R.id/binding attributes/class names/drawables all match |

**Verification after the second hardening round (2026-09-22)**

| Item | Method | Result |
|---|---|---|
| Full real compile (including all second-round changes) | `./gradlew :FCL:compileDebugKotlin -Darch=arm64` | **BUILD SUCCESSFUL**, dsh files 0 error / 0 warning |
| Real defects caught by the compiler | same as above | 4 fixed: `*.sh` inside KDoc triggered a **nested comment** that broke the whole file's syntax; `Process.pid()` **does not exist** in the Android runtime; `emptyMap()` type inference; `URL as HttpURLConnection` **casts that always crash** |
| Rewritten `start-dsh.sh` real run (fixed port) | real dsh package, device-equivalent environment | `READY url=...` after 15s; with the token **200**, without the cookie **401**; passthrough logs visible while running; exit code 0 after TERM and the port is released |
| Rewritten `start-dsh.sh` real run (`PORT=0`) | same as above | port allocated automatically (e.g. 40233), read back from the URL successfully |
| Failure signal | instance directory has no dsh package | immediately `FAILED reason=package-missing` (exit code 1), no longer waiting uselessly until timeout |
| Self-check probe | `probe.sh` | `dsh-probe-ok` (also prints the real kernel/arch/node version) |
| Pure logic real run | Java directly against the **compiled output**, 30 assertions (semver / registry parsing and sorting / tag / URL&token parsing / log redaction) | **30 / 30 passed** |
| JVM unit tests | new `FCL/src/test/java/com/dsh/DshCoreLogicTest.kt` (kotlinc compile + reflective execution of 14 cases) | **14 / 14 passed** |
| Real dsh behaviour probes | token→cookie, cookie **still valid across a process restart**, `--port 0`, `dsh web --help` | all confirmed, and the WebView and script readiness strategy was decided from this |

> **The key pitfall of running the Android SDK on arm64 (solved; kept for on-device/CI reproduction)**:
> 1. FCL needs `ndk;27.0.12077973` (it has native CMake builds); AGP's automatic download once fetched a corrupt zip,
>    so it was installed manually with `sdkmanager "ndk;27.0.12077973"`.
> 2. That SDK's `aapt2` exists only as an x86_64 build (Google does not publish a linux-arm64 aapt2). Running it directly on arm64 reports
>    `AAPT2 Daemon startup failed`. Solution: install `qemu-x86_64-static` + x86_64 runtime libraries (`libc6:amd64` etc.,
>    the apt sources need to be split by architecture), then replace the `aapt2` inside AGP's cached jar with a "qemu forwarding wrapper"
>    (keep the real binary under another name, change `aapt2` to `exec qemu-x86_64-static <real binary> "$@"`), clear the already-extracted
>    transforms cache, and rebuild.

### 7.2 Unverified / not done

> ⚠️ **Most items in this section are out of date** (written 2026-09-22): a complete APK (with native + new assets) has been produced
> for every version since 0.1.1; the three proot pieces are in `jniLibs/`, the rootfs is in `assets/dsh/rootfs/`, and both are in place;
> on-device end to end was lit up at 0.1.6.
> This is preserved verbatim purely as a historical record; for current status see `ROADMAP.md` and `TASKS.md`.
- **Producing a complete APK including the new assets again**: the new code such as `DshBootstrap` already compiles,
  and the assets are confirmed to be in the mergeAssets/dex intermediates; but due to sandbox limits (every command return SIGTERMs
  gradle inside proot, and repackaging's dexBuilder takes longer than a single timeout),
  another final APK file could not be produced. The existing APK is the previous round's
  output. Running `assembleDebug` in an x86_64 or unlimited-time environment produces a complete package with native + the new assets in one go.
- **Native CMake build**: FCL's 46 C/C++ sources (the JRE launcher/GL etc. that run Minecraft, **unrelated to dsh**)
  need the NDK toolchain (clang/cmake/ninja are all x86_64), which in an arm64 sandbox needs qemu throughout, so it was not run. The APK
  packaging above was obtained by **temporarily commenting out externalNativeBuild** (now restored).
- **proot binary and rootfs.tar.xz**: platform-specific large files, not committed to the repository. The packager must fill them in per
  `PACKAGING.md` (proot is best shipped via jniLibs; the rootfs should bundle Node).
- **Executing proot on a real device**: the sandbox itself runs inside proot and cannot nest proot, so the proot execution in
  `ProotProcessExecutor`/`DshRuntime` is an on-device interface contract (the rootfs's internal flow has been verified in an equivalent environment).
- **Runtime behaviour**: the WebView actually loading, the foreground service keeping alive~~, Keystore encryption/decryption~~ all need a real device.
  (After the Key became dsh's business, the "encryption/decryption" item no longer exists.)

---

## 8. Implementation roadmap (suggested order)

1. ~~**Local compile verification**~~ ✅ Done: `./gradlew :FCL:compileDebugKotlin` BUILD SUCCESSFUL
   (in an arm64 environment, handle the x86_64/qemu NDK and aapt2 issues per the §7.1 note).
2. ~~**Complete APK packaging**~~ ✅ Verified (the dsh part): `assembleFordebug` produced a signed APK, 18 dsh classes in the dex,
   components/layouts all packaged. Native CMake was skipped for now because the arm64 toolchain needs qemu (unrelated to dsh, see §7.2);
   running `assembleDebug` on a real device/x86_64 environment produces a complete package with native code.
3. ~~**assets and first-run extraction**~~ ✅ Code complete and verified: `DshBootstrap` first-run extraction (incremental/idempotent/symlinks)
   compiles + 12/12 functional tests; the assets base directories are created (scripts are in; the proot binary and rootfs.tar.xz
   are for the packager to fill in per `PACKAGING.md`).
4. **On-device integration**: fill in the proot binary (jniLibs) + rootfs.tar.xz → first-run extraction → install dsh from the download page → start → WebView shows the UI.
5. **Polish**
   - ~~Settings page: API key entry UI (wired to `DshCredentials`), model selection, port~~ ⚠️ **the key part is void**
     (since 0.1.5 the Key is dsh's business, see correction 3 at the top of this document; the page itself has been merged into the instance detail temporary page)
     > ⚠️ Since 2026-10-07 that page has been merged into the **instance detail temporary page**; the API Key row has been removed (left to dsh's own page).
   - ~~Instance rename, size cleanup entry, error messages~~ ✅ Done (second round; rename/size are in the settings page, error messages land inline + in dialogs)
   - Still not done: download resume, Landlock weak-isolation warning, instance config export/import.
   - ~~If a formal entry point is wanted: add a dedicated button/menu item on the main screen (currently a hidden entry via long-pressing "Settings").~~
     ⚠️ **Void**: after the M0 shell rework, launching goes straight to the dsh main screen and there is no hidden entry any more (see `design/app-shell.md`).
   - **The second round's complete changes**: 10 fatal/severe bugs, 6 performance, 11 reliability/security,
     interaction-layer completion, script signal protocol rewrite (all as recorded at the time)

---

## 8.5 On-device integration steps (execute in order on a real device)

> ⚠️ **This section was written before the shell rework and parts of it are out of date**: the runtime base did **not** switch to `:proot-engine`,
> and is still the **home-grown proot layer + pre-packaged rootfs** described in this document (already implemented).
> **Step 1, "fill in the two classes of large platform files", is no longer necessary** — the three proot pieces are in `jniLibs/`, the rootfs is in
> `assets/dsh/rootfs/`, and both are already in place (see `ROOTFS.md`).
> What to verify on a real device and how is governed by **T6 in `TASKS.md`** and M1 in `ROADMAP.md`; the remaining steps in this section are still usable for reference.

Prerequisites: an arm64 Android device (unrooted is fine), and a development environment that can compile FCL (an x86_64 machine is easiest,
or this sandbox's arm64+qemu arrangement, see the §7.1 note).

### Step 1: Fill in the two classes of large platform files (**no longer necessary, both are in place**)
1. **proot binary (the jniLibs route is recommended)**: name the arm64 proot and proot loader
   `libproot.so` and `libproot_loader.so`, and put them in `FCL/src/main/jniLibs/arm64-v8a/`.
   The system unpacks them into `nativeLibraryDir` at install time with the execute bit already set, and `DshPaths.resolveProotBin/Loader`
   will prefer them. (Source: the proot packages from proot-distro / Termux, or cross-compile your own.)
2. **rootfs.tar.xz**: prepare an arm64 Ubuntu (glibc, recommended) or Alpine (musl) rootfs,
   **with Node 22/24 preinstalled before packaging** (to save the first network round trip), pack it as `.tar.xz` and put it at
   `FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz`. Delete `PLACEHOLDER.txt` in the same directory.
3. If you switch to the assets route for proot instead, put the .so files in `assets/dsh/proot/` and delete the placeholder files (see `PACKAGING.md`).

### Step 2: Produce a complete APK
```
./gradlew :FCL:assembleDebug          # or assembleFordebug (uses the in-repo debug key, password FCL-Debug)
```
This step builds the native CMake normally (46 C/C++ files, used to run MC, unrelated to dsh but needed for FCL packaging).
An arm64 build machine needs to handle the x86_64→qemu problem for aapt2/cmake/ninja per §7.1; an x86_64 machine has no such problem.

### Step 3: Install and reach the entry point
1. `adb install -r <apk>` to install onto the real device.
2. ~~Enter the dsh launcher: **long-press "Settings"** on the left of the main screen (hidden entry) → opens `DshInstancesActivity`.~~
   ⚠️ **Void**: after M0 the app goes straight into the dsh shell on launch (three pages: Instances / Versions / Settings), with no hidden entry.

### Step 4: First-run extraction + install an instance
1. Tap "Download" → enter `DshDownloadActivity`; the first time this triggers `DshBootstrap` to unpack proot/rootfs/scripts
   into the private directory (with a progress indicator).
2. Once the version list has loaded (from the npm registry), pick a version → triggers `DshInstaller` to run
   `setup-node-dsh.sh` inside proot and install dsh. Watch the live log in `DshLogsActivity`.
3. When the install finishes, the instance state becomes READY.

### Step 5: Configure the key and start
1. ~~Use `DshCredentials.storeEncrypted()` to store the DeepSeek API key (the settings page UI is still to be added, see §8 step 5;
   for debugging you can call it once in code or temporarily write the instance's `credentials.env`).~~
   ⚠️ **Void**: since 0.1.5 the Key is entered in **dsh UI → Settings → Models**, and the launcher no longer manages it (see correction 3 at the top of this document).
2. Tap "Start" in the instance list → `DshRuntime` starts `dsh web`, the foreground service keeps it alive → the token URL is captured →
   `DshWebViewActivity` loads it, the full dsh Web UI appears, ready for chat/coding.

### Integration troubleshooting checklist (common failure points for each link)
- **proot won't start**: check that `libproot.so` has the execute bit, that the `PROOT_LOADER`/`PROOT_TMP_DIR` environment variables are correct,
  and that `/bin/sh` exists inside the rootfs.
- **Node version mismatch**: `node -v` inside the rootfs must be `^22.19||>=24` (`setup-node-dsh.sh` validates this).
- **dsh web won't start**: check that you are using `node --expose-internals bin.js` (it cannot go into NODE_OPTIONS); look at `DshLogsActivity`.
- **WebView 401**: token→cookie did not take effect; check that the full URL with `?token=` was loaded and that the WebView allows cookies.
- **Chat fails but the UI is fine**: most likely the key is missing or it's a quota problem; the Key is entered in **dsh UI → Settings → Models**
  (the launcher no longer injects it since 0.1.5, and the old `credentials.env` check is void).
- **Killed in the background**: check that the `DshRuntimeService` foreground notification is persistent and that notification permission has been granted.

---



| Risk | Description | Mitigation |
|---|---|---|
| Large size | Single-instance node_modules ≈303MB + rootfs + Node | Share the rootfs; provide instance cleanup; warn the user |
| Killed in the background | Android killing background apps interrupts dsh web | Foreground service + persistent notification + WAKE_LOCK |
| Weak isolation | Landlock is mostly unusable under proot/Android | Keep the approval dialogs; warn against running untrusted tasks |
| Node version | Requires ^22.19\|\|>=24 | Ship a new enough Node in the rootfs; the setup script validates it |
| dsh iterates fast | Developer preview, breaks compatibility | Multi-version management; lock the verified versions |
| Native module compatibility | Alpine (musl) occasionally has compatibility problems | Use Ubuntu (glibc) by default, measured |

---

## 10. Appendix: deliverables index

> ⚠️ **This section was written early and the counts and file names are already out of date** (code files grew from 24 to 40+,
> and the layouts/scripts have been added to and removed from as well). **Do not reconcile against the numbers here** — for the current file list and structure see
> the "Current status" and "Where the code lives" sections of [`README-EN.md`](README-EN.md).
>
> This section is kept only to show **what was originally intended to be delivered** (what belongs to docs, what to code, what to scripts and fixtures);
> that classification itself is still valid.

**Docs (`docs/`)**

- `README.md` — documentation overview and navigation (including current status)
- `PLAN.md` — this document, the complete implementation plan (master plan)
- `ROADMAP.md` — milestone plan
- `PACKAGING.md` — packaging notes: preparing and placing the proot binary + rootfs.tar.xz
- `ROOTFS.md` — rebuilding and slimming the rootfs
- `ENVIRONMENT.md` — development environment (JDK / SDK / NDK)
- `design/` — design documents (proot launch chain, multi-version, UI assembly, W^X bypass…)

**Code (`FCL/`)**

- `FCL/src/main/java/com/dsh/core/` — paths / instances / install / runtime / credentials / logs / command building
- `FCL/src/main/java/com/dsh/ui/` — instance list / download / logs / settings / WebView + Adapters
- `FCL/src/main/res/layout/*dsh*.xml` — dsh page layouts
- `FCL/src/main/assets/dsh/` — the runtime base unpacked on first launch (`scripts/` + `rootfs/`)
- `FCL/src/test/java/com/dsh/` — JVM unit tests

**Scripts and fixtures**

- `FCL/src/main/assets/dsh/scripts/` — the POSIX sh shipped in the package (`setup-node-dsh.sh` / `start-dsh.sh` / `probe.sh`)

---

## 11. Terminology quick reference (for readers unfamiliar with Android/Linux)

| Term | One-line explanation |
|---|---|
| **proot** | A tool that "pretends to chroot" without root, letting an ordinary Android phone run a whole Linux userland. FCL uses it to run Java, we use it to run Node. |
| **rootfs** | The files of a whole Linux system (/usr, /lib, /bin…), packed into an archive, tens to hundreds of MB. |
| **glibc / musl / bionic** | Three C standard libraries. Ubuntu uses glibc, Alpine uses musl, Android ships bionic. dsh's native modules are published only for glibc/musl, so you can't use Android's bionic directly. |
| **Native module (.node)** | An extension in Node compiled with C/C++ (e.g. node-pty, koffi). It is a binary for a specific CPU+libc and does not work across libc — this is exactly the root cause of needing proot. |
| **profile (dsh)** | dsh's run mode: `web` (with Web UI) / `headless` (one-shot tasks) / `sdk` etc. |
| **DSH_HOME** | The directory where dsh stores sessions, configuration and profiles. We give each instance its own to achieve isolation. |
| **Foreground service** | An Android background service with a persistent notification that is hard for the system to kill. Used to keep the long-running `dsh web` alive. |
| **token→cookie authentication** | The dsh Web UI's login method: the first visit with a token plants a cookie, after which the cookie is used. WebView stores cookies by default, so loading the token-bearing URL once gets you in. |

---

## 12. Document notes

- **Version**: v1.0 (consolidating Tasks 1/2/3)
- **How to read it**: for the conclusions quickly, see §0 TL;DR; to implement it, follow the §8 roadmap; for feasibility, see §7 verified;
  those helping with the Android code should focus on §3 architecture, §4 code structure and §5 key flows.
- **Important reminder**: none of the DeepSeek API keys used for testing in this document were written into any file or code; if you provided a key in the conversation,
  please rotate/delete it in the DeepSeek console. ~~In production the key only goes through `DshCredentials` (Keystore encryption); do not hard-code it.~~
  ⚠️ **Void**: since 0.1.5 the Key is managed by dsh itself; the launcher neither stores nor injects it (see correction 3 at the top of this document).
- **Current boundary**: the logic layer and the runtime chain have been verified with real data / real dsh; ⚠️ **this item is out of date** —
  on-device end to end was lit up at 0.1.6 (see M1 in `ROADMAP.md` and T6 in `TASKS.md`).
