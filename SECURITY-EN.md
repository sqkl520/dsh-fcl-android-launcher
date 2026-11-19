[简体中文](SECURITY.md) | English

# Security Policy

DSHarness-FCL-Launcher is an Android launcher that runs **on your device**. It uses proot to run a
Debian rootfs, a Node process and a dsh (DeepSeek Harness) service inside the app's private
directory, then loads `http://127.0.0.1:<port>/` in a WebView.

It is **not** a remote service, it has **no** backend of ours, it collects **no** telemetry, and it
**never uploads anything about you automatically**. (The one exception is one you press yourself: the
crash screen carries an upload button inherited from upstream FCL — see [Outbound](#outbound).)
What it does is start a web service that only the local machine can reach, and show you the UI.

Precisely because it is "run someone else's code on your device" (Node inside the rootfs, the dsh
you install, the model provider you configure), this document is deliberately explicit about **what
it requests, where it connects, where it keeps credentials, and what it cannot protect against**.

> ⚠️ **The most important point first:** **the rootfs is not a security sandbox.** proot is syscall
> forwarding — not a container, not virtualisation, and there is no kernel-level isolation. Whatever
> runs inside it holds every file permission this app holds. See "What this launcher cannot protect
> against" below.

---

## Supported Versions

Security fixes are issued for the **latest verified build only**.

| Version | Supported | Notes |
|---------|-----------|-------|
| Latest `-fixN` build (currently `0.1.6-SNAPSHOT-fix26`) | ✅ Yes | Security fixes land here |
| Earlier `-fixN` builds | ❌ No | Upgrade to the latest build |
| The `0.1.6-SNAPSHOT` non-fix build | ⚠️ Critical fixes only | Not yet promoted to `1.0.0` |

The project is **pre-1.0**; interfaces and directory layout will still change. There is no
backporting to older builds — this is a single-maintainer project, and maintaining parallel branches
would mean maintaining none of them well. If you are on an unsupported build, the fix is to upgrade.

---

## Reporting a Vulnerability

**Do not open a public issue for a security problem.** A public issue is visible to everyone,
including anyone who would exploit it before a fix exists.

**Preferred — GitHub Security Advisories:**

> <https://github.com/sqkl520/dsh-fcl-android-launcher/security/advisories/new>

This gives us a private thread, a place to develop the fix, and a way to publish an advisory that
credits you.

**Alternative — email:** `sqkl520soap@gmail.com` with `[DSH-FCL Security]` in the subject line.

You do not need to encrypt, and there is no PGP key. Do **not** paste production credentials, your
API key, your model provider URL, access tokens or personal data into a report; redact them first.

### What to include

- The version suffix (the string on the **About** screen, e.g. `0.1.6-SNAPSHOT-fix26`) — without it
  there is no way to tell whether you are on the current build
- Device model + Android version + **WebView version** (shown on the About screen, or
  `chrome://version`) — proot, WebView and storage-path behaviour are strongly ROM-dependent
- What the issue is, and what an attacker gains from it — **impact matters more than the mechanism**
- Reproduction steps, or a proof of concept. A minimal repro is worth more than a long write-up
- Relevant log excerpts, **redacted** (access token, API key, model provider URL, device paths)

### What to expect

| Stage | Target |
|-------|--------|
| We confirm we received it | within **7 days** |
| Initial assessment — accepted / need more info / out of scope | within **14 days** |
| Fix, or a documented mitigation you can apply yourself | within **90 days**; sooner for high severity |
| Public advisory and credit | after the fixed build is available |

These are **targets from one person**, not a contractual SLA. There is no bug bounty. This is a
free, volunteer-maintained project; if a report needs expertise I do not have, I will say so rather
than let it sit. You will be credited in the advisory and the CHANGELOG unless you ask otherwise.

### Scope — what counts as a security issue

**In scope:**

- **Leakage of the access token or an API key** into logs, the clipboard, exported files, crash
  reports, or screenshots
- **A third-party app reading this app's private directory** (`home/.credentials.yaml`,
  `dsh-token-url.txt`, the token URL in the pid file, all under `<filesDir>/dsh/`)
- **Making the WebView load a non-loopback address**, or a forged line in process output steering
  the WebView at an attacker-controlled host/port (see "URL adoption gate" below)
- **Arbitrary file read/write or path traversal**: user-controlled input (instance name, start
  directory, log filename) escaping its intended directory
- **An exported component being launched by a third party** with any of the above as a consequence
- **Network policy bypass**: making the app's Java/WebView network stack send **cleartext** HTTP off-device
- **Supply-chain issues**: CI workflows, release artifacts, substituted dependencies
- **Privilege escape through the proot / PROOT_LOADER layer**: writing from inside the rootfs to a
  location outside the app's private directory

**Out of scope** — these are expected behaviour or declared design trade-offs, not vulnerabilities:

- **Other apps on the same device can reach `127.0.0.1:<port>`.** Every app on Android shares one
  network namespace, so loopback ports are visible to every local process. That is a platform fact,
  **not a defect in this launcher**. The protection is the access token; see "Access token" below.
- **Code inside the rootfs can do anything the app can do.** See "What this launcher cannot protect
  against". What you install into the rootfs is your own choice.
- **The model provider you configure receives your conversation content.** That is what it is for.
  What is sent to it is decided by the dsh and provider you chose, not by the launcher.
- **Attacks requiring root or an unlocked bootloader.** Those are past the application permission boundary.
- **Upstream bugs**: dsh itself, Node, Debian, WebView, FCL. Report those upstream; if an upstream
  defect is **amplified into extra risk by this project**, that part is ours.
- **Anything in a version not listed as supported above.**
- **"The app white-screens / crashes on an old WebView."** That is a known platform version limit
  with an explicit gate and a documented workaround, not a security issue.

### Known non-issues

Behaviour that gets reported as a bug or a security concern but is intentional:

1. **"The address bar has `?token=...` when I open in a browser."** Intentional. That token is dsh's
   authentication; the UI does not open without it. It is also why "Copy link" exists — you decide
   which browser to hand it to.
2. **"The app requests `INTERNET`."** Needed to fetch the dsh version list, to `npm install` inside
   the rootfs, and for dsh to reach the model API you configure. The app reports to no server of ours.
3. **"`android:allowBackup` is `true`."** It is `true` in the manifest, but `<filesDir>/dsh/` — that
   is, **all** runtime data (rootfs, instances, credentials, logs) — is excluded wholesale from cloud
   backup and device transfer in `backup_rules.xml` and `data_extraction_rules.xml`. See "Backup" below.
4. **"The app allows cleartext HTTP."** It does not, by default. Only `127.0.0.1` and `localhost` are
   exempted, because the WebView has to load the local dsh. See `network_security_config.xml`.
5. **"Off-site links pasted into the conversation get handed to the system browser."** Intentional.
   The WebView carries loopback addresses only; everything else is punted out, so arbitrary sites are
   never rendered in a WebView context that holds a credential.
6. **"After switching to an external browser, the log shows the port and whether a credential was
   present, but not the token."** Intentional. The hand-off trace records `scheme://host:port` and
   whether a credential was attached, and **never the token value**.

---

## Permissions — what is requested and why

| Permission | Why it is needed | Can it be omitted |
|------------|------------------|-------------------|
| `INTERNET` | Fetch the dsh version list (npm registry), `npm install` inside the rootfs, dsh reaching the model API you configure | No |
| `ACCESS_NETWORK_STATE` | Distinguish "no network" from other download failures instead of misreporting | No |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` + `FOREGROUND_SERVICE_SPECIAL_USE` | dsh is a long-lived process; it must not be killed mid-task by the system | No |
| `POST_NOTIFICATIONS` | A foreground service must show a persistent notification (user-granted on Android 13+) | Can be denied; only affects notification visibility |
| `WAKE_LOCK` | Keep the CPU awake during a task so long runs are not suspended halfway | No |

**Not requested**: storage access (`MANAGE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` /
`WRITE_EXTERNAL_STORAGE`), installing other apps (`REQUEST_INSTALL_PACKAGES`), camera, microphone,
location, contacts, SMS, call log, or querying installed apps. Everything lives in the app's private
directory, so no storage permission is needed.

**Exported components**: the only **user-facing** `android:exported="true"` component is
`SplashActivity` (the launcher entry point). The `DshRuntimeService` runtime service and the app's own
`ContentProvider`s are `exported="false"` and cannot be started directly by third-party apps. The
`FileProvider` uses `grantUriPermissions="true"`, so access is granted per-URI.

Two facts from the packaged result, stated plainly:

- The build merges in androidX's `androidx.profileinstaller.ProfileInstallReceiver`, which is also
  `exported="true"`; it is protected by the system-level `android.permission.DUMP`
  (signature|privileged), so **third-party apps cannot actually invoke it**.
- There is also androidX's `InitializationProvider`, which is `exported="false"`.

### Backup

`android:allowBackup` is `true` in the manifest, but **all runtime data is excluded**:

| Rule file | Applies to | Excluded |
|-----------|-----------|----------|
| `backup_rules.xml` | Android 11 and below | `domain="file" path="dsh/"` |
| `data_extraction_rules.xml` | Android 12+ (cloud backup + device transfer) | Same |

`domain="file"` means `<filesDir>`, and the runtime root is exactly `$filesDir/dsh`
(`DshPaths.ROOT_DIR`) — so the rootfs, instance directories, `.credentials.yaml`, the pid/sidecar
token files and the logs **are all inside the exclusion**.

Why exclude the whole directory: the backup quota is about 25 MB while this directory starts at 1 GB;
and more importantly, after a device change the `version` file is compared against the local assets, so
a restore would still require re-extraction, while the instance list can be rebuilt from the UI.

`<filesDir>` also holds two files that are **not** in the exclusion list: `startup-failures.log` (a
startup-failure stack trace written by `FCLApp`) and `app_log/` (FCL's own logs). Neither **contains
credentials** (the FCL log path applies its own token replacement), which is why they are not excluded
— recorded here so you do not have to infer it.

---

## Network — where it connects, and whether you can turn it off

### Outbound

| # | Connection | Destination | Initiated by | Can it be turned off |
|---|-----------|-------------|--------------|----------------------|
| 1 | dsh version list | `https://registry.npmjs.org` | The launcher (`DshRegistry`) | Does not happen unless you use the versions page |
| 2 | `npm install @deepseek-ai/dsh@<version>` | `https://registry.npmjs.org` | npm inside the rootfs (native process, raw sockets) | Does not happen unless you install a new version |
| 3 | Model API calls | **The endpoint you configure** | dsh (Node inside the rootfs) | Decided by dsh's own configuration |
| 4 | Off-site links you explicitly open | The site you clicked | The system browser (**not** this app) | Does not happen unless you click |
| 5 | **Crash log upload** | `https://api.logshare.cn/1/log` (Chinese locale)<br>`https://api.mclo.gs/1/log` (otherwise) | The launcher (`HttpRequest`, inherited from FCL) | **Only when you press "Upload log" on the crash screen** |

There is **no** backend of ours, no telemetry, no crash-report server **of our own**, and no update
checker.

⚠️ **But the crash screen does carry a third-party upload button**, inherited from upstream FCL. Only
when you actively press "Upload log" is the crash text POSTed to a third-party log-sharing service
(`api.logshare.cn` in a Chinese locale, otherwise `api.mclo.gs`). **It never fires automatically.**
That path's text also **does not go through** [log redaction](#access-token) — see "Known residual
risks" below.

To verify (this app's Java network layer only):

```bash
grep -rn "HttpURLConnection\|openConnection\|new URL(\|new Socket" FCL/src/main/java/com/dsh/
```

The command also matches **comments**, so check each hit for whether it is a real call. There are
three real network calls:

| Location | Purpose | Target |
|----------|---------|--------|
| `DshRegistry.kt` | Fetch the dsh version list | `https://registry.npmjs.org` |
| `DshRuntime.kt` (`Probe.isPortOpen` / `probeTokenUrl`) | Probe whether the **local** dsh is still alive and whether the token is still accepted | `127.0.0.1` |
| `LogSharingUtils.kt` (in `com/tungsten/fcllibrary`) | The crash screen's "Upload log" button | Item 5 above |

> `DshDownloadViewModel.kt` also mentions `HttpURLConnection`, but **only in a comment** — it is a
> caller of `DshRegistry.fetchVersionsCached()` and issues no request itself.

> `com/tungsten/fclcore/` also carries a generic HTTP toolkit inherited from FCL (`HttpRequest` /
> `NetworkUtils`); that is a **request layer** and does not decide where to connect — the caller
> supplies the target. It has **exactly two users** in this project: `DshRegistry` (the npm version
> list) and `LogSharingUtils.uploadLog` (the crash screen's upload).

`npm` / dsh inside the rootfs are native processes that use raw sockets and bypass this layer
entirely — where they connect is decided by npm's and dsh's configuration, not by the launcher.

### Inbound

**dsh binds loopback only.** At launch the launcher passes `HOST=127.0.0.1` explicitly (the
`argvEnv` in `DshRuntime.kt`), and the script side defaults to `127.0.0.1` with a comment saying
"do not bind `0.0.0.0`" (`start-dsh.sh`).

⚠️ **But "nobody on the LAN can reach it" is not the whole story**: every app on Android shares one
network namespace, so **any app on the device can connect to `127.0.0.1:<port>`**. The real
protection is the access token; see the next section.

A detail worth knowing: dsh's session cookie name is `dsh-auth-` + the base64url of
`sha256(authority)`, and **the authority includes the port** (`127.0.0.1:38501`). Changing ports
therefore changes the whole cookie name — that is both the root cause of "immediately 401 after a
port change" and the reason **credentials cannot be reused across ports**: an attacker on a
different port does not inherit the previous session's cookie.

### Cleartext HTTP policy

`network_security_config.xml` defaults to **`cleartextTrafficPermitted="false"`**, with an exception
only for `127.0.0.1` and `localhost`:

```xml
<base-config cleartextTrafficPermitted="false" />
<domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="false">127.0.0.1</domain>
    <domain includeSubdomains="false">localhost</domain>
</domain-config>
```

Meaning: if some code path is ever misdirected into sending a cleartext request off-device, it
**fails outright** instead of silently leaking the token/cookie. This policy covers only the app
process's Java/WebView network stack; apt/npm inside the rootfs are native processes and are not
subject to it (otherwise dependencies could not be installed).

---

## Credentials — where they live and how they are protected

### Access token

On startup dsh prints an address like `dsh web: http://127.0.0.1:3080/?token=XXXX`. This token is
**per-process, base64url, 43 characters**, and is dsh's only built-in authentication.

By design it necessarily appears in a few places: **memory, the WebView URL, and the clipboard**.
The clipboard one needs stating more precisely — there are two ways it gets written:

| Case | Extra confirmation |
|------|--------------------|
| You press "Copy link" | Yes, you know exactly what you are copying |
| You choose "Open in browser" (the gate dialog's external-browser button, the instance page's browser button, the crash hint's hand-off) | **No** — the hand-off **copies first, then navigates**, as a fallback for browsers that drop the query string (BL-225) |

So: **if you have ever used "Open in browser" once, a token-bearing address is sitting on your
clipboard**, whether or not you copied it deliberately.

Three things limit how far it spreads:

1. **Log redaction.** Every line entering the log bus (including files written to disk) has
   `token=XXXX` replaced with `token=***`, plus a second pass that replaces any registered token
   value. Registering a secret must pass **five gates**: a lower length bound (≥8, so a single
   character cannot redact an entire log), an upper bound (≤256), token shape (base64url characters
   only), not shadowing contract vocabulary (which would silently break log attribution), and
   capacity (≤16 entries). **When capacity is full, new registrations are rejected rather than old
   entries evicted** — eviction would turn a real token that already leaked into a log back into
   plaintext.
2. **Bounded start-log rotation.** `dsh-start-*.log` is a **token-bearing** startup log. Before each
   start it gets a **bounded** rotation: the newest **6** by modification time are kept forever, and
   the rest are deleted only once they are more than **7 days** old. The script deletes its own file
   only on a **normal exit**, so an app kill leaves it behind — meaning **several token-bearing log
   files can coexist within one session**. That is the deliberate price of keeping a recovery source
   (needed to adopt an orphaned process).
   ⚠️ The more direct angle: these logs live under `cacheDir` and are `--bind`-mounted as `/tmp`
   inside the rootfs, so **any process in the rootfs can read them** (and therefore the plaintext
   token).
3. **The hand-off trace omits the token.** When handing off to the system browser, the log records
   only `scheme://host:port` and whether a credential was attached.

⚠️ **Stated plainly**: the token-bearing URL is written in **plaintext** to the pid file and the
sidecar file `dsh-token-url.txt` (both in the app's private directory). This exists so an orphaned
process can be adopted after the app is killed and its token recovered — the token is per-process,
dsh does not persist it, and without this the only option would be a restart. **These files are not
encrypted**; their protection rests entirely on Android's app-private directory isolation.

### API key (model provider credential)

**dsh owns it; the launcher does not touch it.** The key lives in the instance's
`home/.credentials.yaml`, read and written by dsh. The launcher does not log it, does not inject it
into the environment, and does not pass it through its own code.

Historical leftovers: early versions had the launcher manage keys itself, leaving behind
`credentials.env` (**plaintext**) and `credentials.enc` (the launcher's own Keystore ciphertext).
Both are **actively cleaned up** on upgrade — otherwise a plaintext API key would sit in the
instance directory indefinitely.

⚠️ The **exact scope** of that cleanup needs saying, so nobody assumes "cleaned means gone":

- It covers only instance directories listed in **`instances.json`**. Orphan directories not in the
  list (e.g. historical leftovers) are **not** cleaned, and that plaintext key stays there.
- Deletion **fails completely silently** (no `onFailure` logging). It is retried on the next start,
  but you may never learn it did not work.
- To be certain, check the instance directories yourself for these two files.

⚠️ **A real exposure surface (already documented in the setting's description)**: dsh's file API
**allows reading files outside the workspace** (that is its design — the agent must be able to read
arbitrary files). So **if you pick `home/` as the workspace, the agent can read
`.credentials.yaml`**. The launcher's picker defaults to the instance directory (`workspace/` and
`home/` side by side) and the setting's description spells this out, but it **cannot technically
prevent it** — that is dsh's semantics.

### Clipboard

"Copy link" puts the **full token-bearing URL** on the system clipboard. That is deliberate (BL-225:
browsers such as Edge strip the query string from localhost URLs delivered by third-party apps, so
copy-paste is the fallback path).

⚠️ But **this does not only happen on "Copy link"**: any "Open in browser" also copies before
navigating (see the table above). So do not assume "I never pressed copy, so there is no credential
on the clipboard".

⚠️ The clipboard is **system-wide**: before Android 10 any focused app can read it; from Android 10
on only the focused app can, but where you paste is your decision. **You control this token's
exposure surface** — think about who you are handing it to before pasting.

---

## What this launcher cannot protect against

**This section is the most important part of this document.** Every item is a real limitation, not a
disclaimer.

1. **The rootfs is not a security sandbox.** proot is **syscall forwarding** — not a container, not
   virtualisation, no kernel isolation. Any code running inside the rootfs holds every file
   permission this app holds and can read and write everything under `<filesDir>/dsh/`, including
   `.credentials.yaml` and the token files. Packages you `npm install`, dsh plugins you add, and
   commands you have the agent run all sit at that privilege level.
2. **proot deliberately bypasses Android's W^X protection.** Under `targetSdk 34` the app's private
   directory is not executable by default; this project uses `PROOT_LOADER` to get around that so
   things can exec inside the rootfs. That is an **intentional weakening of a platform security
   boundary** in exchange for "run Linux without lowering targetSdk". The trade-off is recorded in
   `docs/design/wx-exec-proot-loader.md`.
3. **`<filesDir>/dsh` is mounted writable into the rootfs.** proot uses `--bind=` to expose the host
   directory at `/opt/dsh` in the guest, **writable**. That is required for dsh to install
   dependencies, and it also means processes inside the rootfs can rewrite the host's runtime files.
4. **Any app on the device can reach `127.0.0.1:<port>`.** See above. The token is the only barrier;
   once it leaks (logs, clipboard, a screenshot), any app on the device can take over that UI.
5. **The WebView has JavaScript enabled.** It must be — dsh's front end is a JS app. Mitigations:
   file/content access disabled, mixed content disallowed, third-party cookies disallowed, multiple
   windows disallowed, JS cannot open windows automatically, only loopback addresses stay in the
   WebView, and WebView remote debugging is enabled **in debug builds only**.
   ⚠️ **That does not mean "everything that should be off is off"**: the following are **not set
   explicitly and rely on WebView defaults** — `allowFileAccessFromFileURLs` /
   `allowUniversalAccessFromFileURLs` (default `true` on API ≤29, `false` on 30+; this app's minSdk
   is **26**), `savePassword` (default `true`), `saveFormData` (default `true`), `databaseEnabled`
   (default `false`). `allowFileAccess=false` already reduces the damage these can do to very little,
   but this is "not disabled" rather than "disabled", and this project **does not claim** that part is
   hardened.
   One good thing: `addJavascriptInterface` is **never called**, so pages cannot reach Java objects.
6. **We do not verify the integrity of npm packages or dsh plugins inside the rootfs** beyond what
   npm itself does. Supply-chain safety is decided upstream and by your choices.
7. **No protection against an already-compromised device.** On a rooted device, with an unlocked
   bootloader, or with malicious Xposed modules, app-private directory isolation does not hold and
   most guarantees in this document stop applying.
8. **No PGP, no bug bounty, no dedicated security response.** Response times are one person's targets.

---

### Known residual risks (known to us, not fixed)

These are **deliberate trade-offs kept for now**, not things we failed to think of. They are listed so
you can judge the risk yourself rather than discovering it after an incident.

1. **Text uploaded from the crash screen does not go through log redaction.** "Upload log" POSTs the
   crash panel's full text as-is (`LogSharingUtils.uploadLog`) and does not reuse
   `DshLogBus.sanitize`. Crash text is currently a stack trace and normally holds no token; but **if
   an exception message ever carries a token-bearing URL, this path is a direct bypass of redaction**.
2. **In-page loopback links do not validate the port.** `shouldOverrideUrlLoading` uses `isLoopback()`
   to decide whether to stay in the WebView, and it **compares host only, not port**. So an in-page
   `http://127.0.0.1:<any port>/` is navigated inside the WebView. This differs from
   `webUiUrlAllowed(url, expectedPort)`, which is strict.
3. **The `DEEPSEEK_*` environment-variable prefix is allowed unconditionally.**
   `ProotCommand.isAllowedEnvKey` ends with `key.startsWith("DEEPSEEK_")`. **No caller uses it today**,
   but it is a ready-made channel for "put the key into a child process's argv (where it shows up in
   `/proc/<pid>/cmdline`)". It is kept because upstream scripts may use the prefix; if you fork this
   project, replace it with an explicit allowlist.
4. **`::1` passes the host check but not the cleartext policy.** The host check accepts `::1`, while
   the `network_security_config.xml` exception lists only `127.0.0.1` and `localhost`. As a result
   `http://[::1]:port/` is blocked outright by the network-security policy — this is a **functional
   gap, not a security hole** (it fails closed).

---

## Known hardening (independently verifiable)

| Measure | Location | Notes |
|---------|----------|-------|
| URL adoption gate | `DshRuntime.kt` `webUiUrlAllowed` | See "Why this gate exists" below |
| Log redaction | `DshLogBus.kt` | Regex replacement plus a second pass over registered secrets; registration is bounded and rejects over-limit additions rather than evicting |
| Start-log rotation | `DshRuntime.kt` | Token-bearing `dsh-start-*.log` files get a **bounded** rotation: the newest 6 are kept, the rest only deleted past 7 days (see "Access token") |
| Cleartext HTTP disallowed by default | `network_security_config.xml` | Loopback only |
| Runtime directory excluded from backup | `backup_rules.xml` / `data_extraction_rules.xml` | All of `<filesDir>/dsh/` |
| WebView hardening | `DshWebViewActivity.setupWebView` | See item 5 above |
| WebView debugging in debug builds only | Same | Guarded by `BuildConfig.DEBUG`; off in release builds |
| Instance **id**/path sanitisation | `DshPaths.kt`, `DshRuntime.resolveHomeRootfs` | Rejects `..`/`.`, accepts guest-absolute paths only, falls back to the default on invalid input. Note the display **name** never participates in path construction |
| Truthful exit attribution | `DshRuntime.kt` | A failed stop no longer reports "stopped"; install errors are visible |

---

### Why this gate exists (a real attack chain)

This is not a hypothetical threat. `dsh-start-*.log` lives in the **shared** `TMP_DIR`, and
`ProotCommand` binds it as `/tmp` inside the rootfs — **any code in the rootfs can append a line to
it**. When the host parses that file, the regex only requires "looks like a URL"; it does not
validate the host.

So the full chain is:

1. Anything running in the rootfs (an npm package, a dsh plugin, a command you had the agent run)
   appends `dsh web: https://evil.example/?token=x` to `/tmp/dsh-start-*.log`;
2. The host reads it, probes that host with a GET, and a 3xx response marks it "live"; the URL is
   written to the sidecar and pid file;
3. It becomes `state.url` and eventually reaches `webView.loadUrl` — **the UI is steered to the
   attacker's site**, inside a WebView context that holds your credential.

The `https` variant really does render (the `network_security_config.xml` policy only blocks
cleartext http), so the cleartext policy **cannot** substitute for this gate. All three checks
(scheme allowlist + loopback host + port) are required — red-team testing showed that with the host
check alone, `http://127.0.0.1:<attacker port>/?token=x` was still adopted.

---

## Hardening recommendations (for users)

- **What you install into the rootfs is equivalent to what you grant this app.** Do not install dsh
  plugins from untrusted sources.
- **Do not pick `home/` as your workspace.** That lets the agent read your API key. The setting's
  description says so too.
- **Decide where you paste the copied link.** The clipboard holds the full token-bearing address.
- **Review logs before sharing them.** Logs are token-redacted, but **instance paths, model provider
  URLs, and the commands you had the agent run** are not.
- **Stay on the latest build.** Security fixes land in the latest `-fixN` only.
- **Keep the device itself clean.** On a rooted device the isolation assumptions in this document do
  not hold.

---

> This document describes the implementation as of **`0.1.6-SNAPSHOT-fix26`**. Security claims are
> answerable to the code: if you find this document disagreeing with the code, **the code wins**, and
> an issue pointing it out is welcome.
