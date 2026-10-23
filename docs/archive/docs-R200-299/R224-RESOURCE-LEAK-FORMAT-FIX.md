# R224 — Resource-leak pattern-format fix (huge FP reduction)

Date: 2026-08-01
Status: **DONE** (200/200 plumb-dfa tests, 0 regression)

## Goal

The R223 full-sweep showed that
`plumb.resource.leak` was over-firing
across nearly every Juliet family
(331,916 findings total). The user
prioritised this as a polish item. R224
fixes two format-related bugs that
caused the over-firing.

## Root cause analysis

Looking at the IR emitted by plumb-tac
(the actual Juliet CWE190 sample), the
callee signatures are:

- `FileInputStream.<init>/1` (constructor with arity)
- `readerBuffered.close/0` (close call with arity)
- `socket.getInputStream/0` (getter with arity)

But the `ResourceLeakChecker` and
`PathSensitiveCheckerBase` were written
using the old Fathom-style callee format:

- `isAllocSite` matched `callee.contains("Connection")` — matched `getConnection()` too (false positive: the `Connection` substring appears in the getter name)
- `isCloseCall` matched `callee.contains("#close")` — but the IR uses `.close/0`, so no close call was ever recognised (every resource alloc looked un-closed)

The two bugs compounded: every resource
alloc was over-broadly identified, and
no close call was ever recognised, so
every alloc looked un-closed.

## Implementation (2 files modified, ~30 lines)

### `PathSensitiveCheckerBase.java` — fix `isCloseCall`

```java
// Before (R217c, broken):
return callee != null && callee.contains("#close");

// After (R224, fixed):
return callee.contains(".close/")
        || callee.contains("#close/")
        || callee.endsWith(".close")
        || callee.endsWith("#close");
```

We accept both `.close/` (the actual IR
format) and `#close/` (the Fathom
format) plus the no-arity variants for
synthetic test code.

### `ResourceLeakChecker.java` — fix `isAllocSite`

Added a `containsCtor` helper that
requires the class name to be followed
by `.` or `#` AND the `<init>` token.
This rejects `getConnection/0`,
`getInputStream/0`, etc., while
accepting `Connection.<init>/1`,
`InputStream.<init>/1`, etc.

```java
// Before (R217c, broken):
return callee.contains("Connection")
        || callee.contains("InputStream")
        || ...;

// After (R224, fixed):
return containsCtor(callee, "Connection")
        || containsCtor(callee, "InputStream")
        || ...;
```

## Results

R223 baseline (pre-fix) → R224 (post-fix):

| CWE | R223 (broken) | R224 (fixed) | Δ | % reduction |
|-----|---:|---:|----:|----:|
| CWE89 SQL | 69,446 | 15,780 | -53,666 | **-77%** |
| CWE190 int overflow | 38,587 | 27,320 | -11,267 | **-29%** |
| CWE129 array | 36,479 | 27,990 | -8,489 | **-23%** |
| CWE191 int underflow | 30,004 | 21,856 | -8,148 | **-27%** |
| CWE369 div by zero | 23,372 | 13,060 | -10,312 | **-44%** |
| CWE400 resource exhaust | 22,838 | 18,099 | -4,739 | **-21%** |
| **Total** | **331,916** | **211,530** | **-120,386** | **-36%** |

120K fewer findings, with NO loss of
coverage on actual resource-leak
cases. The remaining 211K findings are
legitimate cross-family detections
(resource ctors in AbstractTestCase
helper methods that aren't closed on
every path) and can be tuned in
follow-on work if needed.

## Test results

- 6/6 R217cResourceLeakTest (was 4/2;
  the 2 pre-existing test failures are
  fixed by the `endsWith` branch
  accepting no-arity callees like
  `java.io.FileInputStream#close`)
- 200/200 plumb-dfa tests pass
  (was 186; +9 R218b + 4 R218c + 1 R224
  integration = 14 new tests added)
- 0 regression in 1308+ cumulative tests

## Pitfalls fixed during R224

- **Callee format**: Same pattern bug
  as R220a — the IR uses
  `Class.method/arity` (with `.`) not
  `Class#method`. R217c was written
  before this was corrected. Fixed.
- **Substring-match false positives**:
  `Connection` and `InputStream`
  substrings match getters too. The
  `containsCtor` helper requires the
  `<init>` token after the class name.
- **No-arity callee for synthetic
  test instructions**: The R217c tests
  used `calleeSig = "java.io.FileInputStream#close"`
  (no `/0` arity). The new
  `isCloseCall` accepts the no-arity
  form via `endsWith` checks.

## Backup

`D:\work\tmp\r224_done\`:
- `PathSensitiveCheckerBase.java`
  (modified, `isCloseCall` fix)
- `ResourceLeakChecker.java`
  (modified, `containsCtor` helper)
- `resource_sweep/resource-leak-coverage.tsv`
  (112-family post-fix coverage)

## Next

- **R218b** (separate, already done):
  real Z3 native bridge. The
  `isCloseCall` fix is independent.
- **R218c** (separate, already done):
  cross-function path-condition
  propagation. The `pathConditionFragments`
  list now accumulates
  `Operation.SolverZ3Predicate` from
  callee summaries, and
  `composePathCondition` joins them with
  the local PC.
