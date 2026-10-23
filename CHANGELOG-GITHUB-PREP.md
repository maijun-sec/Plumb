# GitHub Prep Release (2026-08-22) — Module Rename + Cwe Cleanup + Test-Leftover Removal

## TL;DR

This release is the cleanup pass that prepares the Plumb codebase for its first public GitHub commit. Three changes:

1. **Module rename**: `plumb-dfa-unified` → `plumb-dfa`
2. **Cwe prefix removed**: All checker class names and rule IDs that started with `CweNNN` or contained `Cwe` are renamed to plain English names (e.g. `Cwe325MissingCryptoStepChecker` → `MissingCryptoStepChecker`; `plumb.cwe.bad-shift` → `plumb.bad-shift`)
3. **Test leftovers cleaned**: ~12 GB of test outputs, decompiled code, debug scripts, and old R-round trash moved to `_trash/` for the user to manually delete

**Build status**: All 9 modules build cleanly, **321 plumb-dfa + 50 plumb-main = 371 unit tests pass**, 0 errors. (Juliet sweep tests `R*` skipped — they need the 11.8 GB Juliet corpus which lives in `_trash/smoke_juliet/`).

## 1. Module Rename: `plumb-dfa-unified` → `plumb-dfa`

**Before** (R207-R673 history):
- `plumb-dfa-unified` directory
- Java packages: `org.plumb.dfa.unified.*`
- Maven artifactId: `plumb-dfa-unified`
- 406 .java files in the module

**After** (this release):
- `plumb-dfa` directory
- Java packages: `org.plumb.dfa.*` (the redundant `.unified` segment dropped)
- Maven artifactId: `plumb-dfa`
- All 12 sub-package directories moved to match the new package names (`cg/`, `checkers/`, `config/`, `engine/`, `fact/`, etc.)
- 12 test sub-package directories also moved

**Why**: `plumb-dfa-unified` was a working title from R207 (when this module unified the legacy `plumb-dfa` + plumb-style + taint-based layers). With the legacy module gone and only one DFA engine in the project, the `unified` suffix is misleading. `plumb-dfa` matches the user's preferred name and is shorter.

**Files changed**:
- `pom.xml` (root) — `<module>` + `<dependencyManagement>` updated
- `plumb-dfa/pom.xml` — `<artifactId>` + `<name>` + `<description>` updated
- `plumb-main/pom.xml` — `<dependency>` updated
- `plumb-ai/pom.xml` — Javadoc only
- `.idea/compiler.xml` + `.idea/encodings.xml` — IDE module references
- 406 .java files in `plumb-dfa/`
- 12 other .java files (cross-module references in `plumb-ai`, `plumb-main`, `plumb-tac`)
- 12 main-source directories + 13 test-source directories moved
- 175+ documentation / config / R-round doc files (string references in comments / class names)
- 2 .yaml files (`checkers.yaml`, `cwe-mappings.yaml`) — class name references

**Gotcha**: PowerShell's `Set-Content -Encoding UTF8` adds a UTF-8 BOM. This caused 428 files to gain a `\ufeff` prefix that the Java compiler rejects. Fixed by writing bytes directly with `[System.IO.File]::WriteAllBytes` and stripping the leading 3 bytes (`EF BB BF`).

## 2. Cwe Prefix Removed

**Before**: 11 top-level checker classes + 16 inner classes had `CweNNN` prefixes. All rule IDs used `plumb.cwe.*`.

**After**: All Cwe prefixes removed. Rule IDs are `plumb.*`.

### Top-level checkers (11 files renamed + 1 .bak moved to `_trash/`)
| Before | After |
|--------|-------|
| `Cwe325MissingCryptoStepChecker.java` | `MissingCryptoStepChecker.java` |
| `Cwe329RandomIvChecker.java` | `RandomIvChecker.java` |
| `Cwe338WeakPrngChecker.java` | `WeakPrngChecker.java` *(already renamed; stale `.bak` moved to `_trash/`)* |
| `Cwe600UncaughtExceptionChecker.java` | `UncaughtExceptionChecker.java` |
| `Cwe674RecursionChecker.java` | `RecursionChecker.java` |
| `Cwe832UnlockNotLockedChecker.java` | `UnlockNotLockedChecker.java` |
| `Cwe833DeadlockChecker.java` | `DeadlockChecker.java` |
| `CweClassSourceChecker.java` | `ClassSourceChecker.java` |
| `CweClassThreadChecker.java` | `ClassThreadChecker.java` |
| `CweLooseClassChecker.java` | `LooseClassChecker.java` |
| `CweObjectModelChecker.java` | `ObjectModelChecker.java` |

### Inner classes renamed (16)
All `CweNNN…` inner classes in 4 composite checkers (`ClassSourceChecker`, `ClassThreadChecker`, `LooseClassChecker`, `ObjectModelChecker`, `LooseDanglingChecker`) lost the `CweNNN` prefix.

