[简体中文](RELEASES.md) | English

# Release notes (for users)

> One section per version, explaining **"what changes for you in this build"**. GitHub Release bodies
> are taken from here. Newest first.
>
> **There is exactly one source of truth for the version number**: `dshVersion` in `gradle.properties`
> at the repository root. The newest version number in this file must match it.

> How to write these: **one `##` per version → a one-sentence summary → category sections → upgrade
> notes**. Categories are Keep a Changelog's six (Added / Changed / Deprecated / Removed / Fixed /
> Security) plus `Known limitations` and `Upgrade notes`.
> Hard rules: `Fixed` appears only once per version (use list items for several fixes); no
> parentheses or technical nouns in headings; describe what the user experiences rather than which
> line changed; unsolved problems go under `Known limitations`, never hidden.

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

## 0.1.6-SNAPSHOT-fix22
> **The model provider you add no longer disappears.** Previously one restart wiped the provider you
> configured, with no warning at all.

### Fixed

- **Your custom provider survives a restart.** On every start the launcher rewrote a config file in the
  instance directory, and that file is exactly where dsh reads custom model providers from. It used to be
  **regenerated in full** — a provider you wrote in following dsh's documentation was wiped on the next
  start. Now the launcher maintains only its own two entries and leaves everything else untouched, so your
  configuration is no longer lost.

  `writeLauncherPatchOverlay` used to overwrite the whole file; it now merges — claiming `client-hmr` and
  `launcher-assets` by id and keeping everything else after them (dsh's patch semantics are "later wins").
  `R45LauncherPatchMergeTest` has 9 cases, one of which asserts the production call site really calls the
  merge function. Unit tests 1060/1060, script self-check 33/33.

  Verified on device (KKG-AN00 / Android 10): wrote a `mygateway` provider into `launcher.patch.yml`,
  force-stopped and relaunched — the entry was still there, and both of the launcher's own entries were
  too. All three coexist.

### Upgrade notes

- **Over-the-top install works** (fix21 → fix22 needs no uninstall).

---

## 0.1.6-SNAPSHOT-fix21
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

- **Over-the-top install works** (fix20 → fix21 needs no uninstall).

---

## 0.1.6-SNAPSHOT-fix20
> **A "failures must be visible" pass.** Several error paths used to swallow the failure silently, so you
> could never work out what went wrong.

### Fixed

- **A failed stop no longer claims it succeeded.** The stop signal's result used to be discarded, so the
  record was cleared even when the process was still alive. It is now cleared only once the process is
  confirmed dead.
- **Cleanup failures are no longer silent.** Leftovers that cannot be deleted are recorded with their
  location and remaining size, so you can tell they are still there.
- **Instance logs no longer cross wires.** `my app` / `my.app` / `my_app` — three different instances —
  used to share one log file, and deleting one also deleted another's log.
- **Deleting an instance can no longer be interleaved with a concurrent operation.**
- **Several critical writes are now atomic**, so a half-written config file cannot happen.

### Upgrade notes

- **Over-the-top install works** (fix19 → fix20 needs no uninstall).

---

## 0.1.6-SNAPSHOT-fix19
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

- **Over-the-top install works** (fix18 → fix19 needs no uninstall).

---

## 0.1.6-SNAPSHOT-fix18
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
| `0.1.6-SNAPSHOT` (fix17) | Warn on empty directories + correct three inaccurate explanations | — |
| `0.1.6-SNAPSHOT` (fix16) | API key exposure surface warning + diagnosable empty-directory typo | — |
| `0.1.6-SNAPSHOT` (fix15) | Ghost-instance protection + call-site contract tests | — |
| `0.1.6-SNAPSHOT` (fix14) | Comment corrections + reject `..` + auto-create custom directories | — |
| `0.1.6-SNAPSHOT` (fix13) | `HOME` points at the instance directory + customisable start directory | — |
| `0.1.6-SNAPSHOT` (fix12) | Open in browser + stable ports, so an external browser can stay authenticated | — |
| `0.1.6-SNAPSHOT` (fix11) | External browser could not pick a workspace — `HOME` now points at the instance workspace | — |
| `0.1.6-SNAPSHOT` (fix10) | Cross-implementation consistency tests drop the mirrored implementation; mutation verification added | — |
| `0.1.6-SNAPSHOT` (fix09) | Tests upgraded from "pinning implementation" to "contract + pipeline" | — |
| `0.1.6-SNAPSHOT` (fix08) | One definition of "is it installed"; failures are no longer swallowed | — |
| `0.1.6-SNAPSHOT` (fix07) | Startup retry and stop reliability | — |
| `0.1.6-SNAPSHOT` (fix06) | **Merges the former fix6~fix10**: new app identity, install progress, external-browser 401, gate-dialog dead ends | **Package name changes to `com.dsh.fcl.androidlauncher` with a dedicated key (not debuggable). Earlier `.debug` builds can coexist** |
| `0.1.6-SNAPSHOT` (fix05) | Install progress visible; external-browser 401 and dead-end buttons handled | — |
| `0.1.6-SNAPSHOT` (fix04) | — | Package names carry a `.debug` suffix with the public debug key |
| `0.1.6-SNAPSHOT` (fix03) | — | — |
| `0.1.6-SNAPSHOT` (fix02) | — | — |
| `0.1.6-SNAPSHOT` (fix01) | — | — |
| `0.1.6-SNAPSHOT` | Base 0.1.6 build | — |
| `0.1.5-SNAPSHOT` | Task area, setup page and right panel polished | — |
| `0.1.4-SNAPSHOT` | Instance management and version download reworked | — |
| `0.1.3-SNAPSHOT` | Slimmed rootfs (314 MB → 149 MB) | — |
| `0.1.2-SNAPSHOT` | Setup page and a task area; fixed duplicate installs | — |
| `0.1.1-SNAPSHOT` | **The APK bundles the full rootfs** — no manual setup | 1.4 GB free space for first extraction |
| `0.1.0` | First installable version | First release; rootfs had to be prepared manually |
