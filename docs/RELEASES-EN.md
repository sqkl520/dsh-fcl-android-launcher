[简体中文](RELEASES.md) | English

# Release notes (for users)

> This file holds the **public** release notes — one section per version, explaining "what changes for
> you in this build". GitHub Release bodies are taken from here.
>
> Newest first. Format loosely follows [Keep a Changelog](https://keepachangelog.com/).
>
> **There is exactly one source of truth for the version number**: `dshVersion` in `gradle.properties`
> at the repository root. The newest version number in this file must match it.
>
> ⚠️ **Known inaccuracies in the historical sections**: the upgrade notes of the early builds (fix1 – fix7)
> say "the first launch re-extracts the scripts (a few MB)", but the size measures at only **about 34 KB**
> (the `scripts` subdirectory totals 34 013 B), and it does not even happen automatically for users who
> already installed the app (real script-version comparison on startup only landed in fix10). Those sections
> are kept verbatim as a historical record; **the newest section wins**. Do not estimate upgrade time from them.
>
> ⚠️ **Scope note for the English version**: only the newest section is translated in full. The ~27
> historical sections (fix21 back to 0.1.0) are summarized in the table below; their full text lives in the
> Chinese original [`RELEASES.md`](RELEASES.md). Historical details are rarely what you need — if you are
> chasing a specific old build, read the Chinese file (or ask in an Issue).

---

## 0.1.6-SNAPSHOT (fix26, current verified build)

> **The twenty-sixth round of 0.1.6** — fixing a defect where **your configuration silently disappears**:
> **the model provider you add is gone after one restart**.

### Fixed (`launcher.patch.yml` is no longer overwritten wholesale)

- **The problem**: on every start the launcher wrote a `launcher.patch.yml` into the instance directory,
  and that file is exactly what `--patch` points at when dsh starts. It used to be **regenerated in full** —
  so a custom provider you wrote in following dsh's documentation was wiped on the next start, **with no
  warning at all**.
- **Now**: the launcher claims only its own two entries (`client-hmr`, `launcher-assets`); every other entry
  is **preserved verbatim** and placed after the launcher's entries (dsh's patch semantics are
  "later entries win", so your configuration still takes effect).
- **On-device evidence**: before the fix, writing a provider and restarting took
  `grep -c mygateway launcher.patch.yml` from `1` to `0`; after the fix it stays at `1`.
- **One shortcoming, stated plainly**: the **on-device verification of this fix was not completed by me**
  (the device had already disconnected). Nine unit tests cover the merge logic, one of which specifically
  asserts that "the production call site really calls the merge function" (reverting the call site makes
  exactly that test go red). If your provider still disappears after a restart, please open an issue with
  what you observed.

### Upgrade notes

- **Over-the-top install works** (fix25 → fix26 needs no uninstall).
- Quality baseline: unit tests **1060/1060**, script self-check **33/33**.

---

## 0.1.6-SNAPSHOT (fix25, historical verified build)

> **The twenty-fifth round of 0.1.6** — fixing "the gate dialog body gets clipped": turn the font size up
> and the most important sentence becomes invisible.

### Fixed (gate dialog body no longer clipped at large font sizes)

- **Symptom**: at 1.3× system font size, the WebView gate dialog's body broke off mid-sentence, and what
  got cut was precisely "tapping 'Continue anyway' on this device will not restore these features" — i.e.
  the most important risk warning **did not exist on a small screen with large text**.
- **The root cause was one layer deeper than the ledger recorded**: the layout already **had** a ScrollView,
  but two things together defeated it — ① the ScrollView used `0dp` + `weight`, and weight only distributes
  leftover space when the parent has a definite height, while the dialog root is `wrap_content`, so there
  was never any scroll budget; ② the height check used `parent.getMeasuredHeight() < maxHeight`, and since
  the parent is `wrap_content` that condition is **almost always true**, so it always took the "it fits"
  branch and long bodies were silently clipped.
- **Now**: height is computed in code from the message's measured height plus a fixed chrome allowance, the
  layout uses `wrap_content` with explicit scrollbars, and any body length can be scrolled to the end.

### Upgrade notes

- **Over-the-top install works** (fix24 → fix25 needs no uninstall).
- Quality baseline: unit tests **1051/1051**, script self-check **33/33**.

---

## 0.1.6-SNAPSHOT (fix24, historical verified build)

> **The twenty-fourth round of 0.1.6** — a **reliability pass**: turning "silent failure" one by one into
> "failure you can see".

### Fixed (no more silent failures)

- **Orphaned dsh process cleanup no longer pretends to succeed**: the SIGQUIT return value used to be
  dropped, so the pid file was deleted even when the process was still alive. Now a signal that was not
  delivered skips the grace period and **logs it**; after SIGKILL a still-live process is explicitly logged
  as "STILL alive"; and the pid file is **only deleted once the process is confirmed dead**.
