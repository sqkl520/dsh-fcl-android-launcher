[简体中文](TASKS.md) | English

# Task list (to-do)

> **This document is published publicly** — write only "what to do / what to verify"; details like local paths and internal workflow are not written here.

> Holds items that are **definitely planned but not done**. Once done, remove them from this list.
>
> **What this document is**: the "Completed" section is a **historical index** (kept, for looking back); P1/P2/P3 and "To be verified on a real device"
> are the **to-do / to-verify lists that are still current**.
>
> **Per-version tracking after fix7 is not in this document**: from fix6/fix7 on, every version's changes and fix notes are recorded in
> `RELEASES.md` (per version, user-facing). This document keeps only the part that is "still not done / still not verified",
> plus each round's **mandatory verification checklist** (T6).

## Version progress (as of fix26)

| Item | Value |
|---|---|
| Public version | `0.1.6-SNAPSHOT` (`dshVersion` in `gradle.properties`) |
| Version code / fix label | `dshVersionCode=132` · `dshFixLabel=fix26` |
| Current verification build | `0.1.6-SNAPSHOT-fix26` (current verification build) |
| Quality baseline | unit tests **513/513**; script self-check **28/28** |
| On-device end to end | 0.1.6 (batch A) lit up; since then per-version on-device feedback has driven the fixes (fix1~fix26) |

> The single source of truth for the version number is `gradle.properties`; the "current version" in `RELEASES.md` / `README.md` must match it.

## Completed (index kept, for looking back)

| Original number | Contents | Landed in |
|---|---|---|
| G5 | Page transition animation (fade-in + slide up 30dp / 250ms, played only when the position really changes) | commit `97151a2` |
| G2 | About page (description lines + link group, modelled on FCL's `AboutPage`) | commit `97151a2` |
| G1 | In-page multi-page container + tab bar (`DshMultiPageUI`; settings page = launcher settings \| about) | commit `73f3d99` |
| G3 | Theme customisation (colour picker dialog + numeric sliders + multi-icon rows) | commit `136d962` |
| G4 | File/image picking (system `ACTION_OPEN_DOCUMENT`) | commit `467c5c1` |
| — | Second on-device test fixes (DNS / duplicate-install guard / FCL-standard widgets / status misjudgement) | commit `ca95ab0` |
| — | First-run prerequisite page + home task area | commit `d285671`+`9cb4855` |
| — | **Animations 16/16 fully aligned** (including SwipeMenuLayout / WaveProgressView / AnimUtil) | commit `d4b1a92`…`4d79564` |
| — | APK output tidy-up + 0.1.1 packaging + 0.1.0 archiving | commit `26d3a8a`/`a262a7e` |
| **T1** | Instance detail page converted to FCL's row style (`DshInstanceSettingAdapter` + value rows/button rows) | commit `839e0fa` |
| **T4** | Right panel buttons follow FCL's form (icon+text clickable container; the original T4 premise "use `bg_right_menu_button`" turned out on checking to be an unused FCL resource, so the real pattern was followed instead) | commit `839e0fa` |
| **T2** | 12 languages + custom launcher name (`LauncherUtil` + edit row) | commit `8b49ea9` |
| **T9** | `.github/workflows` adaptation — **no longer needed**: FCL's original CI was deleted wholesale when the project moved to the PC (`92ba6df`; measured upstream `workflows total_count: 0`) | commit `92ba6df` |
| **Batch A** | On-device end to end worked for the first time (install→start→chat→clean stop→restart without EADDRINUSE) | `0.1.6-SNAPSHOT`, code 106 |
| **T-D1** | Shipping identity switch: formal package name `com.dsh.fcl.androidlauncher` + new signing + not debuggable (brought forward and landed in fix6) | fix6, code 112 |
| **T7 slimming** | rootfs `314,360,800 → 148,637,136` bytes (−52.7%), extracted 1436 → 862 MB; shipped to real devices since 0.1.3 | see `ROOTFS.md` |

### fix7 ~ fix26 completion overview (per-version details in `RELEASES.md`)

| fix | Main delivery | Survey/critique source |
|---|---|---|
| fix7 | Deployment page live log panel, activity rows during install, startup smoke self-check + broken-tree self-healing | author's fix6 on-device feedback (BL-165~167, no separate report directory) |
| fix8 | "Copy access link" exit (BL-168 🔴), five on-device problems on the deployment/log pages | R21, five tracks |
| fix9 | Third way out of the gate dialog + make it non-cancellable, but introduced "blank screen after copying" | R22, six tracks |
| fix10 | fix9 rework: buttons always attached + "Later" exit + `auth=yes\|no` trace + blank-screen fallback | R23 six-track critique |
| fix11 | seccomp retry actually forks, orphan proot eradicated, stop timeout 13s, process identity check, Stopping busy state | R23b S2 / R24 |
| fix12 | Five sets of "is it installed" criteria merged into one, fail-open changed to fail-closed, preparation page criteria from the same source | R23b S1+S3 / R25 |
| fix13 | Contract tests replace "pinning the implementation's shadow"; add development and contributing docs | R23b S6 / R26 |
| fix14 | fix13's comparison tests drop the mirrored implementation + mutation verification | R26 wrap-up |
| fix15 | External browser "can't pick a workspace": `HOME` points at the instance workspace | R27 P0-A |
| fix16 | "Open in browser" + fixed ports (external browser stays authenticated long term) | R27 P0-B/P0-C |
| fix17 | `HOME` now points at the instance directory itself + new "start directory" setting | R27 P0-A2 (P0-A rework) |
| fix18 | Auto-create custom start directories, reject `..`, three outdated comments | R28 critique |
| fix19 | Ghost-instance protection (no new directories under the runtime directory), saved value matches displayed value | R28 second round |
| fix20 | API Key exposure-surface warning, diagnosable typo'd empty directory | R28 third round |
| fix21 | Empty directory auto-warning `WARN home-has-no-subdir`, three incorrect explanations corrected | R28 C3 |
| fix22 | No re-showing the gate after a successful link copy (BL-221); `--patch` overlay + `/assets` cache-header plugin (BL-222) | R29 C3 items 6/8 |

## P1 · UI restoration

### ~~T3. Resource completion~~ — **checked, no action needed**
- Conclusion: of the 71 drawables FCL has and we lack, **not one needs to be added** —
  they are either FCL's own unused resources (`bg_right_menu_button` / `bg_game_menu_inset` /
  `bg_container_transparent_selected` / `ic_baseline_file_24` etc., zero references across the whole repository),
  or MC-specific (controller/modpack/account/renderer), or MC-page-only icons
