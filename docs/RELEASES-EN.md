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

- **Custom model providers now survive a restart.** The launcher used to rewrite the instance's config
  file on every startup — and that is exactly where dsh reads your custom model providers from, so a
  provider configured following the dsh docs was gone on the next launch. The launcher now maintains
  only its own two entries and leaves everything else untouched.

  `writeLauncherPatchOverlay` used to overwrite the whole file; it now merges: it claims `client-hmr`
  and `launcher-assets` by id, keeps the remaining entries and appends them after (dsh's patch
  semantics are "the later one wins"). On a real device `grep -c mygateway launcher.patch.yml` was 0
  after a restart before the fix and is always 1 after it. `R45LauncherPatchMergeTest` has 9 cases,
  one of which asserts the production call site actually calls the merge function — reverting to
  overwrite turns it red. Unit tests 1060/1060.

### Upgrade notes

- **Over-the-top install works** (fix21 → fix22 needs no uninstall).

---

## 0.1.6-SNAPSHOT-fix21
> **Risk warnings are no longer cut off when you increase the system font size.** The gate dialog
> body used to break off mid-sentence.

### Fixed

- **The gate dialog body is no longer clipped and can be scrolled.** At 1.3× system font size the
  dialog's body broke off mid-sentence, and what got cut was precisely "tapping 'Continue anyway' on
  this device will not restore these features" — the most important risk warning simply did not exist
  on a "small screen + large text" device. Height is now computed from the content's actual measured
  size, and any body length can be scrolled to the end.
- **The root cause was one layer deeper than the ledger recorded.** The layout already **had** a scroll
  container, but two things together defeated it: ① it used `0dp` + `weight`, and weight only
  distributes leftover space when the parent has a definite height, while the dialog root is
  `wrap_content`, so there was never any scroll budget; ② the height check compared the parent's
  measured height against the limit, and since the parent is also `wrap_content` that condition is
  almost always true, so long bodies were silently clipped.

### Upgrade notes

- **Over-the-top install works** (fix20 → fix21 needs no uninstall).

---

## 0.1.6-SNAPSHOT-fix20
> **A "failures must be visible" pass.** Several error paths used to swallow the failure silently, so
> you could never find out what happened.

### Fixed

- **Failures that used to be swallowed now surface.** Log rotation failing, the log write failing,
  instance deletion partially failing, the overlay write failing — each of these used to leave no trace
  at all. They now leave a visible record instead of a silent no-op.

### Upgrade notes

- **Over-the-top install works** (fix19 → fix20 needs no uninstall).

---

## 0.1.6-SNAPSHOT-fix19
> **A security hardening: nobody can steer your UI to another site any more.** Plus a batch of
> stability fixes.

### Fixed

- **The WebView can no longer be navigated to an arbitrary site.** A page could steer the in-app UI
  elsewhere. Navigation is now restricted to the expected origin
  (`127.0.0.1: expectedPort` or the page's own origin).
- **Stability fixes** to the launcher's session and instance handling.

### Upgrade notes

- **Over-the-top install works** (fix18 → fix19 needs no uninstall).

---

## 0.1.6-SNAPSHOT-fix18
> **The same dialog no longer reappears after you copy the access link.** And opening the UI in an
> external browser no longer re-downloads the front-end files every time.

### Fixed

- **After a successful copy, the same dialog no longer comes back.** Previously you tapped "Copy
  link", the link went to the clipboard, and two seconds later the UI showed "Continue anyway / Copy
  link / Open in external browser" again — asking about a choice you had just made. Now, after a
  **successful** copy, it stays dismissed.
- **Front-end assets carry a cache header**, so opening the UI in an external browser no longer
  re-downloads the same files on every page load.

### Upgrade notes

- **Over-the-top install works** (fix17 → fix18 needs no uninstall).

---

## 0.1.6-SNAPSHOT-fix17
> **Correcting three things the previous build stated wrongly**, and a real fix for the empty
> "choose workspace directory" dialog.

### Fixed

- **Correcting three things the previous build got wrong:**
  - **"The front-end bundle is not compressed" was wrong.** dsh's front-end bundle **is** gzip-compressed
    (level 1, only above 1 KB). The uncompressed size had been mistaken for the actual transfer size.
  - **"WebView being old explains the slowness" was wrong.** You were operating in an **external
    browser**, and that client does not use the system WebView at all. That point only explains "the
    in-app UI may crash on old devices" — unrelated to "the external browser is slow".
  - **"1.35 MB re-downloaded every time" was not the actual transfer size.** That was the uncompressed
    size. The conclusion "everything is re-sent on every refresh" still holds, but the number is much
    smaller.
- **The directory picker now starts somewhere that actually has content.** The dialog used to open
  empty, so you could not navigate anywhere. It now starts at the instance directory, where
  `workspace`, `home` and `node_modules` live.

### Upgrade notes

- **Over-the-top install works** (fix16 → fix17 needs no uninstall).

---

## Historical builds (summarized)

The full text of each of these lives in the Chinese original [`RELEASES.md`](RELEASES.md). Only builds whose
upgrade notes matter to you today are called out in the notes column.

| Version | Headline | Notes |
|---|---|---|
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