- **Leftover `rootfs.tmp` cleanup is no longer silent**: when deletion fails it records the **absolute
  path, an exception summary and the remaining size**, and judges the outcome with `exists()` rather than
  treating a boolean return value as the conclusion.
- **Instance log filenames now use the same folding as directory names**: the two used different rules, so
  `my app` / `my.app` / `my_app` — three **different instances** — shared one log file, and deleting one
  deleted another instance's log. Both now delegate to the same mapping.
- **The delete-transaction gate now covers the whole transaction lifetime**: previously the gate was
  released between two windows and concurrent writes could slip in.
- **Pid files, install pids, overlay replacement and rootfs replacement all became atomic writes**, each
  step forced to check its return value, with the "guess and move" fallback removed.

### Upgrade notes

- **Over-the-top install works** (fix23 → fix24 needs no uninstall).
- Quality baseline: unit tests **1037/1037**, script self-check **33/33**.

---

## 0.1.6-SNAPSHOT (fix23, historical verified build)

> **The twenty-third round of 0.1.6** — **security depth**: blocking "a forged line in process output
> steering the UI somewhere else".

### Fixed (URL adoption gate)

- **Addresses entering the WebView now pass three gates**: a scheme allowlist (`http`/`https` only), a
  loopback host, and the port. Red-team testing showed that `javascript:alert(1)//`,
  `file:///root/home/.credentials.yaml`, `data:text/html,…` and `intent://…` were previously adopted
  verbatim and passed to `loadUrl`. An **allowlist** rather than a denylist, because the set of schemes
  `loadUrl` accepts varies by WebView version and a denylist necessarily misses some.
- **Stricter log redaction**: `registerSecret` rejects overly short secrets (a single character would
  otherwise redact an entire log down to `***`).
- **Pages now rebuild correctly after being reclaimed**, and the script version comparison was corrected.

### Upgrade notes

- **Over-the-top install works** (fix22 → fix23 needs no uninstall).
- Quality baseline: unit tests **1027/1027**, script self-check **33/33**.

---

## 0.1.6-SNAPSHOT (fix22, historical verified build)

> **The twenty-second round of 0.1.6** — two things: **stop asking you questions you just answered**, and
> **add cache headers for the front-end static assets used by external browsers**; plus a re-measurement and
> correction of "how much does opening the UI actually download".

### Fixed (no more gate re-prompt after copying the link)

- **After a successful copy, the same dialog no longer comes back.** Previously you tapped "Copy link", the
  link went to the clipboard, and two seconds later the UI showed "Continue anyway / Copy link / Open in
  external browser" again — asking about the choice you had just made. Now, after a **successful** copy, it
  settles on a panel reading "Link copied. Paste it in your browser; tap Retry if you want to open it in the
  app." with four exits — retry / logs / stop / close. Switching back to the app from the browser no longer
  triggers another prompt.
- **Behaviour is unchanged when the copy failed or the confirm dialog was cancelled**: you still return to the
  gate dialog to decide again. Only "the link really made it into the clipboard" takes the new path.
- **Two things that are still not solved, stated up front**: leaving and re-entering the UI still prompts once
  (the flag only lives in the current page and is not persisted — persisting it would mean this device never
  warns about an outdated WebView again, and that warning is your only chance to learn that the embedded UI
  will break); and if a state replay happens while the dialog is open (for example, pressing Home and coming
  back), the gate dialog is still re-shown once.

### Fixed (external browser: cache headers for front-end static assets)

- **Files under `/assets` now carry `cache-control: public, max-age=31536000, immutable`.** dsh's own
  front-end server only writes `content-type` and sends no cache headers at all, so an external browser had
  to re-download the front-end files on every open. The launcher mounts a small plugin of about 130 lines
  (`launcher-assets`) that adds exactly this one header; the file names already contain content hashes, so
  `immutable` is safe. Measured: gzip still applies (a 77 KB CSS file compresses to 19 KB), `../` and
  `%2e%2e` both 404, and if the plugin fails to load it only logs one warning and the UI degrades to the
  previous no-cache behaviour — it never prevents startup.
- **dsh's development hot-reload polling was stretched from every 0.5 s to every 5 s** (inside proot every
  `stat` costs a ptrace round-trip). This only reduces constant background CPU; it does not change how fast
  the UI opens. That plugin (`client-hmr`) was not disabled outright — it is the only path by which
  "install a plugin and it appears without refreshing the page" works; with it off, the plugins page would
  still say "active" and you would see "installed, shown as active, but not on the page", which is worse
  than half-failing silently.

### Corrected (how much is actually downloaded on each open)