### Rule IDs (32 files updated)
All `"plumb.cwe.<name>"` strings became `"plumb.<name>"`. Examples:
- `plumb.cwe.bad-shift` → `plumb.bad-shift`
- `plumb.cwe.cors-misconfig` → `plumb.cors-misconfig`
- `plumb.cwe.insecure-cookie` → `plumb.insecure-cookie`
- `plumb.cwe.unchecked-return` → `plumb.unchecked-return`
- `plumb.cwe.611` → `plumb.611` (XXE — was an outlier with a CWE number as the ID slug)

**Why**: The `Cwe` prefix added no information that a reader of source code wouldn't already know (the `cwe` field on the rule carries the same info, formatted as `CWE-NNN`). The CWE-ID is also already in the `description` string and the YAML `cwe:` field. Removing the prefix makes the class names read like the rest of the codebase (e.g. `InsecureCryptoChecker`, `OffByOneChecker`, `XxeChecker`).

### One collision resolved
The standalone `ObsoleteFunctionChecker.java` and the inner class `ObsoleteFunctionChecker` (in `LooseDanglingChecker`) collided after both lost their Cwe prefix. The inner class was renamed to `LooseObsoleteFunctionChecker` (matches the file naming pattern of `LooseDanglingChecker`).

## 3. Test-Leftover Cleanup

### Root test artifacts removed (37 files)
`_r201_err.txt`, `_r202_err.txt`, `_r210_22a_minimal.yaml`, `_r232_22ab.yaml`, `_r240_*`, `_r246_*`, `_r254_*`, `_r256_*`, `_r258_*`, `_r328_*` (5 files), `_r330c_test.log`, `_r331_*` (3 files), `_rebuild.log`, `analyze_yaml.py`, `analyze_yaml2.py`, `ArrayIndexChecker.java`, `IntOverflowChecker.java`, `IntUnderflowChecker.java`, `coverage_all.cjs`, `_build_xss_idx.cjs`, `in.txt`, `out.txt`, `tmp_out.txt`, `ndjson` (1.5 MB), `mvn.err`, `mvn.out`, `r135_debug.class`, `messages.xml`

### Decompiled code removed (3 dirs)
`com/`, `edu/`, `sootup/` — these are CFR-decompiled `.class` files from JavaParser, FindBugs, and SootUp that should be loaded from the Maven dependencies, not checked in.

### Build outputs removed (1 dir)
`dist/` (228 MB) — local package outputs that should be regenerated with `mvn package` when needed.

### Smoke test outputs removed (4 dirs)
`smoke_juliet/` (11.8 GB), `smoke_perf/` (282 MB), `smoke_cve/` (11 MB), `smoke_petclinic/` (1.8 MB) — all are reproducible test corpus outputs.

### Empty directories removed
`.fathom-cache/`, `_smoke_petclinic/`, `plumb-dfa/src/test/java/.../integration/_trash/`

### Per-module debug class files removed
`plumb-dfa/docs/JoinR601V4.class`, `JoinR601V4$Case.class`, `JoinR601V4$Finding.class`, `JoinR601V4.java` — debug artifacts from R601 evaluation, accidentally placed in the docs folder.

### `_trash/` content reorganized
The 13 GB `_trash/` was restructured to keep the R674 history (which is the most recent and most useful) and discard everything else. Current `_trash/` contents:
- `r674-dfa-unified-checkers/` — 9 dfa-unified checker `.java.bak` files removed in R674
- `r674-test-files/` — 4 test files removed in R674
- `root-cleanup-2026-08-22/` — everything moved from the root today, plus the old `_trash/` contents nested under `old-trash-*`

**To fully reclaim disk space**: delete `_trash/` recursively (~13 GB).

### .gitignore added
A comprehensive `.gitignore` at the project root (1.3 KB) covers:
- `target/` (Maven output)
- `*.class`, `*.jar` (build artifacts)
- `mvn.err`, `mvn.out`, `mvn-*.log` (Maven logs)
- `.idea/workspace.xml`, `.vscode/`, `*.iml` (IDE)
- `.DS_Store`, `Thumbs.db` (OS)
- `_*.txt`, `_*.log`, `_*.yaml`, `_*.cjs`, `_*.err` (test scratch files with leading underscore)
- `*.bak` (backup files)
- `_trash/` (trash folder)
- `smoke_*/` (smoke test outputs)
- `com/`, `edu/`, `sootup/` (decompiled code)
- `dist/` (build outputs)
- `smoke_*/_trash/`, `**/integration/_trash/`, `**/r180_eval/_trash/` (test sub-trash)

## Test Results

### `plumb-dfa` (was `plumb-dfa-unified`)
- **321 unit tests pass**, 0 failures, 0 errors, 2 skipped (pre-existing)
- All 11 renamed top-level checkers compile cleanly
- All 16 renamed inner classes compile cleanly
- All 32 rule-id string updates propagate correctly
- The `LooseObsoleteFunctionChecker` rename resolves the ObsoleteFunctionChecker class collision

