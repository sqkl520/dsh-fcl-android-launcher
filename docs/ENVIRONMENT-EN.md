[简体中文](ENVIRONMENT.md) | English

# Development environment setup

> The toolchain (JDK / Android SDK / NDK) is **not in this repository** — it is part of the machine, not of the project files.
> After switching devices or machines, re-prepare it according to this document.
>
> **Two verified environments**:
> - **A. Windows x64 (recommended)** — see §1; the cmake/ninja/clang bundled with the SDK run natively
> - **B. arm64 Linux** — see §2; the x86_64 tools inside the SDK/NDK need qemu wrapping

## 1. Windows x64 (recommended)

### 1.1 Installed components (measured, compiles)

| Component | Version | Common path | Notes |
|---|---|---|---|
| JDK | **17** (e.g. Microsoft OpenJDK) | `C:/Program Files/Microsoft/jdk-17.0.20.101-hotspot` | AGP 8.x requires JDK **17+**; the module's source/bytecode target is pinned to `JavaVersion.VERSION_17` (`FCL/build.gradle.kts`). On this machine `~/.gradle/gradle.properties` currently points at JDK 21 and the module still emits 17 bytecode — pin it to 17 per §1.2 if you want one less class of environment difference |
| Android Studio | 2026.1.3 (build `AI-261.26222.65.2613.15948027`) | `C:\Program Files\Android\Android Studio` | For writing code; command-line builds do not depend on it |
| Android SDK | platform **35**, build-tools **35.0.0**, platform-tools | `D:\Android\Sdk` | Package names: `Google.AndroidStudio` + `cmdline-tools` |
| Android NDK | **27.0.12077973** | `D:\Android\Sdk\ndk\27.0.12077973` | Only used for the `ptyjni` bridge |
| CMake (bundled with the SDK) | **3.22.1** | `D:\Android\Sdk\cmake\3.22.1\bin\{cmake,ninja}.exe` | Used by AGP `externalNativeBuild`; **runs natively on x64, no qemu needed** |

### 1.2 Three key wirings (miss one and nothing compiles)

```powershell
# ① Make Gradle use JDK 17 (the system default may not be 17) — write into the user-level gradle.properties
#    %USERPROFILE%\.gradle\gradle.properties
org.gradle.java.home=C:/Program Files/Microsoft/jdk-17.0.20.101-hotspot

# ② Tell Gradle where the SDK is — write into the repo-root local.properties (gitignored, not in Git)
#    <repo root>/local.properties
sdk.dir=D:/Android/Sdk

# ③ Let scripts / other tools find the SDK too (optional but recommended, user-level environment variables)
setx ANDROID_SDK_ROOT "D:\Android\Sdk"
setx ANDROID_HOME     "D:\Android\Sdk"
```

