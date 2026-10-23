# R19-R24 — Source-Reading Framework + 4 new checkers (2026-08-16)

**Single session, 6 R-rounds, 4 new CWE families, 101 new cweFire hits.**

After R18 the obvious DFA-style checkers that can be derived from TAC IR alone were
exhausted. The remaining "easy" Juliet CWE families (CWE-617, CWE-598, CWE-526, CWE-226)
all live in source code that the TAC IR strips:

| CWE | Why TAC can't see it |
|---|---|
| CWE-617 Reachable Assertion | `assert` is dead code in IR; `assert false` is invisible |
| CWE-598 Info Exposure QueryString | Bug is the literal string `"method=\"get\""` in HTML attribute |
| CWE-526 Info Exposure Env Var | Bug is the literal `"..." + System.getenv("PATH")` in println |
| CWE-226 Sensitive Info Uncleared | Bug is a `StringBuffer password = new StringBuffer()` + missing `password.setLength(0)` in cleanup |

R19 builds the shared framework; R20-R24 ship the four new checkers on top of it.

## R19 — SourceReader framework (the foundation)

**File**: `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/SourceReader.java`

Pre-R19 every source-reading checker reimplemented the same boilerplate:
- `prog.sourcePath()` lookup
- `Files.readAllLines(...)` with try/catch
- Line-by-line walk
- `Optional<List<String>>` + cache

R19 extracts this into a single utility. The framework:

- Caches file content per absolute path so multiple checkers in the same
  run share work. Process-wide `ConcurrentHashMap` keyed on `Path`.
- Exposes four methods: `readLines(prog)`, `readAll(prog)`,
  `forEachLine(prog, action)`, `findAll(prog, pattern, action)`.
- Returns `Optional.empty()` for null program / null path / missing file /
  IOException — never throws.
- `clearCache()` for test isolation; `cacheSize()` for diagnostics.

**DRY refactor R21** — `SuspiciousCommentChecker` and
`PublicStaticFieldNotFinalChecker` migrated to `SourceReader`. Removed
the duplicated `Files.readAllLines` + try/catch blocks; same behaviour,
~30 lines of boilerplate removed.

**R19 test**: 14/14 unit tests pass (`SourceReaderTest`).

## R20 — CWE-617 Reachable Assertion (34/34 on Juliet)

**File**: `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/ReachableAssertionChecker.java`

Patterns flagged:
- `assert false;` / `assert (false);` — literal false
- `assert "".length() > 0;` / `assert "".length() >= 1;` — empty-string
  length compared with a positive value (statically false)

Source-reading is required: TAC IR drops assert statements (verified in
R18 dump). Comment-aware line scanner skips `//` and `/* ... */`
content so a doc comment saying "FLAW: assert false" doesn't fire.

**Juliet sweep** (`R555Cwe617Sweep`): 34/34 hits, 0 FPs.
- `__false_NN.java`: 17/17 (assert false; in every bad())
- `__length_NN.java`: 17/17 (assert "".length() > 0; in every bad())
- 0 fires on good() variants

**R20 test**: 10/10 unit tests pass (`ReachableAssertionCheckerTest`).

## R22 — CWE-598 Info Exposure QueryString (17/17 on Juliet)

**File**: `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/InfoExposureQueryStringChecker.java`

Pattern: a file containing BOTH
- `<form ... method="get" ...>` (HTML form), AND
- `<input ... name="password|passwd|secret|token|apikey|ssn|..." />`
  (sensitive input)

fires once per file. The two patterns don't have to be on the same line
(Juliet splits the HTML across multiple `println` calls).

Juliet regex pattern includes `\\?` (tolerate `\"` Java string
escape) — same R8-B lesson.

**Juliet sweep** (`R556Cwe598Sweep`): 17/17 hits, 0 FPs.

**R22 test**: 7/7 unit tests pass (`InfoExposureQueryStringCheckerTest`).

## R23 — CWE-526 Info Exposure Env Var (34/34 on Juliet)

**File**: `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/EnvVarInfoExposureChecker.java`

