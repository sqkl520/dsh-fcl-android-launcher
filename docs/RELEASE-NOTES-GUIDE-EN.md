[简体中文](RELEASE-NOTES-GUIDE.md) | English

# How to write release notes

> These rules apply to **every product in this project**, not to one particular `RELEASES.md`.
> Read this before writing any user-facing release notes.

## The one-line principle

**Release notes are written for users, not for yourself.**

Users open the Release page with one question: **"What does this version do for me, and should I
install it?"** Anything that does not help them answer that question does not belong here.

---

## 1. The test: should this sentence exist at all?

After writing each sentence, ask three questions:

| Question | If it fails |
|----------|-------------|
| Can a user understand it? (Do not assume they know code or our internal numbering.) | Rewrite it in everyday words |
| Does a user care? (Does it help them decide whether to install, or how to use it?) | Delete it, or move it to internal docs |
| Is it true? (Have the numbers and claims actually been measured?) | Verify it, or do not write it |

---

## 2. Structure: four fixed levels, no improvisation

```markdown
## Version (status)

> One sentence on the most important change in this version.

### Added / Changed / Fixed / Security / Known limitations
### Upgrade notes
```

### 2.1 The version heading

```markdown
## 1.4.0
## 1.4.0 (fix26, current verified build)
## 1.3.2 (historical verified build)
```

The parentheses hold **status only** (current / historical / superseded) — no explanations, no
technical descriptions.

### 2.2 The one-sentence summary (mandatory)

A `>` blockquote right after the heading, **one or two sentences**, stating the most important change
in this version __for the user__.

```markdown
> **The model provider you add no longer disappears.** Previously one restart wiped it, with no warning.
```

**Counter-example** (a real incident):

```markdown
> **The twentieth round of 0.1.6** — the result of the third critique round. **Most important: don't
> pick the instance's home directory as your workspace.**
```

The problem: "the twentieth round" is internal numbering the user neither knows nor cares about;
"the third critique round" is internal process — talking to ourselves.

### 2.3 Category sections