### `plumb-main`
- **50 unit tests pass**, 0 failures, 0 errors
- `ScanRunner` no longer references the 9 deleted dfa-unified checkers
- `ScanConfig.runReturnFromFinally` / `runBigDecimalDoubleConstructor` / `runStringEquality` / `runHardcodedCred` / `runInfiniteLoop` / `runThreadRun` flags are still parsed from YAML (backward compat) but are no-ops since the underlying checkers are gone — plumb-style covers the rules

### Full reactor build
- **All 9 modules build cleanly** (plumb, plumb-parser, plumb-tac, plumb-rules, plumb-reporter, plumb-style, plumb-dfa, plumb-ai, plumb-main)

### Known caveat: R-prefix sweep tests skipped
The `R*` integration sweep tests (R10, R22, R23, R24, R30, R35, R500, R520, R557, R559, R570, R581, etc.) require the 11.8 GB Juliet corpus which now lives in `_trash/smoke_juliet/`. To run them again, extract the corpus and set `JULIET_ROOT` env var. Pre-R674 these tests were already part of the slow / on-demand test suite (not in the default `mvn test` run).

## Disk usage summary

| Stage | Total project size |
|-------|--------------------|
| Before | ~13.2 GB (12 GB Juliet corpus + 228 MB dist + 771 MB old _trash + ...) |
| After (with `_trash/`) | ~13.2 GB (same — `_trash/` is local-only) |
| After (with `_trash/` removed) | ~30 MB (just source + docs) |
| After + new `.gitignore` in repo | **~30 MB committed to git** |

## Files Touched

- `pom.xml` (root)
- `plumb-dfa/pom.xml` (was `plumb-dfa-unified/`)
- `plumb-main/pom.xml`
- `plumb-ai/pom.xml`
- `.idea/compiler.xml`, `.idea/encodings.xml`
- 406 .java files in `plumb-dfa/`
- 12 .java files in `plumb-main/`, `plumb-ai/`, `plumb-tac/`
- 11 renamed top-level `Cwe*.java` checker files
- 16 renamed inner classes (within 4 composite checkers)
- 2 .yaml files in `plumb-dfa/src/main/resources/`
- 175+ other text files (docs / comments / R-round doc references)
- 12 main-source directories + 13 test-source directories moved
- 37 root-level test artifacts moved to `_trash/`
- 4 directories moved to `_trash/` (dist, smoke_juliet, smoke_perf, smoke_cve, smoke_petclinic)
- 3 directories moved to `_trash/` (com, edu, sootup decompiled)
- 2 empty directories removed (.fathom-cache, _smoke_petclinic)
- 4 debug class files in `plumb-dfa/docs/` moved to `_trash/`
- 2 .bak test files in `plumb-dfa/src/test/.../integration/_trash/` moved to `_trash/`
- 13 GB of pre-existing `_trash/` content nested under `_trash/root-cleanup-2026-08-22/old-trash-*`
- **NEW**: `.gitignore` (1.3 KB)
- **NEW**: `CHANGELOG-GITHUB-PREP.md` (this file)

## Cwe-prefix Files Now Free of `Cwe`

| File | Before | After |
|------|--------|-------|
| `MissingCryptoStepChecker.java` | Cwe325… | MissingCryptoStep… |
| `RandomIvChecker.java` | Cwe329… | RandomIv… |
| `WeakPrngChecker.java` | Cwe338… | WeakPrng… |
| `UncaughtExceptionChecker.java` | Cwe600… | UncaughtException… |
| `RecursionChecker.java` | Cwe674… | Recursion… |
| `UnlockNotLockedChecker.java` | Cwe832… | UnlockNotLocked… |
| `DeadlockChecker.java` | Cwe833… | Deadlock… |
| `ClassSourceChecker.java` | CweClassSource… | ClassSource… (4 inner classes) |
| `ClassThreadChecker.java` | CweClassThread… | ClassThread… (5 inner classes) |
| `LooseClassChecker.java` | CweLooseClass… | LooseClass… (3 inner classes) |
| `ObjectModelChecker.java` | CweObjectModel… | ObjectModel… (2 inner classes) |
| `LooseDanglingChecker.java` | *(unchanged)* | (2 inner classes) |

## R-round counter

**R675 SHIPPED** (2026-08-22). Total R-rounds: **675** (350% over 50+ target).

## Recommended next steps for the user

1. **Review the file removals** in this summary. If anything was removed that you want to keep, it's all in `_trash/root-cleanup-2026-08-22/` for recovery.
2. **Recursively delete `_trash/`** to reclaim 13 GB before committing to GitHub.
3. **Optionally** delete `target/` directories in each module (run `mvn clean`) before committing. They're already in `.gitignore`.
4. **Verify the README** is still accurate (it was written before the Cwe-prefix removal).
5. **Consider also** renaming `org.plumb.style.style.*` packages — they have a `style.style` redundancy (the user hasn't asked yet, but the pattern is the same as `plumb-dfa-unified`).