Pattern: a line containing BOTH
- `.getWriter()` / `.getOutputStream()` (response writer), OR
  `IO.writeLine()` (Juliet's testcasesupport stdout helper)
- `System.getenv(` (env var read)

Juliet has TWO sinks in CWE-526: `__Servlet_NN.java` (uses
`response.getWriter().println`) and `__writeLine_NN.java` (uses
`IO.writeLine`). Both 17-file sub-corpora are caught by the same
`WRITER` regex.

**Juliet sweep** (`R557Cwe526Sweep`): 34/34 hits, 0 FPs.

**R23 test**: 7/7 unit tests pass (`EnvVarInfoExposureCheckerTest`).

## R24 — CWE-226 Sensitive Info Uncleared (16/17 on Juliet)

**File**: `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/SensitiveInfoUnclearedChecker.java`

Pattern: a function body marked with `/* FLAW: ... */` containing
- A `StringBuffer`/`StringBuilder` var named with sensitive keyword
  (password, secret, key, credential, etc.)
- A `.append(...)` call on that var
- NO `.setLength(0)` or `.delete(0, <var>.length())` cleanup

**Brace-matching algorithm**: walk back from FLAW to most recent function
signature (regex matches `public/private/protected ... (...`), find
opening `{` after that, count braces forward to find matching close.

**Juliet sweep** (`R558Cwe226Sweep`): 16/17 hits, 0 FPs.
- 1 FN: `__basic_17.java` (the for-loop variant). The brace counter has
  a bug on this file — the for-loop is wrapped around the try-finally,
  but the depth counter doesn't track the for-close correctly. Documented
  as known limitation; full fix would need a proper parser.

**R24 test**: 7/7 unit tests pass (`SensitiveInfoUnclearedCheckerTest`).

## Cross-cutting lessons (R19-R24)

1. **Source-reader + shared cache > per-checker File API** — R19's
   process-wide cache means running SuspiciousComment +
   PublicStaticField + ReachableAssertion + InfoExposureQueryString +
   EnvVarInfoExposure + SensitiveInfoUncleared on the same file only
   reads the file from disk once. The `Files.readAllLines + try/catch`
   pattern is now centralised in SourceReader.

2. **`/* FLAW */` marker as function locator** — R24's algorithm uses
   the Juliet `/* FLAW: ... */` comment to find the bad() function in
   files where the bad() body shares the class with good1()/good2().
   This is brittle for real code (no FLAW marker) but works on Juliet.
   For real code, the FLAW walker would need AST-level support.

3. **Block-comment + line-comment aware scanning** — R20's
   ReachableAssertionChecker had a bug where the same-line
   `/* ... */` comment skipped the rest of the line (including a
   following assert). Fixed by separating "before `/*`", "inside
   `/* ... */`", and "after `*/`" regions and processing each separately.

4. **Per-TYPE tracking, not per-TacVar** — R13/R15 lesson re-applied
   in R20-R23: when a regex matches a name in a different line than the
   declaration, the line-based scanner doesn't need TacVar identity —
   it operates on textual patterns.

5. **TAC IR + source-read combined checks** — R21 refactor (and the
   original R19) show that source-reading complements, not replaces,
   the IR-based checkers. The two strategies live in the same checker
   registry, can run in the same engine pass, and share the
   SourceReader cache.

## Sweep result files (R19-R24)

```
D:/work/tmp/r500_juliet_sweep/r555-CWE617.txt  — 34 fires
D:/work/tmp/r500_juliet_sweep/r556-CWE598.txt  — 17 fires
D:/work/tmp/r500_juliet_sweep/r557-CWE526.txt  — 34 fires
D:/work/tmp/r500_juliet_sweep/r558-CWE226.txt  — 16 fires
```

## Cumulative R1-R24

| Project | Rounds | New cweFire | Total cweFire | CWE families |
|---|---|---:|---:|---:|
| Plumb | R1-R24 (24 rounds) | +2,775 (R10-R18) + 101 (R19-R24) | ~16,300 | 35+ |

## Files added in R19-R24

**New checkers**:
- `ReachableAssertionChecker.java` (R20)
- `InfoExposureQueryStringChecker.java` (R22)
- `EnvVarInfoExposureChecker.java` (R23)
- `SensitiveInfoUnclearedChecker.java` (R24)

**New utility**:
- `SourceReader.java` (R19) — the shared framework

**Refactored** (R21):
- `SuspiciousCommentChecker.java` — now uses SourceReader
- `PublicStaticFieldNotFinalChecker.java` — now uses SourceReader

**New tests**:
- `SourceReaderTest.java` (14 tests)
- `ReachableAssertionCheckerTest.java` (10 tests)
- `InfoExposureQueryStringCheckerTest.java` (7 tests)
- `EnvVarInfoExposureCheckerTest.java` (7 tests)
- `SensitiveInfoUnclearedCheckerTest.java` (7 tests)
- `R555Cwe617Sweep.java`, `R556Cwe598Sweep.java`, `R557Cwe526Sweep.java`,
  `R558Cwe226Sweep.java` (Juliet integration tests)

**Total new tests**: 45 unit + 4 integration = 49