Use [Keep a Changelog](https://keepachangelog.com/)'s six categories, **in this order, omitting the
ones that do not apply**:

| Category | When to use it |
|----------|----------------|
| `### Added` | A new feature users can see |
| `### Changed` | Existing behaviour changed (including "correcting something the previous version got wrong") |
| `### Deprecated` | Something about to be removed |
| `### Removed` | Something removed in this version |
| `### Fixed` | A defect that got fixed |
| `### Security` | Credentials, permissions, privilege |

Plus two that Keep a Changelog does not define but user-facing release notes need:

| Category | When to use it |
|----------|----------------|
| `### Known limitations` | Problems this version **explicitly did not fix**. Better stated than hidden |
| `### Upgrade notes` | Uninstall needed? Re-download? How to verify after installing |

### 2.4 Upgrade notes (mandatory)

Cover at least three things:

- Can it be installed **over the top**, or must the user uninstall first?
- Will it trigger a **re-extraction / re-download / data migration**, and roughly how long?
- **How to confirm the right build is installed** (e.g. what the About screen should show)

---

## 3. Eight hard rules

### Rule 1: `### Fixed` may appear only once per version

When several things were fixed, use `-` list items:

```markdown
### Fixed

- **Problem A**: …
- **Problem B**: …
```

**Do not write**:

```markdown
### Fixed (problem A)
### Fixed (problem B)      ← forbidden
```

### Rule 2: No parenthetical explanations, no technical nouns in headings

```markdown
### Fixed                              ✅
### Fixed (launcher.patch.yml overwrite) ❌
### About testing (being honest here)    ❌
### One unsolved thing (important)       ❌
### Known limitations (not solved here)  ❌
```

**The heading is just the category name.** Explanations go in the item body.
Parentheses saying "important", "please read", "to be honest", "a word of truth" are **talking to a
reviewer**, not to a user.

### Rule 3: Describe what the user experiences, not which line you changed

| ❌ Internal view | ✅ User view |
|---|---|
| Reworked the lifetime of the gateCopied flag | The same dialog no longer reappears after a successful copy |
| Moved `repair()` to an IO thread | The UI no longer stutters |
| Switched from the SIGQUIT return value to `ProcessUtil.isAlive` | A failed stop no longer reports "stopped" |

Technical detail (root-cause analysis, mutation verification, test counts) belongs in **internal
records** (BUGLEDGER / CHANGELOG / commit messages), not in user release notes.

### Rule 4: No internal numbering, no internal process

```markdown
> **The twentieth round of 0.1.6** — result of the third critique round.   ❌
> **This version fixes the empty-dialog problem.**                          ✅
```

"The Nth round", "critique", "rework", "six-way parallel review", "shape pin" — these are team
vocabulary. Users cannot parse them and do not need to.

### Rule 5: No forward references

A version's notes must **never mention a later version**:

```markdown
(notes written for fix19)
> That number was inaccurate — fix21 corrected it and fix22 re-measured.   ❌
```

fix22 did not exist when this was written. The reader sees scrambled time.

**Instead**: say "not solved in this version; addressed in a later release", or simply state this
version's facts. **If a past version's notes turn out to be wrong, go back and fix that version** —
do not explain the old one in a new one.

### Rule 6: No "install xxx.apk (the About screen should show xxx)"

The Release page is itself distributing that APK; the filename and version number already appear
three times on the page. The sentence is pure noise — and copy-paste tends to carry a stale version
number into it.

### Rule 7: State what was NOT fixed — without inflating what was

```markdown
### Known limitations

- **Problem A: not fixed.** The reason is …
- **Problem B: fixed for the first case; the second case (spinner never stops) is not fixed.**
```

**Never** put "these two symptoms are actually two different diseases" under `### Fixed` in a way
that makes readers think both were fixed.

### Rule 8: Every number must be measured

Any number in release notes (size, duration, test count, download volume) must be **measured by you**.

A real incident: notes claimed "545 KB for the first screen" when the real figure was about 5 MB — the
measurement covered only the main bundle and missed 58 plugin bundles. A wrong number is worse than no
number, because readers make decisions from it.

---

## 4. Self-check list

Go through this before publishing:

- [ ] Every version section has a **one-sentence summary**, and it contains no internal numbering
- [ ] `### Fixed` appears **only once** in each section
- [ ] No `###` heading contains parentheses
- [ ] No **forward references** (mentioning a later version)
- [ ] No "install xxx.apk (About screen should show …)" redundancy
- [ ] Unsolved problems are under `### Known limitations`, not hidden under `### Fixed`
- [ ] Every number has been measured, or has been deleted
- [ ] No "important", "please read", "to be honest" asides anywhere on the page

Automation suggestions:

```bash
# Duplicate headings within one version section
awk '/^## /{delete seen} /^### /{if($0 in seen) print "DUP: "$0; seen[$0]=1}' RELEASES.md

# Parentheses in headings
grep -n "^### .*(" RELEASES.md

# Forward references (a fixN section mentioning fixM where M > N)
awk '/^## .*fix([0-9]+)/{match($0,/fix([0-9]+)/,a); cur=a[1]}
     {line=$0; while(match(line,/fix([0-9]+)/,b)){ if(cur>0 && b[1]>cur) print NR": fix"cur" -> fix"b[1]; line=substr(line,RSTART+RLENGTH)}}' RELEASES.md
```

---

## 5. Common misconceptions

| Misconception | Why it is wrong |
|---------------|-----------------|
| "More detail is always better" | Users only want to know whether to install. Detail that buries the conclusion is the same as no notes |
| "Internal numbering shows how much work went in" | Users do not care how many rounds you did; they care whether their problem is fixed |
| "A parenthetical makes the heading clearer" | It makes headings long, messy, and un-scannable. A category name should just be a category name |
| "Root-cause analysis in release notes looks professional" | Professionalism comes from users being able to judge for themselves, not from jargon. Root causes go in internal docs |
| "If an old version's notes were wrong, correct them in the new version" | Readers get confused about which version told the truth. **Go back and edit the old one** |
| "Don't mention what wasn't fixed — it looks bad" | Users who hit the problem find no answer and trust you less |

---

## 6. Division of labour with internal docs

| Document | Reader | Contents |
|----------|--------|----------|
| `RELEASES.md` (governed by this guide) | **Users** | What this version does for me, whether to install, how to verify |
| CHANGELOG | Developers | What each commit changed (may be frozen, but do not pretend to maintain it) |
| BUGLEDGER | Maintainers | Symptom, root cause, fix, verification evidence |
| Commit messages | Future maintainers | Why the change was made |

**Each piece of content lives in exactly one place.** Do not copy root-cause analysis into release
notes, and do not copy user-facing wording into internal docs.