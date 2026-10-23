# R677 — Comment cleanup: 11 lighter files (~111 R-refs removed)

**Date:** 2026-08-22
**Status:** ✅ R677 SHIPPED
**R-rounds cumulative:** 1–677
**Constraint:** Same as R673 — "必须是注释理解，不能使用批量脚本来处理注释" (must use comment understanding, no batch scripts).
**User correction:** Same as R673.1+ — "不是删除注释，是处理注释" (process, not delete).

---

## TL;DR

R677 continues the comment cleanup with the next 11 lighter files. All R-round process markers removed with substantive source-code-related content preserved. ~111 R-refs removed from 11 files. All 321 plumb-dfa unit + 50 plumb-main unit tests pass.

| File | R-refs before→after | Type |
|---|---|---|
| ClassSourceChecker.java | 15 → 0 | composite (4 inner) |
| ClassThreadChecker.java | 15 → 0 | composite (5 inner) |
| OrmLostUpdateDfaChecker.java | 10 → 0 | aliasing + FunctionSummary |
| LooseClassChecker.java | 10 → 0 | composite (3 inner) |
| FunctionSummaryCache.java | 10 → 0 | engine summary |
| OffByOneChecker.java | 9 → 0 | DFA check |
| PathSensitiveCheckerBase.java | 9 → 0 | taint base class |
| FindingReporter.java | 9 → 0 | engine finding |
| ObjectModelChecker.java | 8 → 0 | composite (2 inner) |
| PoorCodeQualityChecker.java | 8 → 0 | DFA check |
| LooseDanglingChecker.java | 8 → 0 | composite (2 inner) |
| **Total** | **111 → 0** | |

---

## R677-A — R673 doc update

Updated the R673 doc to:
- Reflect the consolidated status: R673 + R673.1-R673.11 cleaned 11 heavy files, ~500 R-refs removed
- Add the "Follow-up chain" section linking to R674 / R675 / R676 / R677
- Update the "What remains" section to reflect the post-R675 file rename (`Cwe*` → no-prefix) and the R677 next-batch targets

The R673 doc is now the master "comment cleanup policy" reference; subsequent rounds (R673.1-R673.11) and follow-up rounds (R674-R677) are linked but the detailed history lives in this doc.

---

## R677-B — File-by-file summary

### File 1: ClassSourceChecker.java (15 → 0 R-refs)

Composite source-reading checker for CWE-382, CWE-526, CWE-570, CWE-571. Contains 4 inner classes (SystemExitChecker, EnvVarChecker, AlwaysFalseChecker, AlwaysTrueChecker).

**Key wins**:
- Dropped "R668" prefix from file header + each inner class Javadoc.
- Dropped the audit-finding F-2/F-3 reference from the file header but kept the substantive explanation of WHY both gates were removed (Juliet filename + bad() method anchor).
- Dropped "R668 file-wide (was ... filename gate)" from each inner class (it was repetitive process info; the substantive content — what each checker fires on — was preserved).
- Dropped "R634" from the CWE-570 pattern list (X == X + N / X != X + N) — the pattern itself is what matters, not when it was added.
- Dropped "R629" from the public top-level aggregator Javadoc; replaced with substantive description of why the composite exists.

### File 2: ClassThreadChecker.java (15 → 0 R-refs)

Composite source-reading checker for CWE-572, CWE-579, CWE-585, CWE-609, CWE-835. Contains 5 inner classes (ThreadRunChecker, NonSerializableInSessionChecker, EmptySyncBlockChecker, DoubleCheckedLockingChecker, InfiniteLoopChecker).