- Lesson: `git grep bg_item` matches `bg_item_rounded` (substring false positive); when checking resource references, add word boundaries

### T5. Plugin management sub-page (waiting for the plugin system to settle)
- The settings page currently has 3 sub-pages (launcher settings | logs | about); FCL has 4. Add one sub-page once the plugin system is settled

## P2 · Runtime and stability

### T6. On-device end-to-end acceptance (critical) — **0.1.6 lit up; current verification build fix26**

> **Added 2026-10-30: verification tools**
> - **Whatever can run on a PC, don't push onto a real device**: `sh tools/host-dsh-check.sh` actually runs dsh on Windows,
>   covering the launch chain / config injection / front-end static asset cache headers (with a reverse control). Zero extra installation.
> - **What must go on a real device**: `sh tools/e2e-device.sh` (needs a phone connected) — install / cold-start landing point, log assertions,
>   gate dialog wording, screenshot evidence. With no device connected it returns code 2 (an environment problem, not a failure).
> - The **testable/untestable** boundary of both is described in `docs/DEV-PLAN.md §3.8` (internal document).

> **Current verification build**: `0.1.6-SNAPSHOT-fix26` (`v0.1.6-SNAPSHOT-fix26`, code 132).
> Historical control builds: `fix22`~`fix1` and the unsuffixed `v0.1.6-SNAPSHOT` (code 106, the control baseline) are mostly on the Releases page
> (fix5 is the exception: verified locally only, never uploaded) — generally do not install them; use them to reinstall and confirm "is this problem something a fix introduced" when a divergence appears.
> Historical control: the 0.1.2 (old rootfs, `8abaa3ea…`) and 0.1.3-and-later (slimmed rootfs,
> `216cd9ef…`) builds are still on the Releases page, and can be used to compare "did slimming delete something it shouldn't have".
>
> **0.1.6 verified** (confirmed by the on-device logs from before the third-round feedback): stopping kills node (code=137),
> real port read-back with PORT=0 (46501), and the install chain (554M of dependencies) all took effect; **dsh opened successfully on the real device**.
> **fix1 verified** (confirmed by the fourth-round feedback bebug): the cleanest stop on record (EXIT code=0, graceful exit);
> problems such as the compatibility dialog ghosting, the refresh spinner, the blank page when jumping out and not being able to get in while starting were fixed in fix2.
> **fix2/fix3 historical builds**: fix2 fixed the fourth round's 11 items; fix3 fixed the log chain (smart follow/K27/#7/#8b).
> **fix4/fix5 historical builds**: fix4 fixed bare-URL claiming / synthetic URL regression / WebView threshold 119 / stop validation;
> fix5 did full 401 coverage (probe-based token recovery) and, **at the author's request, was only verified locally and never uploaded to Releases**.
> **From fix6**: the shipping identity switched to the new package name + new signing (the old `.debug` package coexists with it, and data is not migrated).
> **fix7 ~ fix22**: each round was driven either by the author's on-device feedback or by the previous round's adversarial critique
> (e.g. fix11~fix14 came from Round 23b's S1~S6; the field logs quoted in fix22's notes came from fix16).
> Each round's mandatory verification checklist is written in the corresponding `RELEASES.md` section and is **no longer accumulated version by version in this document**; this document keeps only
> the basic acceptance items that are still valid and **the mandatory items for the current build (fix22)**.