- **The real first-screen download is about 5 MB.** The earlier measurement only covered dsh's front-end
  `dist` and missed the **58 client plugin bundles** — they are not lazy-loaded; they are injected in one
  batch via `modulepreload` on first screen (9.27 MB uncompressed, **4.42 MB** after gzip). Add `dist` and
  fonts and the first screen is on the order of ~5 MB. These numbers were measured file by file, not
  estimated.
- **Only part of that 5 MB has to be re-downloaded each time**: the plugin bundles are served from
  `/plugins` with an `immutable` cache (those 4.42 MB download only the first time, or after the combo's
  `rev` changes); the roughly 2 MB of `dist` used to be re-downloaded on every navigation, and **this
  version's `/assets` caching plugin fixes that**. ⇒ About 5 MB on the first open, essentially nothing after.

### Upgrade notes

- **Over-the-top install is fine** (fix21 → fix22, no uninstall needed).
- **The scripts changed (11 → 12)**: after upgrading, **the first launch runs the preparation screen once** to
  re-extract the scripts (sub-second; rootfs and instance data untouched), and it will not repeat. If a
  launch with `--patch` exits before becoming ready, the script automatically retries once without `--patch`
  — an optimisation must never be the reason something fails to start.
- Quality baseline: unit tests **1037/1037**, script self-check **33/33**. The tests added this round
  specifically pin "the call site is really wired up", and five mutation verifications were run (revert the
  implementation to the old behaviour; the tests must go red).

---

## Historical builds (summarized)

The full text of each of these lives in the Chinese original [`RELEASES.md`](RELEASES.md). Only builds whose
upgrade notes matter to you today are called out in the notes column.

| Version | Headline | Notes worth knowing |
|---|---|---|
| `0.1.6-SNAPSHOT` (fix21) | Corrected three things the previous build stated wrongly; warn automatically about the empty-directory trap (hit three times); added a self-rescue path that does not depend on the list loading | — |
| `0.1.6-SNAPSHOT` (fix20) | Third R28 review: API key exposure surface warning + diagnosable empty-directory typo | — |
| `0.1.6-SNAPSHOT` (fix19) | Second R28 review: ghost-instance protection + call-site contract tests | — |
| `0.1.6-SNAPSHOT` (fix18) | R28 review rework: comment corrections + reject `..` + auto-create custom directories | — |
| `0.1.6-SNAPSHOT` (fix17) | `HOME` points at the instance directory + customisable start directory | — |
| `0.1.6-SNAPSHOT` (fix16) | Open in browser + stable ports, so an external browser can stay authenticated | — |
| `0.1.6-SNAPSHOT` (fix15) | External browser could not pick a workspace — `HOME` now points at the instance workspace | — |
| `0.1.6-SNAPSHOT` (fix14) | R26 wrap-up: cross-implementation consistency tests drop the mirrored implementation; mutation verification added | — |
| `0.1.6-SNAPSHOT` (fix13) | R26 item 3: tests upgraded from "pinning implementation" to "contract + pipeline" | — |
| `0.1.6-SNAPSHOT` (fix12) | R25 unified criteria: one definition of "is it installed"; failures are no longer swallowed | — |
| `0.1.6-SNAPSHOT` (fix11) | R24 state machine: startup retry and stop reliability | — |
| `0.1.6-SNAPSHOT` (fix10) | R23 review rework: four-branch gate dialog + no blank screen after copying + script version comparison wired up | **First build where the script-version comparison actually runs on startup** |
| `0.1.6-SNAPSHOT` (fix9) | Superseded by fix10 | — |
| `0.1.6-SNAPSHOT` (fix8) | Historical verified build | — |
| `0.1.6-SNAPSHOT` (fix7) | Historical verified build | — |
| `0.1.6-SNAPSHOT` (fix6) | **First identity change: a new app; the old package can coexist** | **Package name changes to `com.dsh.fcl.androidlauncher` with a dedicated key (not debuggable). fix6 does not inherit data from fix1–fix5** |
| `0.1.6-SNAPSHOT` (fix5) | Historical verified build — verified locally only, never uploaded to Releases | — |
| `0.1.6-SNAPSHOT` (fix4) | Historical verified build | — |
| `0.1.6-SNAPSHOT` (fix3) | Historical verified build | — |
| `0.1.6-SNAPSHOT` (fix2) | Historical verified build | — |
| `0.1.6-SNAPSHOT` (fix1) | Historical verified build | Package names carry a `.debug` suffix with the public debug key |
| `0.1.6-SNAPSHOT` | Base 0.1.6 build | — |
| `0.1.5-SNAPSHOT` | Earlier iteration | — |
| `0.1.4-SNAPSHOT` | Earlier iteration | — |
| `0.1.3-SNAPSHOT` | Earlier iteration | — |
| `0.1.2-SNAPSHOT` | Earlier iteration | — |
| `0.1.1-SNAPSHOT` | Earlier iteration | — |
| `0.1.0` | First published version | — |
