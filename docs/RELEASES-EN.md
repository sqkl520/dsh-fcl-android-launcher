[简体中文](RELEASES.md) | English

# Release notes (for users)

> One section per version, explaining **"what changes for you in this build"**. GitHub Release bodies
> are taken from here. Newest first.
>
> **There is exactly one source of truth for the version number**: `dshVersion` in `gradle.properties`
> at the repository root. The newest version number in this file must match it.

## How to write these (read this before editing the file)

The structure is fixed at four levels. Do not improvise:

```text
## Version (fixN, status)        ← one ## per version
> One sentence on the most important change in this build

### Added / Fixed / ...         ← use Keep a Changelog's categories, only the ones that apply
### Upgrade notes              ← uninstall needed? re-extraction? anything to watch out for
```

**Six categories** (from [Keep a Changelog](https://keepachangelog.com/), in this order, omit the ones
that do not apply):

| Category | When to use it |
|----------|----------------|
| `Added` | A new feature users can see |
| `Changed` | Existing behaviour changed |
| `Deprecated` | Something about to be removed |
| `Removed` | Something removed in this build |
| `Fixed` | A defect that got fixed |
| `Security` | Security-related fixes (credentials, permissions, privilege) |

**Three hard rules** (these were the most frequently broken):

1. **`Fixed` may appear only once per version.** When several things were fixed, use `-` list items;
   do not open a second `### Fixed (something)`. Stuffing technical descriptions into the parentheses
   is what made these notes messy.
2. **Describe what the user experiences, not which line you changed.** Good:
   `The same dialog no longer reappears after a successful link copy`. Bad:
   `Reworked the lifetime of the gateCopied flag`. Technical detail belongs in `docs/BUGLEDGER.md`.
3. **No technical nouns in headings.** The heading is just `Fixed` or `Added`, never
   `Fixed (launcher.patch.yml overwrite issue)` — put any explanation in the item body.

> ⚠️ **Known inaccuracies in the historical sections**: the upgrade notes of the early builds (fix1 – fix7)
> say "the first launch re-extracts the scripts (a few MB)", but the size measures at only **about 34 KB**
> (the `scripts` subdirectory totals 34 013 B), and it does not even happen automatically for users who
> already installed the app (real script-version comparison on startup only landed in fix10). Those sections
> are kept verbatim as a historical record; **the newest section wins**. Do not estimate upgrade time from them.
>
> ⚠️ **Scope note for the English version**: only the newest sections are translated in full. Older
> historical sections are summarized in a table further down; their full text lives in the Chinese
> original [`RELEASES.md`](RELEASES.md). Historical details are rarely what you need — if you are
> chasing a specific old build, read the Chinese file (or ask in an Issue).

---

## 0.1.6-SNAPSHOT (fix26, current verified build)

> **The model provider you add no longer disappears.** Previously one restart wiped the provider you
> configured, with no warning at all.

### Fixed

- **Your custom provider survives a restart.** On every start the launcher rewrote a config file in the
  instance directory, and that file is exactly where dsh reads custom model providers from. It used to be
  **regenerated in full** — a provider you wrote in following dsh's documentation was wiped on the next
  start. Now the launcher maintains only its own two entries and leaves everything else untouched, so your
  configuration is no longer lost.
- **On-device evidence**: before the fix, writing a provider and restarting took `grep -c mygateway`
  from `1` to `0`; after the fix it stays at `1`.

### Known limitations

- **On-device verification of this fix was not completed by the author** (the device had disconnected).
  Nine unit tests cover the merge logic, one of which specifically asserts that "the production call site
  really calls the merge function" (reverting the call site makes exactly that test go red). If your
  provider still disappears after a restart, please open an issue with what you observed.

### Upgrade notes

- **Over-the-top install works** (fix25 → fix26 needs no uninstall).
- Quality baseline: unit tests **1060/1060**, script self-check **33/33**.

---

## 0.1.6-SNAPSHOT (fix25, historical verified build)

> **Risk warnings are no longer cut off when you increase the system font size.** The gate dialog body
> used to break off mid-sentence.

### Fixed

- **The gate dialog body is no longer clipped and can be scrolled.** At 1.3× system font size the WebView
  gate dialog's body broke off mid-sentence, and what got cut was precisely "tapping 'Continue anyway' on
  this device will not restore these features" — the most important risk warning did not exist on a
  "small screen + large text" device. Height is now computed from the content's actual measured size, and
  any body length can be scrolled to the end.
- **The root cause was one layer deeper than the ledger recorded.** The layout already **had** a scroll
  container, but two things together defeated it: ① it used `0dp` + `weight`, and weight only distributes
  leftover space when the parent has a definite height, while the dialog root is `wrap_content`, so there
  was never any scroll budget; ② the height check compared the parent's measured height against the
  limit, and since the parent is also `wrap_content` that condition is almost always true, so long bodies
  were silently clipped.

### Upgrade notes

- **Over-the-top install works** (fix24 → fix25 needs no uninstall).
- Quality baseline: unit tests **1051/1051**, script self-check **33/33**.

---

## 0.1.6-SNAPSHOT (fix24, historical verified build)

> **A "failures must be visible" pass.** Several error paths used to swallow the failure silently, so you
> could never work out what went wrong.

### Fixed

- **Leftover process cleanup no longer pretends to succeed.** The stop signal's result used to be
  discarded, so the pid file was deleted even when the process was still alive. Now a signal that was not
  delivered skips the wait and records it, a still-live process after a hard kill is explicitly logged,
  and the pid file is **only deleted once the process is confirmed dead**.
- **`rootfs.tmp` cleanup failures are no longer silent.** When deletion fails it records the absolute
  path, an exception summary and the remaining size, and judges the outcome by "is the file still there"
  rather than treating a boolean return value as the conclusion.
- **Instance logs no longer cross wires.** Log filenames and directory names used two different folding
  rules, so `my app` / `my.app` / `my_app` — three **different instances** — shared one log file, and
  deleting one also deleted another instance's log.
- **Concurrent writes can no longer slip into an instance deletion.** The guard used to be released
  between two steps.
- **Several critical writes became atomic**: pid files, install pids, the overlay replacement file and
  rootfs replacement — each step now checks its result, and the "guess and move" fallback is gone.

### Upgrade notes

- **Over-the-top install works** (fix23 → fix24 needs no uninstall).
- Quality baseline: unit tests **1037/1037**, script self-check **33/33**.

---

## 0.1.6-SNAPSHOT (fix23, historical verified build)

> **A security hardening: nobody can steer your UI to another site any more.** Plus a batch of stability fixes.

### Security

- **The UI only loads local addresses now; it can no longer be hijacked to an external site.** Previously
  a single forged line in the process output could rewrite the address the UI loads, and any code inside
  the rootfs could write that output. Red-team testing confirmed four address forms were previously
  adopted and loaded verbatim: `javascript:`, `file:///root/home/.credentials.yaml` (reads your API key),
  `data:`, and `intent://`. Addresses now pass three checks before reaching the UI: http/https only,
  loopback host only, and the port must match. An allowlist rather than a denylist — which schemes can be
  loaded varies by system WebView version, so a denylist necessarily misses some.
- **Stricter log redaction.** Previously one overly short "secret" could redact an entire log down to
  `***`, destroying its usefulness.

### Fixed

- **Pages rebuild correctly after the system reclaims them**, instead of showing a blank screen.
- **The script version comparison was corrected**, so "does this upgrade need a re-extraction" is judged
  more accurately.

### Upgrade notes

- **Over-the-top install works** (fix22 → fix23 needs no uninstall).
- Quality baseline: unit tests **1027/1027**, script self-check **33/33**.

---

## 0.1.6-SNAPSHOT (fix22, historical verified build)

> **The same dialog no longer reappears after you copy the access link.** And opening the UI in an
> external browser no longer re-downloads the front-end files every time.

### Fixed

- **After a successful copy, the same dialog no longer comes back.** Previously you tapped "Copy link", the
  link went to the clipboard, and two seconds later the UI showed "Continue anyway / Copy link / Open in
  external browser" again — asking about the choice you had just made. Now, after a **successful** copy, it
  settles on a "Link copied" panel with four exits — retry / logs / stop / close. Switching back to the app
  from the browser no longer triggers another prompt.
- **Behaviour is unchanged when the copy failed or you cancelled the confirm dialog**: you still return to
  the gate dialog to decide again. Only "the link really made it into the clipboard" takes the new path.
- **Opening the UI in an external browser no longer re-downloads the front-end files every time.** dsh's
  own front-end server sends no cache headers, so roughly 2 MB of front-end assets used to be re-downloaded
  on every open. Those files now carry a long-lived cache marker, so the second open downloads essentially
  nothing.
- **Actual download volume after the fix: about 5 MB the first time, essentially nothing afterwards.** The
  earlier note claiming "545 KB for the first screen" was wrong — it only measured the main front-end bundle
  and missed 58 plugin bundles (they are not lazy-loaded; they are injected in one batch, about 4.4 MB after
  gzip). Both the plugin bundles and the front-end assets are now cached, so they re-download only on the
  first open or when the version changes.
- **dsh's hot-reload polling was stretched from every 0.5 s to every 5 s.** This only reduces constant
  background CPU; it does not change how fast the UI opens. It was not disabled outright — with it off,
  "install a plugin and it appears without refreshing" would silently stop working, which is worse than
  being a bit slower.

### Known limitations

- **Leaving and re-entering the UI still prompts once about an outdated WebView.** That "already copied"
  flag only lives in the current page and is not persisted — persisting it would mean this device never
  warns again, and that warning is your only chance to learn the embedded UI will break.
- **If a state replay happens while the dialog is open (for example pressing Home and coming back), the
  gate dialog is still re-shown once.**

### Upgrade notes

- **Over-the-top install is fine** (fix21 → fix22, no uninstall needed).
- **The scripts changed (11 → 12)**: after upgrading, **the first launch runs the preparation screen once** to
  re-extract the scripts (sub-second; rootfs and instance data untouched), and it will not repeat. If a
  launch with `--patch` exits before becoming ready, the script automatically retries once without `--patch`
  — an optimisation must never be the reason something fails to start.
- Quality baseline: unit tests **513/513**, script self-check **28/28**. The tests added this round
  specifically pin "the call site is really wired up", and five mutation verifications were run (revert the
  implementation to the old behaviour; the tests must go red).

---

## Historical builds (summarized)

The full text of each of these lives in the Chinese original [`RELEASES.md`](RELEASES.md). Only builds whose
upgrade notes matter to you today are called out in the notes column.

| Version | Headline | Notes |
|---|---|---|
| `0.1.6-SNAPSHOT` (fix21) | Corrected three things the previous build stated wrongly; warn automatically about the empty-directory trap (hit three times) | — |
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