- **Whether the DNS fix really takes effect** (can `npm install` successfully install 0.2.x) — 0.1.6 verified
- Time and success rate of the first rootfs extraction (about 862MB extracted after slimming + about 523MB for one instance; free space before installing ≥1.7GB) — 0.1.6 verified
- **Whether slimming deleted something it shouldn't have**: the `node` / `npm` / `dsh` items of `probe.sh` must all pass,
  `dsh web` must be able to start the service, and `npm install` must run (the latter depends on npm's internal node-gyp chain) — 0.1.6 verified
- The actual look and behaviour of the prerequisite page / home task area / instance detail page / right panel buttons — long-term regression item (adjusted further with each version's feedback)
- Whether `dsh web` can start inside proot, WebView token/cookie, keep-alive with the screen off, failure recovery — long-term regression item (worth a look every version)
- **fix2 new mandatory items** (the 11 fixes of the fourth round + existing priorities, see the `fix2` release notes) — **merged into the regressions from fix3 on**:
  ①exiting and re-entering while starting (relaxed entry); ②jumping out to a browser → back to the home page + no `authentication required`;
  ③refresh (with no data change) stops spinning; ④scrolling up through a long log to read history does not drift, and returning to the bottom is reliable;
  ⑤after locking the screen for a few minutes during install, coming back still works; ⑥tapping stop/delete again while stopping leaves no residue; ⑦install progress no longer replays the whole animation block
- **fix3 new mandatory items** (Round 15, see the `fix3` release notes) — **merged into later versions' regressions**:
  ①scrolling up in the log page to read history **is no longer dragged back** (smart follow); ②the log page scrolls more smoothly than fix2 (K27);
  ③new log lines **fade in** (#7); ④**tapping the left menu switches pages** with a click animation (including confirmation of the fix for the "menu does not switch pages" regression);
  ⑤switching language in settings **does not bounce back to the home page**; ⑥the same startup failure shows only one dialog
- **fix4 new mandatory items** (see the `fix4` release notes) — **merged into later versions' regressions**:
  ①**401 main chain**: start an instance → kill the app (swipe it out of recents, do not tap stop) → re-enter the app (the instance shows running = claimed)
  → open the UI → choose "Open in external browser" → **the browser should go straight into dsh (no more English 401 page)**;
  ②if a `no persisted token url` line appears in the log on the first claim after upgrading from an old version: it recovers after starting the instance once normally;
  ③**old WebView (<119)**: if the page breaks after choosing "Continue", **a prompt with upgrade instructions should appear again** (the dialog
  contains "where to update WebView"; this confirms the fix for "the prompt sometimes does not appear");
  ④after stopping an instance, the false report "proot is still alive after stopping" no longer appears (or if it does, it really is a leftover and worth diagnosing);
  ⑤normal start/stop/chat end to end without regression
- **fix22 new mandatory items** (see the `fix22` release notes):
  ①**no re-asking after copying**: open the UI → choose "Copy link" in the gate dialog → confirm the copy succeeded →
  the UI should stay on the "Link copied…" panel (the gate dialog is not re-shown), with four exits: retry / logs / stop / close;
  ②switching from the browser back to the app → **you should not be asked again** (previously you were); tapping "Retry" → the gate dialog appears normally;
  ③behaviour is unchanged when the copy fails or the confirmation dialog is cancelled (still returns to the gate dialog);
  ④**a second entry in an external browser gets faster**: refreshing / reopening the same instance in an external browser
  should return `cache-control: public, max-age=31536000, immutable` in the response headers (front-end files are no longer re-downloaded every time);
  ⑤normal start/stop/chat end to end without regression (the `--patch` overlay must not affect startup)
  > Known and unresolved (also written in the release notes; do not treat as a regression): **exiting and re-entering the UI still prompts once** for the WebView gate
  > (the marker is not persisted, deliberately); and if a state replay happens while the confirmation dialog is open (e.g. pressing Home and coming back), the gate dialog is re-shown once.

#### How to connect a device / how to grab logs

> Installing **avoids adb on the main path** (download the Release attachment directly in the phone's browser); the adb route is an optional enhancement for "install repeatedly + grab detailed logs".
> `adb` in the commands below means the Android SDK's `platform-tools/adb` (`adb.exe` on Windows).

- **Package name**: shipping package since fix6 is `com.dsh.fcl.androidlauncher` (no suffix); the debug channel (fordebug) is
  `com.dsh.fcl.androidlauncher.debug` (`applicationIdSuffix=".debug"`). The two can coexist.
- **Connect a device**: `adb devices` → you should see `<serial>\tdevice`; if it says `unauthorized`, allow USB debugging on the phone.
- **Install (adb route)**: `adb install -r dsh-fcl-android-launcher-<ver>-arm64.apk` → `Success`;
  if signing/version is incompatible, first `adb uninstall com.dsh.fcl.androidlauncher.debug` and then install (this triggers re-extraction).
- **Grab logs** (evidence priority: the app's on-disk logs first, logcat for crashes second):
  - On-disk runtime log: `adb shell run-as com.dsh.fcl.androidlauncher.debug cat files/dsh/logs/runtime.log`
  - Instance log: `... cat files/dsh/logs/instance-<id>.log` (paths in `DshPaths.kt` / `DshLogBus.kt`)
  - logcat: first `adb logcat -c` to clear the buffer, then `adb logcat --pid=$(adb shell pidof -s com.dsh.fcl.androidlauncher.debug)`;
    for crashes use `logcat -d AndroidRuntime:E CrashReporter:E '*:S'`.
  - In-app: Settings → Runtime self-check (runs `probe.sh`, last line `dsh-probe-ok`); settings/instance details → Logs.
- **Device space**: run `adb shell df -h /data` beforehand and make sure **≥1.7GB** is free (862MB extraction + temporary + about 523MB for one instance).
- **Record**: on failure always note the timestamp + the raw log lines (unmodified).

### T7. rootfs slimming and making it downloadable — **slimming done and verified on a real device (0.1.6)**

**Done** (using GNU tar `--delete` to remove entries from the original archive directly, no repacking):

| Removed | Reason |
|---|---|
| `/usr/lib/gcc`, `/usr/include`, `libasan/libtsan/liblsan/libubsan/libhwasan/libcc1`, `lto-dump` | GCC development environment — only used when **compiling on the spot**; but dsh's 5 native modules are all **distributed as prebuilds** (node-pty has `prebuilds/linux-arm64/pty.node`, koffi/sharp/the two node-addons each ship linux-arm64 binaries); measured: **not one** `/.node` file is a build product and `node-pty/build/Release/` does not exist ⇒ the compiler is not needed |
| `/usr/lib/python3.11` + interpreters other than `/usr/bin/python3` | Same as above (node-gyp's python dependency). **`python3` the binary is kept** (6.6MB) as insurance |
| `/usr/share/man`, `/usr/share/doc`, `/usr/share/info`, `/usr/share/gitweb` | Documentation |
| The whole perl family (`/usr/share/perl*`, `/usr/lib/*/perl*`, `/usr/bin/perl*`, `cpan`) | Nothing was running perl (apt's perl dependency is `perl-base`'s C tools, which are kept) |
| `/usr/lib/git-core` + `git`/`git-shell`/`scalar` | dsh does not depend on the system git when it brings its own git capability; `ssh` is kept |
| `/root/.cache/node-gyp` (56MB), `/var/lib/apt` (78MB), `/var/cache/apt` (75MB) | Build-time cache / apt indices |
| `gconv` (non-UTF-8 charset conversion), 6 large-language locales (fr/ru/uk/sv/es/de) | Save space; **UTF-8 + CJK + en are kept** |
| Symlinks for the 6 sanitizers (targets already deleted, leaving broken links) | Consistency |

**Kept**: `gcc`/`g++`/`cpp`/`make`/`pkg-config`/`python3` (a few MB each), all `node_modules` and
`/opt/node22`, apt/dpkg/openssl/ssh, and the `en`+`zh`+`ja`+`ko` etc. locales.

**Measured result**: `rootfs.tar.xz` **314,360,800 → 148,637,136 bytes (−52.7%, 158 MiB saved)**;
extracted **1436 MB → 862 MB**; entries 57,527 → 39,975.
sha256 (new version): `216cd9ef915a3512b9fedadfed7d5be6119422d120005c86b54d9e3b8ccea3f6`
(the old `5d762c30…` can still be retrieved from the 0.1.2 Release attachment).

> ⚠️ **Old and new must not be mixed**: `rootfs/version` was bumped in step (`…-slim1`), otherwise the app will not re-extract.
> The slimmed package has been released since 0.1.3 and passed on-device end to end at 0.1.6 (`node/npm/dsh` self-check + `dsh web`
> starting the service + `npm install`) — "it deleted the right things" now has direct evidence.

**Still not done**:
- Whether to keep `pkg-config` can be re-evaluated (it sits in the native compilation chain and is meaningless on its own — but it requires `--as-needed`
  to be used by the linker, and `libglib-2.0.so` in turn requires it, so keeping it is less trouble).
- Longer term: switch to **download on first launch + verify** (the APK does not contain the rootfs; it is downloaded after install), which can cut the APK size by more than half again.

### T8. Cache and cleanup UI
- npm cache (`dsh/npm-cache`) and rootfs size display + one-tap cleanup, in the settings page
- **Status (checked 2026-10-30): not done** — the settings page's "Launcher settings" currently has no cache/size/cleanup rows
  (the shared npm cache itself already works, see the comment at the top of `DshInstaller.kt`; what is missing is only the **display and cleanup entry point**)

## P3 · Other

### T10. Documentation sync — **the main body is done (2026-10-06); increments from 0.1.6 on are carried by each version's release notes**
- Done: the outdated "the runtime base will go with `:proot-engine`" statements in `PLAN.md` / `ROADMAP.md`
  were all corrected to "home-grown proot layer + pre-packaged rootfs"; `proot-engine-integration.md` is marked **not adopted** at the top;
  `PACKAGING.md`'s "Ubuntu/Alpine" and "you need to put in" were changed to the current state (Debian 12 + already in place).
- After 0.1.6: per-version changes always go in `RELEASES.md` (the GitHub Release body is taken from it) —
  this list only follows the "not yet done / not yet verified" part and does not repeat things version by version.
- Remaining (low priority): `PLAN.md` is still in its early-plan form (there is a staleness notice at the top pointing the way); a full rewrite would not pay off much.

---

## To be verified on a real device

> These are things that are **implemented but have not yet been run on a real device**. The code and unit tests pass, but unit tests cannot cover "system behaviour".
> Status markers: **0.1.6 verified** = confirmed by the on-device logs from before the third-round feedback; **to verify** = fixed, awaiting on-device confirmation.
> Each round's newly added mandatory items are collected in T6 (especially "fix22 new mandatory items"); this section keeps only the **long-term regression** ones.

### V1. Back key chain — **long-term regression item**

- Pressing back on the home page → **hand over to the system to exit** (not an immediate exit, and not doing nothing)
- Pressing back on a non-home page → **return to the home page**
- Pressing back in instance details → **go back one level at a time**: details → list (rather than backing all the way out of the app)
- The back button in the left menu → **behaviour exactly the same as** the key
- **Long-pressing back** (without releasing): "return to home first, then the whole shell is exited on release" should not happen

> The implementation uses `onKeyDown` rather than overriding `onBackPressed` (reason: the latter is taken over by
> `onBackPressedDispatcher` in API 33+, and overriding it would put "system back" and "our own back" on two different paths).
> The premise it relies on is that the manifest has not enabled predictive back and targetSdk is 34 — if targetSdk is raised to 35 in the future,
> this chain must move to `onBackInvokedDispatcher`, otherwise gesture back will bypass it.

### V2. Whether the process is really gone after stopping — **0.1.6 verified**

- Start an instance → stop → **start the same instance again**: it should not report the port as in use
- After stopping, wait 10 seconds and check the app log for
  the line `警告：停止后 proot 仍存活` ("warning: proot is still alive after stopping" — the literal log string is Chinese; if present, it did not stop cleanly and the scene needs investigating)

### V3. Instance detail page — **0.1.6 verified** (dsh opened successfully on the real device; the details/logs chain works)

- Tap an instance row → enter details; all three tabs (Run / Logs / Config) can be switched to
- The "Logs" tab shows only **this instance's** output (not everything mixed together)
- Switching to another tab and back: **the temporary page returns to the list** (this is intended behaviour — the temporary page belongs to the current tab's context)
- The detail page is no longer a full white page in light mode (that was a problem with the old standalone page)

### V4. Log routing — **to verify** (long-term regression item; the log page has gone through several rounds of changes in fix3/fix9/fix21)

- Settings → Logs: an **app-level** view (extraction/install/credential lines)
- Instance details → Logs: only that instance's lines
- On-disk files: one each of `logs/runtime.log` (app) and `logs/instance-<instance>.log` (instance)

### V5. Task area and terminal states — **to verify** (long-term regression item; the task block had wording and localisation changes in fix6/R18)

- Install a version → **the row does not disappear after the install finishes**, but drops to the lower half of the task area marked as completed
- Tap "Cancel" → the row does not vanish instantly, but turns into "Cancelled" (**not** "install failed")
- Start failure → the task area keeps a failed row **with the reason** (no longer just flashing in a dialog)
- The "Clear completed" at the head of the task area appears only when there really are history rows, and tapping it clears the lower half
- **Cancel** and **Stop** are two different icons (they used to look the same)

### V6. API Key handed over to dsh — **to verify** (long-term regression item; fix20 added the reminder "don't pick `home` as the workspace")

- **An instance can be started without a Key configured** (it used to be blocked by a "API Key not configured yet" dialog)
- The first time you enter the dsh UI it prompts once "where to fill in the Key", and **does not prompt on the second entry**
- Fill in the Key in **dsh UI → Settings → Models** → **it saves and you can chat** (this one matters most:
  the old version would **refuse the write** because the launcher had injected environment variables)
- The "Model" row is **not** present in the instance details' "Config"
- For an old installation that was upgraded: after starting once, the `credentials.env` / `credentials.enc`
  in the instance directory should be cleaned up automatically (the app log has a line `已清理遗留凭据文件…` — "leftover credential files cleaned up")
- **When picking a workspace, pick `workspace`, not `home`** — `home` holds the API Key
  (`.credentials.yaml`), and dsh's file interface allows reading files outside the workspace (the reminder added in fix20)
