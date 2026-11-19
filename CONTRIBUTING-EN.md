[简体中文](CONTRIBUTING.md) | English

# Contributing guide

## 1. What this project is, stated plainly

This is a **personal project**: a DeepSeek Harness (dsh) launcher for Android, built by a high-school
student with AI assistance (vibe coding) and ground down bit by bit on real hardware. It is based on
[Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher) (GPL-3.0).

Three honest facts up front, so your time is not wasted:

- **No response-time promise.** School, exams and being stuck on a bug can all mean weeks without looking at Issues.
- **No promise that "I'll build this feature".** Direction is set by the author; a genuinely good idea in an
  Issue may still not get built.
- **But PRs are read carefully.** A PR with tests and a clear motivation has a much higher chance than a
  "one-line change".

If you can accept those three points, read on.

## 2. Reporting problems

**Search the [Issues](https://github.com/sqkl520/dsh-fcl-android-launcher/issues) first** (including closed
ones) — many problems already have their root cause and workaround written up in the release notes, such as
the blank screen caused by an outdated WebView engine.

When opening a new Issue, please use the template and include these six things where possible:

| Needed | Why |
|---|---|
| **Version suffix** (the string on the About screen, e.g. `0.1.6-SNAPSHOT-fix26`) | Without it there is no way to tell whether you installed the current build; the README has had version-mismatch incidents in the past |
| **Device model + Android version** | proot / WebView / storage-path behaviour is strongly ROM-dependent |
| **Reproduction steps** | "Tapping start shows a blank screen" and "after installing dsh, tapping start shows a blank screen" are two different problems |
| **Log excerpt** | The in-app log page can copy out; instance logs and the global log are separate — paste the relevant one |
| **Whether you are rooted** | This project targets **unrooted** devices; a rooted environment changes proot's permission decisions |
| **Expected vs actual** | One sentence is enough, but it must be there |

### How to get logs

- **In-app** (easiest): the instance detail page's "Logs" shows that instance; the global log on the Settings
  page shows everything; long-press to copy.
- **Files on disk** (not via logcat — this project's dsh logs are not written to `android.util.Log`, so logcat
  will not show them):
  - Global: `<filesDir>/dsh/logs/runtime.log` (truncating rotation at 1 MB)
  - Instance: `<filesDir>/dsh/logs/instance-<id>.log`
  - How to fetch (**`run-as` only works on the debug channel**, not on release builds):
    ```sh
    adb shell run-as com.dsh.fcl.androidlauncher.debug cat files/dsh/logs/runtime.log
    ```
- **Only** when the app crashes outright, or the native proot / pty layer errors (things Java-level logging
  cannot capture), use logcat: `adb logcat -d > logcat.txt`.
- Note: the more detailed on-device manual `tools/ondevice.md` lives in the **workspace snapshot repository**
  and is not included in the source repository — do not link it from documentation (it would 404).

When pasting logs, **redact API keys, tokens and access links**. dsh's access link carries credentials;
pasting it into a public Issue is equivalent to publishing your instance.

## 3. Proposing changes

**Open an Issue before writing code.**

The reason is practical: some things in this repository that "obviously should be changed" are the way they
are **because a specific bug was hit** (for example why the unit-test task is `testFordebugUnitTest` rather
than `testDebugUnitTest`, why stopping waits 13 seconds, why "is it installed" is allowed only one
definition). If you do not ask first, you may well dismantle a line of defence that carries both comments and
assertions, and the PR will bounce back and forth.

In the Issue, state: **what symptom you want to fix / how you plan to change it / whether it touches existing
tests.** Waiting for a "go ahead" from the author saves both sides time.

## 4. Submitting a PR

```text
fork → branch off main → change → run it green locally → open PR
```

- Branch names are free-form; `fix/xxx` or `feat/xxx` is suggested.
- One PR does one thing. Drive-by formatting, drive-by renames and drive-by dependency bumps will all be
  asked to split out.
- **`sh run-tests.sh` must be green** (or the equivalent Gradle unit tests all green — see §6). Paste the
  result in the PR description.
- Commit messages are in Chinese and state "what changed + why"; that is the style of this repository's
  history, so follow it.
- **Do not** add signature tails such as `Co-Authored-By` / `Generated with ...` to commit messages or PRs.

## 5. Development environment

**Do not repeat the environment setup steps here** — read [`docs/ENVIRONMENT-EN.md`](docs/ENVIRONMENT-EN.md):

- §1 Windows (the primary environment today): the three wirings for JDK 17, Android SDK 35, NDK 27
  (`JAVA_HOME`, `local.properties`, environment variables).
- §2 arm64 Linux: the other wiring, for the phone sandbox.
- §1.3 **Line endings**: `*.sh` must be LF, `*.cmd` must be CRLF — the repository has `.gitattributes` as a
  backstop, but do not break it by hand.
- §4 Verifying the environment: three Gradle commands you can copy and run.

The one-line version: **JDK 17 + Android SDK 35 + NDK 27**, with the SDK path written into
`local.properties` (that file is not committed).

## 6. Local build and test

### 6.1 Windows (`gradlew.bat`)

```powershell
# Compile check (Kotlin + Java + resources + Manifest merge)
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:compileDebugKotlin :FCL:compileDebugJavaWithJavac `
  :FCL:processDebugResources :FCL:processDebugMainManifest

