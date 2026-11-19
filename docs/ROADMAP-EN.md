[简体中文](ROADMAP.md) | English

# dsh Android Launcher — Future Roadmap

> Consolidates the to-dos, on-device integration work and polish items scattered across the round reports and
> `PLAN.md §8` into a single update route **organised by milestone**. Each milestone gives: the goal, what to do,
> the completion criteria (DoD), and dependencies and risks.
>
> How to read it: **from top to bottom is the suggested order of work**. M1 is the prerequisite for everything
> that follows (without a working real device, everything is on paper).
>
> **Revision record (2026-10-30)**: the project has reached **fix22** (`dshVersionCode=128`, unit tests 1060/1060,
> script self-check 33/33); before this, the document had been stuck at the fix1 state. This pass only
> **annotates and corrects**: achieved milestones are ticked with the date noted, outdated "plans" are rewritten to
> match the code and what was actually delivered, and **historical milestones and original acceptance steps are kept
> in full** (delete them and you can no longer see why the order was chosen).

---

## Where we are now (as of 2026-11-19: **code fix26 / code 132**; **public releases still stop at fix22**)

> ⚠️ **These two numbers differ — do not conflate them**:
> - **The code** has reached **fix26** (`dshVersionCode=132`, unit tests 1060/1060, script self-check 33/33);
> - **Public releases** stop at **`v0.1.6-SNAPSHOT-fix22`** (code 128) — fix23–fix26 have **not been
>   published to the Releases page** and have no tags. They currently exist only on this machine and in
>   the `output/` directory.
>
> So: **if you want a publicly downloadable build, install fix22**; fix23–26 must wait for a release.

**The shell rework (M0) is fully complete** (phases 0~4, including the de-Material wrap-up, see `design/app-shell.md`):
the app goes straight into the dsh shell on launch, the main menu has three switchable pages (Instances / Versions / Settings), and there is no MC runtime gate.

**The runtime base is also in place (at the code level, 2026-10-03, phases A~E-1)**: the home-grown proot layer + pre-packaged rootfs
(Debian 12 + Node 22 + preinstalled dsh) is wired into the APK, the three jniLibs (`libproot.so` / `libproot-loader.so` /
`libbusybox.so`) are in place, and the `PTY` bridge (`ptyjni`) is compiled in; builds since 0.1.1 are **full packages including the complete rootfs**.
⚠️ The rootfs has not been rebuilt since 2026-10-06 (what `docs/ROOTFS.md` labels `…-dsh0.1.6-alpha.2-slim1`),
while upstream `latest` was measured on 2026-10-30 to have moved to **0.2.1-rc.5** (`alpha=0.2.2-alpha.2`) — see BL-191.

> **⚠️ Route correction (2026-10-06)**: `design/proot-engine-integration.md` once planned to **integrate oonid/pr's
> `:proot-engine` to replace the home-grown proot layer**, but it was **ultimately not adopted** — the project continued with the home-grown proot layer.
> This file previously described M1 in several places on the basis of "going with proot-engine", which did not match the code
> (`settings.gradle.kts` includes only `:FCL` and `:ZipFileSystem`; there is no `ProotLauncher`/`ProotHost`, but rather
> `ProotCommand` + `ProotProcessExecutor`); that has been corrected in this pass. That design document is kept as a **decision record**
> (its W^X / PROOT_LOADER reasoning is still valid). **The conclusion — keep targetSdk 34 and bypass W^X with PROOT_LOADER — is unchanged.**

**M1 has been achieved (0.1.6, 2026-10-11)**: on-device end to end worked for the first time (install → start → stop → restart;
dsh opened successfully on the real device). Since then fix2~fix22, **21 fix builds in total**, have been produced (driven by two kinds of sources:
on-device feedback + the adversarial reviews R25~R29); M1's "install → start → stop → restart" chain has stayed usable ever since,
and the "first light" conclusion has been confirmed by later iterations. The original T6 verification checklist in `TASKS.md` is kept as a historical record.

**Currently advancing M2 / M3 / M5 in parallel**: M2's 5 code-hardening items landed long ago (item 4, keep-alive, was always an on-device verification item),
but **not one of the 4 on-device DoD boxes has been ticked** ("20 consecutive install/delete/start/stop cycles" has never been run);
M3 has only two half-done items ("read-only size display + retry on first run"), and the "formal entry point" was solved by M0 in passing;
M5's "distributable signed APK" was achieved by fix6 (T-D1 brought forward). For per-version evidence see `RELEASES.md`.