> ★ **`org.gradle.java.home` decides the JVM of the Gradle daemon**. On this machine that line currently points at
> JDK 21 (`C:/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot`), and compiling and packaging both work fine
> (the module's source/bytecode target is hard-coded to `JavaVersion.VERSION_17`); `./gradlew --version` will show
> `Daemon JVM: …jdk-21…`. If you want to avoid this class of difference, pin that line to 17;
> it also compiles without pinning, but **do not document "the daemon JVM" as "the JDK the project requires"**.

### 1.3 Line endings: scripts must be LF ⚠️

Git's `core.autocrlf` **must be set to `false`** (together with `core.eol=lf`):

```powershell
git config --global core.autocrlf false
git config --global core.eol lf
```

Why: files under `FCL/src/main/assets/dsh/scripts/` are **packed into the APK verbatim**;
`.sh` files are executed on device by `/bin/sh` inside proot, `.mjs` files (`launcher-assets.mjs`, a dsh plugin) are loaded by Node.
Once Windows checks them out as CRLF, `#!/bin/sh\r` makes the interpreter unfindable → every runtime script is dead.
`.gitattributes` already declares `text eol=lf` for that whole directory (including `.mjs`) as a backstop,
but **the global config must be right too**, or old checkouts will not correct themselves.

Verify (should print `i/lf  w/lf`):

```powershell
git ls-files --eol FCL/src/main/assets/dsh/scripts/
```

### 1.4 What to do the first time you open Android Studio

1. `File → Open` and pick the repository root (the level that contains `settings.gradle.kts`, **not** the `FCL/` inside it)
2. `Settings → Build → Build Tools → Gradle → Gradle JDK` → pick **17** (this Studio bundles JBR 25;
   do not treat it as the project's JDK baseline; AGP 8.x requires 17+ and the module target is pinned to 17)
3. The first sync downloads Gradle 8.14.4 + dependencies; it needs network access and takes several minutes

### 1.5 Pitfalls on this platform

| Symptom | Cause | Fix |
|---|---|---|
| `sdkmanager --licenses` hangs at `Accept? (y/N)`, piping `y` has no effect | the `.bat` wrapper swallows stdin | use `cmd /c "...sdkmanager.bat --licenses < yes.txt"` (a PowerShell pipeline does not work) |
| the first Gradle build is very slow | downloads the Gradle distribution + all dependencies | one-off; cached afterwards |
| compiling takes a long time (3–9 minutes) | full Kotlin/Java/resources | just re-run after a timeout; Gradle incremental build skips finished tasks |

## 2. arm64 Linux

| Component | Version | Common path | Notes |
|---|---|---|---|
| JDK | **17** | `/usr/lib/jvm/java-17-openjdk-arm64` | required by AGP 8.x |
| Android SDK | compileSdk **35**, build-tools **35.0.0**, platform-tools | `/opt/android-sdk` | can be overridden with `ANDROID_SDK_ROOT` (the scripts already support it) |
| Android NDK | **27.0.12077973** | `/opt/android-sdk/ndk/27.0.12077973` | only used for the `ptyjni` bridge |
| Gradle | bundled with the wrapper | `<repo root>/gradlew` | no separate install needed (`FCL/` has no `gradlew`; use the one at the repo root) |
| system cmake/ninja | any reasonably recent version | `/usr/bin/{cmake,ninja}` | `run-compile.sh` uses it to build ptyjni |

If `ANDROID_SDK_ROOT` points elsewhere, the scripts follow it automatically:

```sh
export ANDROID_SDK_ROOT=/path/to/android-sdk
```

## 3. Architecture-related (**arm64 Linux only**): x86_64 tools need qemu wrapping

> A Windows x64 machine **does not need this section** — the cmake/ninja/clang bundled with the SDK run natively on x64.

On an arm64 machine, these tools inside the Android SDK/NDK are **x86_64**:

- `$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/*` (clang etc.)
- `$SDK/cmake/<ver>/bin/{cmake,ninja,cpack,ctest}`

Running them directly gives **SIGILL** (AGP's `externalNativeBuild` reports `finished with non-zero exit value 132`).
Fix: write a wrapper script layer using `qemu-x86_64-static`:

```sh
# Example: the SDK-bundled cmake (same for the tools inside the NDK)
cd "$SDK/cmake/3.22.1/bin"
for f in cmake ninja cpack ctest; do
  [ -f "$f" ] || continue
  [ -f "$f.real" ] && continue          # skip if already wrapped
  mv "$f" "$f.real"
  printf '#!/bin/sh\nexec qemu-x86_64-static "%s.real" "$@"\n' "$(pwd)/$f" > "$f"
  chmod +x "$f"
done
```

### ⚠️ Double-wrapping trap (hit before, the error is extremely unclear)

If the loop uses a guard like `[ -f "$f.real" ] && continue`, **the `$f.real` just generated gets wrapped a second time in the same loop iteration**
(because `$f.real.real` does not exist yet, so the guard does not stop it). Result:

```
cmake → cmake.real(script) → cmake.real.real(the real binary)   # qemu executes a script → fails
```

- **Diagnose**: `ls $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/*.real.real | wc -l` — non-zero means it was double-wrapped
- **Fix**: run `mv -f X.real.real X.real` for every `X.real.real` (keeping the outer wrapper script)
- This project's NDK 27 once had **38 tools double-wrapped**; clang only behaved after the fix

## 4. Verifying the environment

**Windows (current)**:

```powershell
# Compile check (Kotlin + Java + resources + Manifest)
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:compileDebugKotlin :FCL:compileDebugJavaWithJavac `
  :FCL:processDebugResources :FCL:processDebugMainManifest

# Unit tests (run natively by Gradle, the sandbox MiniRunner stub is no longer needed)
.\gradlew.bat --no-daemon :FCL:testFordebugUnitTest

# Packaging (rootfs must be provided first, see ROOTFS-EN.md)
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:assembleRelease
```

> Shipping uses `assembleRelease` (T-D1, since 2026-10-19: release package name / not debuggable / dedicated key).
> The debug channel uses `:FCL:assembleFordebug` (debuggable + `run-as`, package name with a `.debug` suffix, can coexist with the shipping package).

On arm64 Linux, if you have the three scripts `run-compile.sh` / `run-tests.sh` / `build-apk.sh`
(plain POSIX sh, they detect the source tree automatically), they do exactly the same thing as the three Gradle commands above;
if you only cloned the source repository those three scripts are not in it, so just run the Gradle commands above (equivalent one for one).
You cannot run those `.sh` scripts on Windows either — use the Gradle commands above there as well.

---

## 5. Known environment pitfalls (both platforms)

| Symptom | Cause | Fix |
|---|---|---|
| compile interrupted by SIGTERM | compiling takes 3–9 minutes, exceeding the caller's time limit | **just re-run**; Gradle incremental build skips finished tasks |
| packaging stage times out | if the 142MB `rootfs.tar.xz` gets deflated again by aapt it becomes extremely slow | `build.gradle.kts` already has `noCompress += "xz"`; if it still times out, re-run |
| Android resource files raise `InvalidFileException` | duplicate resource file names copied from a case-insensitive filesystem (Windows) | check the conflicting resource names |
| `~/.ssh` does not persist across sessions | a trait of some container/sandbox environments | if the key is lost, regenerate it and add the public key to GitHub |

## 6. Disk

| Item | Size |
|---|---|
| rootfs extracted (after slimming) | ~860MB |
| build outputs and caches (`FCL/build`, `.gradle`) | ~700MB |
| APK (single) | ~155MB |

> It is advisable to clean `FCL/build` before packaging (it can be rebuilt at any time).
> Rebuilding the rootfs from scratch needs an extra ~1.5GB of scratch space (see `ROOTFS-EN.md`).
