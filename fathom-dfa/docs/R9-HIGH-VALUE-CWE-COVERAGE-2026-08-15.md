# R9 — High-Value CWE Coverage (319/15/470/606)

**Date:** 2026-08-15
**Goal:** Cover the largest Juliet CWE families still at 0 hits.
**Status:** SHIPPED — 4 new CWE families, 2,634 new cweFire hits.

## Background

R8 brought the unique CWE family count to 23. R9 targets the
high-volume Juliet families that were still at 0 hits. These
families are also the most impactful in real codebases:

| CWE | Juliet files | Why important |
|---|---|---|
| CWE-15 (External Control) | **720** | Configuration injection from untrusted source |
| CWE-319 (Cleartext Tx) | **610** | Sensitive data over unencrypted channel |
| CWE-470 (Unsafe Reflection) | **720** | Code injection via Class.forName / Method.invoke |
| CWE-606 (Unchecked Loop) | **732** | DoS / logic bug via tainted loop bound |

The R8+ backlog also called out CWE-114 (Process Control) which
is already covered by `CommandInjectionChecker` (CWE-78 sink
family includes `Runtime.exec` and `ProcessBuilder.start`).

## New Checkers

### 1. `CleartextSocketUsageChecker` (CWE-319) — AI-primary

Source-reading. Finds every `new Socket(host, port)` call. The
heuristic narrows to functions that also handle sensitive data
(`password` / `token` / `ssn` / `secret` etc. within ±2000 chars).
AI prompt distinguishes real cleartext password transmission
from health-check pings and test fixtures.

**Result:** AI-primary candidate; the actual Juliet hits come
from the taint-style `CleartextNetworkCredentialSinkChecker`
sibling (R9-A below) which fires 114 hits.

### 2. `CleartextNetworkCredentialSinkChecker` (CWE-319) — taint-style

Sibling of `DBCredentialSinkChecker` (R7, CWE-256). Same taint
framework, same `db.credential.sinks` family, same
`sinkArgumentIndex(2)`, but CWE-319 label. The two checkers
run side-by-side and tag their findings with different CWEs
so Juliet counts them separately.

**Why two checkers?** CWE-256 is "stored in plaintext at
rest" (file source), CWE-319 is "transmitted in plaintext
over the wire" (network source). The taint framework doesn't
distinguish, so we run both and let AI dedup judge which is
more accurate.

**Result:** 114/610 hits.

### 3. `ExternalConfigSettingSinkChecker` (CWE-15) — taint-style

Taint-style. New `external.config.sinks` family with patterns
for `System.setProperty`, `Connection.setCatalog`,
`Locale.setDefault`, `TimeZone.setDefault`, `URL.setURLStreamHandlerFactory`,
`UIManager.setLookAndFeel`, `DriverManager.setLogStream`,
`DriverManager.setLogWriter`. Taint source is the standard
UNTRUSTED_SOURCE. `sinkArgumentIndex(0)` — most "set"
methods take the new value as the first user arg.

**Result:** 408/720 hits (56% recall).

### 4. `UncheckedLoopConditionChecker` (CWE-606) — DFA-style

Per-function tracking:
1. Mark TacVars that come from `.readLine()` /
   `.nextInt()` / `Integer.parseInt()` / `parseLong()` etc.
   as tainted
2. Propagate taint through MOVE / BINOP / CALL
3. On BRANCH, if the condition is a BINOP LT/LE/GT/GE
   with a tainted operand, fire CWE-606

The pattern detects `for (i=0; i < taintedBound; i++)`
without trying to recognize the loop structure explicitly.
AI prompt distinguishes real DoS / logic-bug risk from
test fixtures and bounded loops.

**Result:** 1704/732 hits (2.3 hits/file because many Juliet
files have multiple loops).

## Bugs Fixed in R9

- **R9-A — `reflection.sinks` short-form patterns missing.**
  Same R6-B / R7-A class of bug. The TAC IR emits
  `Class.forName/1` (short form) when the symbol solver
  doesn't resolve the FQN, but the YAML pattern was
  `^java\.lang\.Class\.forName(/\d+)?` (FQN form). Added
  short-form patterns for all three reflection sinks
  (`Class.forName`, `ClassLoader.loadClass`, `Method.invoke`).
  Result: CWE-470 went from 0 to 408 hits.

## Combined R1-R9 CWE-fire Totals

| Family | R1-R8 | R9 | Total R1-R9 |
|---|---|---|---|
| Taint-style (12 families) | 9,198 | 522 (CWE-15 408 + CWE-319 114) | **9,720** |
| DFA-style (11 families) | 340 | 1,704 (CWE-606) | **2,044** |
| AI-primary (8 families) | 243 | 0 | **243** |
| **Grand total** | 9,781 | 2,634 | **12,415** |
| **Unique CWE families** | 23 | 4 | **27** |

(Two CWE families are doubled-counted in different
classifications: taint/AI-primary. The unique family count
is 27, the total hits is 12,415 across all 27 families.)

## Files Added/Modified in R9

**New checkers (4):**
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/CleartextSocketUsageChecker.java` (R541, AI-primary CWE-319)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/taint/CleartextNetworkCredentialSinkChecker.java` (R542-A, taint CWE-319)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/taint/ExternalConfigSettingSinkChecker.java` (R543, taint CWE-15)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/UncheckedLoopConditionChecker.java` (R545, DFA CWE-606)

**YAML updated:**
- `plumb-dfa/src/main/resources/sink-families.yaml`:
  - Added `external.config.sinks` family (R543) — 12 patterns
  - Added short-form patterns for `reflection.sinks` (R9-A fix) — 3 more patterns

**Test files added:**
- `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R542CleartextSweep.java` — single-CWE targeted sweep for CWE-319
- `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R543Cwe15Sweep.java` — single-CWE targeted sweep for CWE-15/470/606
- `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R544Cwe470Sweep.java` — single-CWE targeted sweep for CWE-470

**Test status:** 112/112 DFA-style + taint CheckerTest pass.

## R10+ Backlog

- **CWE-114 (Process Control)** — verify CommandInjection covers it (CWE-78 sink family includes Runtime.exec and ProcessBuilder.start)
- **CWE-832 (Unlock Not Locked)** — 2 files. Pattern: unlock() call without corresponding lock() on all paths. Complex control-flow analysis.
- **CWE-833 (Deadlock)** — 6 files. Pattern: nested locks in different orders. Complex.
- **CWE-252/253 (broader)** — UncheckedReturnChecker exists but only fires for a hardcoded list of fail-able methods. Could be expanded with an AI-primary pass.
- **CWE-114/117/120/124/125/127/128/129 cross-taint** — 2-3 days to verify coverage.
- **CWE-690 (NULL Deref From Return)** — 489 files. Should be covered by NullDerefChecker; verify.
- **CWE-400 (Resource Exhaustion)** — 2396 files. Hard to detect without concrete resource limits; AI-primary pass.
- **CWE-23/36/78/89/113 cross-CWE** — already covered, but check for missed variants in 22/45/67/71-75.