---

## Milestone overview

| Milestone | One-line goal | Nature | Prerequisite |
|---|---|---|---|
| **M0 Shell rework** | The app goes straight into the dsh UI on launch, no longer forced to install the MC runtime | Should do, **done** (phases 0~4) | none |
| **M1 First light on a real device** | Get end to end working once on one real device | Must do, **achieved (2026-10-11, 0.1.6)** | base code in place, real device working |
| **M2 Stability** | Install/start/stop repeatedly on a real device without crashing, hanging or crosstalk | Must do, **code hardening essentially complete (around 2026-10-06), on-device criteria not met** | M1 |
| **M3 Experience polish** | From "usable" to "comfortable" | Should do, **partial** (read-only size display / retry on first run done) | M2 |
| **M4 Testability and engineering** | Defects get caught by automation instead of manual review | Should do, **partial** (1060 unit tests + contract tests; service layer and CI still missing) | parallel to M2/M3 |
| **M5 Distribution and ecosystem** | Ship it to other people; keep up with dsh upstream | Could do, **partial** (signed APK + fixN channel in use; rootfs distribution/compatibility matrix not done) | M3 |
| **M6 Capability expansion** | More device form factors, more profiles/plugins | Long term, **not started** | M5 |

> M0 and M1 are the "shell" and the "kernel": they **can proceed in parallel**. With M0 done, the app correctly enters the dsh UI (a
> base that isn't ready does not block the way); with M1 done, "install dsh → start → WebView" inside the UI actually works.

---

## M0 · Shell rework (make the GUI usable first) ⚡

**Goal**: turn the app shell from "an MC launcher" into "a dsh launcher" — launching goes straight into the dsh UI,
and you are no longer forced to download the ~1GB MC runtime (JRE/LWJGL/Caciocavallo/JNA) first.

**Why it comes first**: it **does not depend on M1's runtime base** (proot / rootfs), so it can be done immediately.
Once done, the app is at least "a dsh launcher" rather than "an MC launcher + dsh hidden behind a long press".

**Design details**: `design/app-shell.md` (including the phased plan for phases 0~4 and the risk list).

**What to do (summary)**
- **Phase 0**: `SplashActivity` skips the 8 MC runtime gates and goes straight into the dsh UI (smallest change, validates the direction first) ✅
- **Phases 1~3**: build the new dsh shell (left slide-out menu + ViewPager2 content area + right panel + dynamic island title) ✅
  (at the time it was five pages = Instances / Manage (placeholder) / Download / Logs / Settings; **converged to three pages on 2026-10-07** =
  Instances / Versions / Settings)
- **Phase 4 (optional)**: trim unreachable MC code and resources to cut size ✅

**Completion criteria (DoD)** — **all achieved (2026-10-02, seventh round; the menu structure converged to three pages on 2026-10-07)**
- [x] Launching goes straight into the dsh shell, **without** the "install or update runtime" page and the MC EULA appearing
- [x] Instances / Versions / Settings are switchable; menu highlight and dynamic island title stay in sync
      (before 2026-10-07 it was five pages = Instances/Manage/Download/Logs/Settings; "Manage" was an empty placeholder and "Logs" has been demoted to a settings sub-page)
- [x] **No MC runtime needs to be installed at all** to reach and use the dsh UI
- [x] MC singletons are not triggered (`ConfigHolder.init()` / `RendererManager.init()`)
- [x] **The 8 dsh layouts contain 0 Material widgets** (§2.5 wrap-up)

**Dependencies / risks**: see `design/app-shell.md` §6 (the key points being "don't trigger MC singletons" and "the lifecycle differences between an Activity and FCLCommonUI").

---

## M1 · First light on a real device 🎯 — **achieved (0.1.6, 2026-10-11)**

**Goal**: on one unrooted arm64 real device, walk through once, completely:
"runtime base ready → install dsh from the download page → start from the list → WebView shows the dsh UI and can chat".

> **Achievement record**: 0.1.6 worked for the first time on a real device (install → start → stop → restart no longer reports the port as in use;
> dsh opened successfully on the real device). The **9 fixes reserved for fix1** at the time were long since absorbed by later on-device feedback —
> the project is now at **fix22**, and M1's "install → start → stop → restart" chain has been usable ever since,
> which shows the "first light" conclusion holds. Steps 1~7 below are the acceptance steps from that time, **kept as a historical record**;
> for the current state see "Where we are now" at the top of this document.

### What to do (in order, historical record)

1. **Install the current verification build onto the real device** — the one with the `-fixN` suffix (at the time `0.1.6-SNAPSHOT-fix1`,
   **the verification build is now `0.1.6-SNAPSHOT-fix22`**); the package is on GitHub Releases:
   <https://github.com/sqkl520/dsh-fcl-android-launcher/releases>
   (the same version without the suffix is the control baseline from before the fix — do not install that one)
2. **First launch**: check the time and success rate of the base extracting the rootfs (tar.xz 142MB after slimming, about 862MB once extracted)
   (the first install needs about 1.4GB free);
   the post-extraction self-check (`probe.sh`) must print `dsh-probe-ok`, otherwise record the failure reason first.
3. **Download page**: install a dsh version → the instance state reaches `READY`.
   Focus on **whether the DNS fix really takes effect** (can `npm install` install 0.2.x) — historically this always failed with `EAI_AGAIN`.
4. **Start**: capture `[start-dsh] READY url=http://127.0.0.1:<port>/?token=...`,
   and `DshWebViewActivity` renders the dsh Web UI.
5. **Chat**: have one successful conversation in the WebView with a real DeepSeek key.
6. **Wrap-up**: after stopping, the process ends, the foreground notification disappears, plaintext credentials are wiped; behaviour when the screen is off / after switching to the background and back.
7. Write the results (success/failure and why) back into this document and T6 in `TASKS.md`.

**Where to look when it goes wrong**: check "Settings → Runtime self-check" in the app and the log page first;
grab the `com.dsh` lines with `adb logcat`.

### Completion criteria (DoD) — **all achieved (2026-10-11, 0.1.6; repeatedly confirmed by the fix2~fix22 on-device iterations)**

- [x] proot can enter the rootfs and run `node -v` (version acceptable), and `probe.sh` prints `dsh-probe-ok`
- [x] The download page can install at least one dsh version, and the instance state reaches `READY`
- [x] After starting, a `?token=` URL is captured and `DshWebViewActivity` renders the dsh Web UI
- [x] dsh opens successfully on the real device (0.1.6; chatting in the WebView depends on the device's kernel version — on very old devices use an external browser)
- [x] Stopping cleans up properly (process ends, foreground notification disappears; 0.1.6 verified "stop → start again does not report the port as in use")

### Fallback route (only to be considered if on-device verification fails) — not enabled, kept as a contingency plan

**If proot will not start on the real device (any of W^X / seccomp / spawn failing)**, try in order:
1. Add `PROOT_NO_SECCOMP=1` (a fallback for seccomp compatibility on some kernels, see M2)
2. Option A from `design/wx-exec-proot-loader.md` §6: **lower `targetSdk` to 28** —
   this is the last resort; the cost is having to go through Play's listing restrictions again, so don't do it unless necessary
3. **Integrating oonid/pr's `:proot-engine`**, recorded in `design/proot-engine-integration.md`, is still an alternative route
   (that document is a decision record; its W^X / PROOT_LOADER reasoning is valid; actually enabling it would require the phased work in its §11)

### Dependencies / risks

- Compatibility between the proot binary and the device kernel's seccomp → M2's `PROOT_NO_SECCOMP` fallback
  (> ⚠️ **Undetermined**: `design/wx-exec-proot-loader.md` §3 cites oonid/pr's static analysis, which holds that proot's
  > built-in seccomp filter is dead code and that the variable "does nothing even if set", while also claiming this overturns the premise of "automatic retry" —
  > yet M2 item 1 (code logic implemented in the fifth round) still uses it as the retry fallback for seccomp/ptrace incompatibility.
  > Whether "the retry really does rescue it" on a real device has not been confirmed (see M2); in both cases the on-device result is authoritative, and until then do not treat either as settled.)
- ~~`Process.pid()` may not be obtainable on ART~~ → M2 item 2 confirmed it is **definitely not obtainable** and it has been fixed (a `/proc` fallback)
- rootfs size (tar.xz 142MB after slimming / about 862MB extracted, see `ROOTFS.md` §9) stacked on top of the APK — mind the first-install experience → T7's wrap-up can make it downloadable

---
## M2 · Stability

**Goal**: once M1 works, make the core chain **stable under repeated operation** on a real device — no crashes, no hangs, no crosstalk between instances.
This milestone digests the reliability items from the earlier round reports that were "identified but need on-device confirmation / are still not done".

> **Current state (2026-10-30)**: items 1/2/3/5/6 have **all landed on the code side**; item 4 (vendor ROM keep-alive)
> is inherently an **on-device verification item** (the code can only do "declarations in place + recoverable when killed"); but **not one of the 4 DoD boxes has been ticked**
> — it is not that the code was not written, but that criteria like "20 consecutive install/delete/start/stop", "network loss/screen rotation", "two instances installing
> concurrently", "30 minutes locked and in the background" are **things that must be exercised repeatedly on a real device** and have never been systematically executed. The stability changes in fix2~fix22
> (such as fix11's seccomp retry actually forking, fix12's unified "is it installed" criterion, fix19's ghost-instance protection)
> are all **single-point fixes made after a problem appeared on a real device**, which is not the same as actively running 20 stress rounds.

**What to do**
1. ✅ **`PROOT_NO_SECCOMP` automatic fallback** (implemented in the fifth round): when it exits before becoming ready and seccomp/ptrace incompatibility is suspected
   (exit code >128, or the log contains seccomp/ptrace/bad system call etc.), automatically retry once with that variable (once per instance per start).
   On a real device, "the retry really does rescue it" still needs confirmation.
2. ✅ **`Process.pid()` fallback** (implemented 2026-10-06): when reflection fails, fall back to parsing
   `/proc/self/task/*/children` (diffing against "the child process snapshot taken before startup").
   ★ While checking `api-versions.xml` it turned out this fallback is **not optional but mandatory**: the Android runtime's
   `java.lang.Process` **has no `pid()` method at all** (that is a Java 9+ API; the fact that it compiles against `android.jar`
   is only a compile-time stub), so on a real device the reflection path never succeeds even once. The boxing bug in the reflective read was fixed at the same time.
   (The fifth round already added a cmdline identity check to `kill()` to avoid killing the wrong process after pid reuse.)
3. ✅ **`DshInstaller` errors dispatched by instanceId** (implemented in the fifth round): the failure summary / npm error tail are now kept per instance,
   eliminating error crosstalk when two instances install concurrently. **The download page's progress-marker crosstalk (R5-17) was also fixed in fix13**:
   `reconcileInstalling(prev, event)` only removes **the version that failed/was cancelled this time**, no longer clearing the whole set
   (`DshDownloadViewModel.kt`, covered by unit tests).
4. **On-device keep-alive verification of the foreground service**: verify `DshRuntimeService`'s survival and post-kill recovery
   on mainstream vendor ROMs (Xiaomi/Huawei/OPPO&vivo/Samsung etc.); the `specialUse` declaration is in place but the actual policy varies by vendor.
5. ✅ **Claimed processes dying silently** (implemented in the fifth round): after a claimed orphan process dies, a 10s liveness patrol (dual-condition) takes over,
   so it no longer stays at Running forever. The remaining exceptional paths (switching to the background mid-install / network loss / disk full etc.) still need on-device reproduction.
6. ✅ **Process left over after stopping** (fixed 2026-10-06): on a real device "stop → the next start reports `EADDRINUSE`" did occur.
   The root cause was that stopping killed the base process, while dsh itself is its child, and **the signal sent to the base was simply ignored by it**
   (in practice it always ended with a force kill, and a force kill does not have time to clean up children). Now three things are done together: the script writes out dsh's real pid
   so the host can terminate it precisely, a signal the base actually responds to is used instead, and if the port is still occupied the system-assigned port is used instead.
   On a real device, "the retry really does rescue it" still needs confirmation.

**Completion criteria**
- [ ] 20+ consecutive install/delete/start/stop cycles with no crash, no hang, no leftover state
- [ ] Exceptional paths such as network loss / switching to the background / screen rotation all recover gracefully
- [ ] Two instances installing concurrently do not interfere with each other's progress
- [ ] The dsh process is still alive after 30 minutes with the screen locked and the app in the background (at least on the test device)

> ⚠️ **Undetermined (contradicts `design/wx-exec-proot-loader.md` §3)**: item 1 above,
> the `PROOT_NO_SECCOMP` automatic retry, is implemented, but the wx-exec document cites oonid/pr's static analysis holding that
> proot's built-in seccomp filter is dead code and that the variable "does nothing even if set".
> The two conclusions conflict, and "the retry really does rescue it" has not been confirmed on a real device either — the on-device result is authoritative, and until then do not treat either as settled.

---

## M3 · Experience polish

**Goal**: from "usable" to "comfortable". These are all polish items and do not block usability.

> **Current state (2026-10-30)**: of the six items, **1 is done (the formal entry point, superseded by M0), 2 are half done (read-only size display,
> retry on first run but no time estimate), and 3 are not done (resume / Landlock warning / config export-import)**.
> Neither the done nor the half-done ones were done on this list's schedule: the formal entry point came with M0, and the size row / retry on the preparation page are UI that already existed in 0.1.x.

**What to do**
- **Download resume** ❌ Not done: if the network drops halfway through installing dsh's dependencies you still have to start over (`DshInstaller` has no Range/resume logic).
- **Landlock weak-isolation warning** ❌ Not done: the UI has no "the environment isolation is weak, do not run untrusted tasks" warning anywhere.
- **Instance config export/import** ❌ Not done: instance metadata (version/profile/port/model, **not including the plaintext key**) cannot currently be exported;
  the "Export" in settings exports only **logs** (`DshLauncherSettingsPage.exportLogs()`), not configuration.
- **Formal entry point** ✅ Superseded by the **M0 shell rework** (launching goes straight into the dsh main screen). See `design/app-shell.md`.
- **Size visualisation and cleanup** ◐ Half done: the settings page already has a **read-only** size display (the "Disk usage" row in instance details),
  and there is a space pre-check before installing the rootfs; but "one-tap cleanup of unused instances / reset of the shared rootfs" **does not exist**.
- **First-run experience** ◐ Half done: the preparation page already has a staged progress bar + percentage + detail row (`DshBootstrap.Progress`),
  and on failure it stays on this page showing the real reason and offering "Retry" and "Start over"; but no "estimated time" is given,
  and progress is not broken down to "which file of the rootfs is being extracted".

**Completion criteria**
- [ ] Resume after network loss works
- [ ] There is an isolation risk warning
- [ ] Configuration can be exported and imported without leaking the key

---

## M4 · Testability and engineering (can run parallel to M2/M3)

**Goal**: turn defects that "only show up when a human reviews the code" into ones "automation can catch". Several P0s in the first four rounds were
only found by manually checking the Android documentation (such as the foreground service that always crashes); that class should be caught by tests.

> **Current state (2026-10-30)**: unit tests have grown from **148 cases** then to **1060 cases / all 1060 green**, script self-check 33/33.
> **What was actually done is "making the decision core testable"**: the decisions inside fixes were extracted into top-level pure functions with no Android
> dependencies, pinned down by `DshRuntimeRaceTest` / `R24StateMachineTest` etc.; from R26 on, "pure function + pipeline assertions"
> were added (`reconcileInstalling` etc.). **What was not done is the service layer itself** (`DshRuntimeService`'s
> `startForeground` timing still relies on code review; the project has not introduced Robolectric); `R24SeamTest` is only
> **a smoke test of the test scaffolding itself** (verifying that `@Before/@After` run in pairs), not a service contract test — don't take it as evidence.
> **CI is still missing** — there is no `.github/workflows` in the repository; the three lines "compile + scripts + unit tests" currently rely on running `release.sh` by hand.

**What to do**
- **Give `DshRuntimeService` a testable abstraction** ❌ Not done: `startForegroundCompat` still calls `startForeground` directly,
  with no injection point; the P0 contract "call startForeground before any return" is currently guaranteed only by code comments and on-device behaviour.
  Doing it would mean introducing Robolectric or extracting the service body into a pure logic class — not a small cost, so evaluate before scheduling.
- **Inject "process/time/Context" into the `DshRuntime` state machine** ◐ Half done: the decision core (state ownership, reuse criteria,
  pid file ownership) has been extracted into pure functions with unit tests; but "process/time/Context" themselves are still not injected, so the
  on-device timing (stopping fully before starting, kill races) can only be verified on a real device, and unit tests cannot cover it.
- **Move to CI** ❌ Not done: wire the three verifications (full `compileDebugKotlin`, script self-check **33 items**, JVM unit tests **1060 cases**)
  into CI; the Gradle ksp stall under the offline sandbox remains (`run-compile.sh` now works around it with `--offline`).
- **Fix the aapt2 errors in `strings.xml`** ❌ Not done (**low priority**, does not block releases): `values-de/fa/ja/pt-rBR/ru` etc. report
  `multiple substitutions in non-positional format`. **The fifth round confirmed these are "warnings", not "errors", and do not block
  `assembleDebug`/releases** (measured: `mergeDebugResources` prints them and the build continues). It is still recommended to switch to positional parameters (`%1$s`…)
  to silence the warnings; **never** use `formatted="false"` — that would stop `getString(id, args)` arguments from being substituted
  (the UI would literally display `%s`). Not introduced by dsh, it predates the repository, so it is recommended to file it separately.

**Completion criteria**
- [ ] `DshRuntimeService` / `DshRuntime` critical paths have unit tests — **half**: the `DshRuntime` side has extracted pure functions that
      are pinned down (`DshRuntimeRaceTest` / `R24StateMachineTest` etc., the 1060 cases include R28's call-site contract tests);
      the `DshRuntimeService` side **has none** (no Robolectric, `startForeground` timing still relies on code review)
- [ ] CI runs the three lines compile + scripts + unit tests (**there is no `.github/workflows`**; currently only `release.sh` run by hand)
- [x] `assembleDebug` is no longer blocked by strings resource errors (the fifth round confirmed they are only warnings; `output/` has accumulated 22 fix builds, fix1~fix22)

---

## M5 · Distribution and ecosystem

**Goal**: be able to ship the finished product to other people; keep up with dsh upstream's fast iteration.

> **Current state (2026-10-30)**: **the release channel is up and running** — signing configuration (`DshReleaseKey`),
> the three-part version numbering convention `dshVersion`/`dshVersionCode`/`dshFixLabel`, per-version release notes in `RELEASES.md`,
> the fixN historical builds and `SHA256SUMS` in `output/`; APKs are distributed as **GitHub Release attachments**
> (measured with `gh release list`: 28 Releases since 0.1.0; of fix1~fix22 only fix5 was not released separately).
> **What is not done is rootfs distribution optimisation and the compatibility matrix** — the APK is still "a whole rootfs package", and there is no written
> "dsh × Node × rootfs" verification matrix. Upstream is also not tracked closely enough: the preinstalled/verified dsh version is stuck at
> 0.1.6-alpha.2, while upstream `latest` is already 0.2.1-rc.5 (BL-191).

**What to do**
- **Release channel** ✅ Landed: signing strategy (`signingConfigs` in `FCL/build.gradle.kts`), version numbering convention
  (`gradle.properties`, three parts), changelog (`RELEASES.md` + `CHANGELOG.md`);
  the distribution method is settled as **independent distribution** (via GitHub Release, not following the FCL mainline).
- **rootfs distribution optimisation** ❌ Not done: putting hundreds of MB of rootfs straight into the APK is too heavy; still to choose between
  "download the rootfs from a trusted source on first launch" / "split the APK by ABI" / "ship a slimmed rootfs in the APK + complete it on first launch".
  The related prerequisites are BL-191/BL-192 (rootfs rebuild).
- **Follow dsh upstream** ◐ Half done: the `version` file / `scriptsVersionPair` script-version comparison mechanism exists,
  and the install page can install 0.2.x; but **the dsh preinstalled in the rootfs is still 0.1.6-alpha.2**,
  and there is no fixed rhythm for "periodically verify a new version and lock it". Watch upstream's changes to native module ABI, profiles and authentication.
- **Multi-version compatibility matrix** ❌ Not done: there is no written matrix recording "which dsh version × which Node × which rootfs" has been verified.

**Completion criteria**
- [x] There is a distributable signed APK and release notes (**from fix6 / 2026-10-19**: the `release` variant, the formal package name,
  not debuggable, new private key `release-key/dsh-release.jks`; every version has release notes in `RELEASES.md`)
- [ ] The rootfs distribution plan has landed (no longer making the APK swell needlessly)
- [ ] There is a dsh version compatibility matrix, updated regularly

---

## M6 · Capability expansion (long term)

**Goal**: once it is stably usable, explore a bigger space. All are **optional** directions, to be decided as needs arise.

- **More dsh profiles**: currently focused on `web`; could explore `headless` (one-shot tasks/automation) and `sdk`.
- **More capability integrations**: the user currently only wants "chat/coding agent + shell/files"; if it is needed later,
  evaluate MCP plugin integration then (computer-use/browser-use is explicitly out of scope).
- **More device form factors**: tablet/foldable adaptation, desktop mode; FCL itself targets foldables, so there is a foundation.
- **Performance and power**: memory/power optimisation for the long-running Node process, automatic suspend/wake when idle.

---

## Priority cheat sheet

- **Critical path (2026-10-30)**: **on-device verification of fix22** — ①whether the `/assets` cache-header plugin really takes effect
  (an external browser opening it a second time no longer re-downloads the ~2 MB dist; the plugin package already had `immutable`, so that is not counted);
  ②whether the WebView gate is no longer re-asked after copying the link. If these two do not pass, M2's "stable under repeated operation" cannot be discussed.
  Next, two **upstream issues** are in the way: `dsh-host-frontend-static/lib/index.js:73` sends no cache header at all for `/assets`
  (locally it can only be worked around via `launcher-assets.mjs`; upstream cannot be changed),
  and `dsh-client-ui-settings-account` is 3.53 MB as a single package / `lib/client.js` 3.42 MB (about 3.2 MB of inlined base64).
- **Right after that**: **the rootfs rebuild** (BL-191/BL-192: bump Node, refresh the preinstalled dsh and script versions),
  which is the hard prerequisite for M5's "follow upstream" and also for M2's "install/delete 20 times" on-device stress test.
- **Can be done in parallel**: M4 testing and engineering (CI = T-D3 in batch D; the service-layer testable abstraction is not done), M3's resume/config export,
  batch D's T-D2 (R8/`resConfigs` APK size trimming) and T-D5 (1.0.0 release rehearsal).
- **Nice to have**: the rest of M3's polish, M5's rootfs distribution choice.
- **As the situation allows**: M6 expansion.

> In one line: **the shell works (M0 ✅), the base is connected too (✅), first light on a real device happened long ago and more than once (M1 ✅, now fix22);
> the bottleneck now is not that features are unwritten, but that "on-device stress testing has not been done (M2 criteria still empty) + the rootfs is too old (M5 out of step with upstream)".**

---

## Relationship to the other documents

- How to verify on a real device, and what to verify: see **T6 in `TASKS.md`** (the original checklist) + `PLAN.md §8.5` (the latter is an old record of the home-grown
  flow; its steps are still usable for reference, but the "fill in the large proot/rootfs files" part is out of date — those files are already in place).
  **What should be verified now** is governed by the "To be verified on a real device" section of `TASKS.md`.
- How each milestone lands in the code: see the design documents under `docs/design/` (`app-shell.md`, `proot-chain.md` etc.);
  the correspondence between milestones and the early batches is M1≈batch A, M2≈batch B, M3≈batch C, M5≈batch D (**only approximate** — do not infer milestone progress from batch numbers).
- **How the runtime base lands**: **the current implementation = home-grown proot layer + pre-packaged rootfs**,
  see `PACKAGING.md` (packaging) and `ROOTFS.md` (rootfs rebuild).
  `design/proot-engine-integration.md` is the **rejected alternative route** (a decision record; the W^X/PROOT_LOADER reasoning is valid),
  and `design/wx-exec-proot-loader.md` is the W^X principle.
- This document is updated as work progresses: tick the corresponding place once a milestone is achieved, and write the conclusions back into `TASKS.md`.
  **Refresh rhythm**: after each fix round ships a build, at minimum update "Where we are now" at the top and the "current state" references in this section;
  M2's four on-device criteria may only be ticked once they have actually been run (do not substitute "the code is written" for "verified on a real device").