**Key wins**:
- Same audit-remediation pattern as ClassSourceChecker.
- Kept the CWE descriptions intact (each inner class's docstring still says what pattern it fires on).
- Cleaned up the orphan comment "DeadlockChecker already exists in a separate file" — this is R-round process info, the file structure is self-explanatory.
- Cleaned the inline R668 comment in InfiniteLoopChecker.finish() to just describe the algorithm.

### File 3: OrmLostUpdateDfaChecker.java (10 → 0 R-refs)

DFA-based ORM_LOST_UPDATE checker (CWE-459) using FunctionSummary cross-method propagation. Not duplicate of plumb-style (kept in R676 full scan) — handles cross-method persistence.

**Key wins**:
- Dropped "R543-DA" version tag from class Javadoc.
- Dropped "R213" reference to the FunctionSummary framework (the class itself is imported; cross-references work).
- Dropped "R585" from the aggressiveness-config field/constructor/inline comments. Kept the design rationale (test-only override, hot-path caching, low/high mode trade-offs).

### File 4: LooseClassChecker.java (10 → 0 R-refs)

Composite source-reading checker for CWE-506, CWE-510, CWE-566. Contains 3 inner classes (EmbeddedMaliciousCodeChecker, TrapdoorChecker, AuthBypassChecker). "Loose" = intentionally aggressive, AI dedup grades FP down.

**Key wins**:
- Same R668 audit-remediation pattern as ClassSourceChecker.
- Kept the substantive "this is intentionally LOOSE, AI grades FP down" note in EmbeddedMaliciousCodeChecker.

### File 5: FunctionSummaryCache.java (10 → 0 R-refs)

Per-engine-run cache of FunctionSummary objects, thread-safe via ConcurrentHashMap + LongAdder. Originally introduced in R213 (single-threaded LinkedHashMap + int counters), lifted to thread-safe in R217.

**Key wins**:
- Dropped the "R213 / R217" version tag from class Javadoc.
- Dropped the "R213 introduced this cache as a single-threaded data structure... R217 lifts it to a thread-safe implementation" historical narrative. Kept the substantive explanation: "the cache was introduced as a single-threaded data structure and later lifted to a thread-safe implementation".
- Dropped 7 "R217:" prefixes from field/method Javadocs. Kept all the substantive content about ConcurrentHashMap, LongAdder, computeIfAbsent atomicity, etc.

### File 6: OffByOneChecker.java (9 → 0 R-refs)

CWE-193 off-by-one error detection in the DFA engine. Operates at TAC IR level, pairs BRANCH operands with subsequent ARRAYLOAD/STORE to confirm off-by-one.

**Key wins**:
- Dropped "R315" version tag from class Javadoc.
- Dropped "R322" (×6) and "R323+" references — the field-name aliasing design and do-while support is purely substantive content.

### File 7: PathSensitiveCheckerBase.java (9 → 0 R-refs)

Base class for path-sensitive DFA checkers (alloc-without-close family). Companion to TaintedCheckerBase.

**Key wins**:
- Dropped "R217c" version tag from class Javadoc + 3 abstract method Javadocs.
- Dropped "R213/R215" reference (the TaintedCheckerBase comparison is the substantive content).
- Dropped "R224" and "R215-style" references; kept the descriptions of how the IR formats callee signatures and how the cross-function propagation works.

### File 8: FindingReporter.java (9 → 0 R-refs)

Collects findings emitted by checkers. Thread-safe, dedup by (file, line, ruleId, cwe). Has a back-reference to AnalysisContext for filePath filling.

**Key wins**:
- Dropped "R211" version tag from class Javadoc.
- Dropped the "R211 v1 single-threaded, parallelism is a later round" parenthetical.
- Dropped 4 "R330" references but kept all the substantive content (why the back-reference is mutable, the chicken-and-egg with AnalysisContext, the filePath-filling logic).
- Dropped the "R211 legacy constructor" Javadoc tag; kept the legacy-vs-new description.

### File 9: ObjectModelChecker.java (8 → 0 R-refs)

Composite source-reading checker for CWE-580 (clone without super.clone) and CWE-581 (equals/hashCode mismatch). 2 inner classes.

**Key wins**:
- Same R668 audit-remediation pattern.
- Dropped 2 "R668" inline comments and the R627 class-level version tag.
- Dropped "R629" from the aggregator Javadoc.

### File 10: PoorCodeQualityChecker.java (8 → 0 R-refs)

CWE-398 (Indicator of Poor Code Quality). Detects 8 sub-patterns: empty block/case/else/if/while/for/function/standalone-semi.

**Key wins**:
- Dropped R668 + R646 references.
- Kept the substantive pattern descriptions in the inline `//` comments above each Pattern declaration. The descriptions explain WHAT the regex matches (empty if body, empty while body, etc.) not WHEN the pattern was added.

### File 11: LooseDanglingChecker.java (8 → 0 R-refs)

Composite source-reading checker for CWE-477 (Obsolete Functions) and CWE-404 (Improper Resource Shutdown). 2 inner classes.

**Key wins**:
- Same R668 audit-remediation pattern as ClassSourceChecker.
- Kept the "CWE-477 detects Thread.stop / Thread.suspend / Runtime.traceInstructions / Runtime.traceMethodCalls / Class.newInstance — all deprecated for security / correctness reasons" substantive list.

---

## Total R-round impact

| Round | R-refs removed | Files touched | Cumulative R-refs remaining |
|---|---:|---:|---:|
| R673 (initial 5 files) | 198 | 5 | ~961 |
| R673.1-R673.11 (6 more) | ~300 | 6 | ~660 |
| R677 (this round) | **~111** | **11** | **~550** |
| (target) | | | ~0 |

The 11 files cleaned in R677 + 11 cleaned in R673+R673.1-R673.11 = 22 files, ~600 R-refs removed. The remaining ~550 R-refs are spread across ~190 lighter files (most have 1-3 R-refs each). At ~20-30 min per file, this is a 5-10 round effort to clean all of them — but most are short fixes (single-line Javadoc tag removal).

---

## Build + test verification

After all 11 file edits:

```
$ mvn -pl plumb-dfa test -Dtest=!R*
[INFO] Tests run: 321, Failures: 0, Errors: 0, Skipped: 2
[INFO] BUILD SUCCESS

$ mvn -pl plumb-main test
[INFO] Tests run: 50, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

All 321 plumb-dfa + 50 plumb-main unit tests pass. The cleanup is comment-only; no behavior change.

---

## What remains (R678+ candidates)

After R677, the next ~10 files for cleanup (sorted by R-ref count) are:

| File | R-refs (est) | Notes |
|---|---:|---|
| `CheckTypeOfResultOfComparatorCheck.java` | 6 | |
| `ConfusedInheritanceCheck.java` | 6 | |
| `CallToUnsupportedMethodCheck.java` | 6 | |
| `EmptyZipEntryCheck.java` | 5 | |
| `IncompatibleMaskCheck.java` | 5 | |
| `SimplifyBooleanExpressionCheck.java` | 5 | |
| `SignedBitCheckCheck.java` | 5 | |
| `ConfusingTernaryCheck.java` | 5 | |
| `FallThroughCheck.java` | 5 | |
| `EmptyMethodInAbstractClassCheck.java` | 5 | |

Top 10 remaining: ~53 R-refs. After R678, the rest of the codebase has ~500 R-refs spread across ~180 files (most with 1-3 R-refs).

**R678+ scope**:
- Continue lighter files (~5-6 R-refs each, ~10 files)
- Then lighter still (~1-3 R-refs each, ~180 files)
- Estimated: 5-10 R-rounds to finish the rest

---

## Why the PROCESS-not-DELETE policy matters here

Looking at the 11 files cleaned in R677, the substantive content I kept was often more valuable than the R-round markers I removed. Examples:

1. **ClassSourceChecker**: kept the 4 inline CWE descriptions (CWE-382 `System.exit()`, CWE-526 `System.getenv()`, CWE-570/571 patterns) — these are the API contract that the checker enforces. The R668 prefix on each is just round metadata.
2. **FunctionSummaryCache**: kept the technical explanation of why ConcurrentHashMap + LongAdder is the right choice ("lock-free concurrent reads and atomic puts", "low-contention"). The R217 prefix is just when the refactor happened.
3. **OffByOneChecker**: kept the field-name aliasing explanation (why SSA names differ in non-static methods + how the field-name map bridges the gap). The R322 prefix is just when the feature was added.
4. **PathSensitiveCheckerBase**: kept the "two dataflow shapes" comparison (flow-to-sink vs alloc-without-close). The R213/R215/R217c references are just when the design split was made.
5. **FindingReporter**: kept the chicken-and-egg explanation (why the back-reference is mutable). The R330 reference is just when the feature was added.

In every case, the SUBSTANCE — what the code does, why it works, what the constraints are — was kept. Only the R-round process info was removed. This is the R673 user-correction policy applied consistently.

---

## Files (R677)

### MOD (11 files, ~50 KB of source, ~111 R-refs removed)
- `plumb-dfa/src/main/java/org/plumb/dfa/checkers/ClassSourceChecker.java` (15 → 0)
- `plumb-dfa/src/main/java/org/plumb/dfa/checkers/ClassThreadChecker.java` (15 → 0)
- `plumb-dfa/src/main/java/org/plumb/dfa/checkers/OrmLostUpdateDfaChecker.java` (10 → 0)
- `plumb-dfa/src/main/java/org/plumb/dfa/checkers/LooseClassChecker.java` (10 → 0)
- `plumb-dfa/src/main/java/org/plumb/dfa/summary/FunctionSummaryCache.java` (10 → 0)
- `plumb-dfa/src/main/java/org/plumb/dfa/checkers/OffByOneChecker.java` (9 → 0)
- `plumb-dfa/src/main/java/org/plumb/dfa/taint/PathSensitiveCheckerBase.java` (9 → 0)
- `plumb-dfa/src/main/java/org/plumb/dfa/engine/FindingReporter.java` (9 → 0)
- `plumb-dfa/src/main/java/org/plumb/dfa/checkers/ObjectModelChecker.java` (8 → 0)
- `plumb-dfa/src/main/java/org/plumb/dfa/checkers/PoorCodeQualityChecker.java` (8 → 0)
- `plumb-dfa/src/main/java/org/plumb/dfa/checkers/LooseDanglingChecker.java` (8 → 0)

### MOD (1 doc)
- `plumb-dfa/docs/R673-COMMENT-CLEANUP-2026-08-21.md` (status line + follow-up chain + "what remains" updated to R677 list)

### NEW
- `plumb-dfa/docs/R677-COMMENT-CLEANUP-NEXT-BATCH-2026-08-22.md` (this file, ~12 KB)

### No production behavior change
All 11 edits are comment-only refactors. 321 + 50 unit tests pass; 9 modules build clean.

---

## Cross-project status (2026-08-22, post-R677)

| Project | Last shipped | In-flight | Test count | CWE coverage |
|---|---|---|---:|---|
| AetherCode | R89 | — | 1640+ Java | UX/infra ships (EXCLUDED) |
| Fathom (C/C++) | R459-5 | R459-5 follow-on | 1174 + 12 R459 | 18 CWE families (EXCLUDED) |
| Plumb (Java) | **R1-R677** | **R678 (next 10 lighter files for cleanup)** | 620+ classes / **429 unit tests** (321 plumb-dfa + 50 plumb-main + 58 plumb-ai) + R* integration | **62 checkers**, 117,190 Juliet cases / 95.0% R / 73.2% P (R658) + AI dedup R672.1 v11 8-thread 88% filter / 97.7% FP-P / 0% bug loss + R668 audit remediation + R669 tool-driven workflow + R670-R672.1 FP-reduction validation + R673 + R673.1-R673.11 comment cleanup (11 files) + R674 duplicate removal (9 dfa-unified) + R675 GitHub prep + R676 full-scan removal (2 more dfa-unified) + **R677 comment cleanup (11 more files, ~111 R-refs removed)** |

**Cumulative R-round count:** 677 (354% over 50+ target)