# Unit tests — note the task name is testFordebugUnitTest
.\gradlew.bat --no-daemon :FCL:testFordebugUnitTest

# Package (a release build needs the release key + a prepared rootfs; see docs/PACKAGING-EN.md, docs/ROOTFS-EN.md)
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:assembleRelease
```

> **Why not `testDebugUnitTest`?** This module sets `testBuildType = "fordebug"` (`FCL/build.gradle.kts`), so
> unit tests run on the `fordebug` variant and the task is `testFordebugUnitTest`. `testDebugUnitTest` also
> runs, but it is **not** this project's release gate and its result may disagree with the round that runs
> before CI.

### 6.2 Linux / phone sandbox (plain POSIX sh)

```sh
sh run-compile.sh    # compile main code first (the tests depend on its output)
sh run-tests.sh      # Kotlin unit tests + runtime script self-check
sh build-apk.sh      # produce an APK (release build) into output/
```

All three must be run **in order**: `run-tests.sh` uses the classes produced by `run-compile.sh`, and it has a
built-in "output freshness check" — if a source file is newer than the classes it errors out immediately, to
prevent a false green from stale implementations.

> **Where are these `run-*.sh` scripts?** They live in the root of the **workspace snapshot repository**, not
> in the Android source repository clone. If you only cloned the source repository, use the Gradle commands in
> §6.1 — they do exactly the same thing. `build-apk.sh` additionally requires
> `FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz` to be in place (142 MB, not in Git).

### 6.3 The standard for "green"

**0 failures**, and a test count **no lower than before your change**.

> We deliberately do not hard-code "currently N tests" — that number grows every round, so hard-coding it
> inevitably goes stale (the badge at the top of the README has already been wrong once for this reason:
> `394` stayed up until the real count was `438`). Use your own measurement: note the count before your change,
> run again after, and only allow it to increase, never decrease.

## 7. Code style

These are summarized from the existing code, not rules invented out of thin air:

- **Kotlin first.** New logic goes in `.kt`; Java is kept only for leftover FCL parts. Pure logic (criteria,
  state derivation, formatting) should be extracted into **pure functions with no Android dependency**, so JVM
  unit tests can pin them directly (see `planRepair`, `versionRowStates`, `resolveLaunchTarget` under
  `com.dsh.core`).
- **Comments explain "why", not "what".** A comment like `// increment the counter` gets deleted; "this must
  wait 13 seconds because the worst case is 12, and at 8 it would always take the timeout branch" has value.
  Many comments in this repository are records of bugs hit — **read the comments before changing code**.
- **Comments and commit messages in Chinese.** UI strings go through the three-language resources
  (Simplified Chinese / Traditional Chinese / English); do not hard-code Chinese into layouts.
- **One function, one job.** State machines, criteria and text mapping are written separately; do not both
  decide and show a dialog in the same method.
- **Do not rewrite a working state machine "to be more elegant".** Every line in those places corresponds to
  a real device incident.
- **The UI follows FCL**: widgets, theme and interaction stay within the FCL system (see
  `docs/design/app-shell.md` §2.5). Do not introduce a new style (Material3 components, a new icon library,
  etc.) without discussing it first.

## 8. Hard requirements for tests

This section is not negotiable:

1. **New behaviour must have assertions.** To fix a bug, first write a failing case that reproduces it, then
   fix until it is green. A PR that only changes code without tests will be asked to add them.
2. **No tautological assertions.** `assertEquals(2, 1 + 1)`, `assertTrue(true)`, assertions that assign to
   themselves — these have appeared in this repository and are now explicitly forbidden: an assertion must be
   capable of failing when the implementation changes.
3. **`sh run-tests.sh` must be green** (or `:FCL:testFordebugUnitTest` all green). Red means the work is not
   finished; do not explain it away with "that case was always flaky" — a flaky case is itself the thing to fix.
4. **State clearly what cannot be tested.** Anything that can only be verified on a real device (proot startup,
   WebView engine, SELinux) must be spelled out in the PR description as "verified on device model X / not
   verified, needs the author to check on hardware". Do not be vague.
5. **Unit tests must not depend on execution order.** Process-level singletons (such as `DshInstances`) must be
   reset in both `@Before` and `@After`.

## 9. What not to commit

| Path / file | Reason |
|---|---|
| `local.properties` | Local SDK path; already in `.gitignore` |
| `release-key/` (`dsh-release.jks`) | **The release signing private key** — leaking it means anyone can ship an "official" build |
| `FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz` | 142 MB, exceeds Git's single-file limit, and can be rebuilt (see `docs/ROOTFS-EN.md`) |
| `*.apk` / `output/` | Artifacts go out as GitHub Release assets, not into Git history |
| `build/`, `.gradle/`, `.kotlin/` | Rebuildable caches |
| Logs, `.hprof`, temporary screenshots | See `.gitignore` |
| The existing `debug-key.jks` / `key-store.jks` | Leftover FCL **debug** signatures — **do not replace, do not delete**, or the old packages' upgrade chain breaks |

Self-check before committing:

```sh
git status
git diff --cached
```

If any item above appears in the staging area, run `git restore --staged <file>`.
