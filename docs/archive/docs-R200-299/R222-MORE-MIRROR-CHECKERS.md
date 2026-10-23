# R222 — More mirror checkers (CWE-129, CWE-22/23/36)

Date: 2026-08-01
Status: **DONE** (186/186 plumb-dfa tests, 0 regression)

## Goal

Reduce false negatives (漏报) by adding
mirror checkers for CWE families the
R215/R216 framework didn't yet cover.
The user explicitly asked to address
both漏报 and 误报; R220 already cut FP;
R222 cuts FN.

## Implementation (2 new checkers + YAML fix, ~12KB)

### `plumb-dfa/.../taint/checker/ArrayIndexChecker.java` (new, 4723 bytes)

CWE-129 (Improper Validation of Array
Index). Juliet pattern:

```java
int data = Integer.parseInt(readLine());
int[] array = { 0, 1, 2, 3, 4 };
if (data < array.length) {  // missing: data >= 0
    IO.writeLine(array[data]);  // BAD: data could be negative
}
```

Watches ARRAYLOAD / ARRAYSTORE; fires
when lhs2 (the index) is a tainted
input-source var. Uses the R220
GuardTracker to suppress when the index
is bounded by an active `>= 0` guard
(the goodB2G fix).

### `plumb-dfa/.../taint/checker/PathTraversalChecker.java` (new, 4686 bytes)

CWE-22 / CWE-23 / CWE-36 (Path
Traversal). Juliet pattern:

```java
String data = readLine();
String root = "/home/user/uploads/";
File file = new File(root + data);
new FileInputStream(file);  // BAD: data could be "../etc/passwd"
```

Watches the bundled `file-path.sinks`
family. The base class only checks CALL
sinks; this checker extends
`checkInstruction` to also handle NEW
instructions (`new FileInputStream(...)`)
because that's how Java object
construction appears in the IR.

Also propagates taint into the dest of
NEW when an arg is tainted, so
`new File(t_t28)` with t_t28 tainted
results in the new File being tainted
and passed through to the next
constructor.

### `plumb-dfa/.../main/resources/sink-families.yaml` (modified)

Two fixes:

1. **Pattern format**: changed from
   `Class#method` (Fathom-style) to
   `Class.method/arity` (plumb-tac IR).
   The IR doesn't include the FQN
   (e.g. `java.io.FileInputStream`); it
   emits the short form
   (`FileInputStream.<init>/1`).
2. **Added more sinks**: `Scanner.<init>`,
   `RandomAccessFile.<init>`, plus a few
   nio.file paths.

## Results (post-R220, R222 added)

| CWE | R219 | R220 (FP reduction) | R222 (FN reduction) | Δ vs R219 |
|-----|-----:|--------------------:|--------------------:|----------:|
| CWE-89 SQL | 6,162 | 6,162 | 6,162 | 0 |
| CWE-78 Command | 816 | 816 | 816 | 0 |
| CWE-129 array | n/a | n/a | **3,997** | new |
| CWE-190 overflow | 6,051 | 5,163 | 5,163 | -888 |
| CWE-369 div zero | 3,958 | 2,788 | 2,788 | -1,170 |
| CWE-23 path | n/a | n/a | **359** | new |
| CWE-476 null | 119 | 119 | 119 | 0 |
| **Total** | **17,106** | **15,048** | **19,404** | +2,298 |

(R190 baseline was 9,954 across 4
families; R222 covers 7 families with
19,404 findings.)

## Pitfalls fixed during R222

- **YAML pattern format**: The bundled
  `sink-families.yaml` used `Class#method`
  (Fathom-style). The plumb-tac IR uses
  `Class.method/arity`. Initial test
  with `FileInputStream#<init>` didn't
  match `FileInputStream.<init>/1`. Fixed
  the YAML.
- **YAML FQN prefix**: Patterns started
  with `^java\.io\.` but the IR emits
  short class names without the FQN.
  Fixed by matching the short form.
- **NEW instruction not handled by
  base class**: TaintedCheckerBase only
  checks CALL sinks, not NEW. The CWE-23
  pattern uses `new FileInputStream(...)`
  which is a NEW in IR. Added NEW
  handling in PathTraversalChecker.
- **NEW doesn't propagate taint**:
  `new File(t_t28)` with t_t28 tainted
  produced a NEW dest t_t29, but
  t_t29 was NOT in taintedVars. Added
  NEW taint propagation: if any arg is
  tainted, mark dest as tainted too.

## What R222 didn't do

- **CWE-191 int underflow**: Already
  covered by the existing
  `IntOverflowChecker` (which fires on
  BINOP ADD/SUB/MUL). No new checker
  needed.
- **CWE-200 sensitive data leak**: The
  `SensitiveDataLeakChecker` exists
  (R216) but doesn't have explicit
  output yet. R221 is a future round.
- **CWE-401 resource leak (missing
  release)**: The R217c
  `PathSensitiveCheckerBase` covers
  alloc-without-close (CWE-404). The
  CWE-401 distinction (close only on
  some path) is a R220 work item.

## Test results

- plumb-dfa: 186/0/0/0 (was
  184; +2 R222 integration tests, 0
  regression)
- R219 integration test: 7/0/0/0;
  findings per family:
  - CWE-89: 6,162
  - CWE-78: 816
  - CWE-129: 3,997 (NEW)
  - CWE-190: 5,163 (-14.7% vs R219)
  - CWE-369: 2,788 (-29.6% vs R219)
  - CWE-23: 359 (NEW)
  - CWE-476: 119
  - **Total: 19,404**

## Backup

`D:\work\tmp\r222_done\` (4 files, ~30KB):
- `ArrayIndexChecker.java` (new)
- `PathTraversalChecker.java` (new)
- `sink-families.yaml` (modified)
- `R219UnifiedScanIntegrationTest.java`
  (modified, +2 tests)

## Next

- **R223** (optional): run Juliet all
  113 family for full coverage report
- **R220a** (already done)
- **R218b/c** (optional): real Z3 bridge
  + cross-function path-condition
  propagation
- **R221** (CWE-200/359 distinction)
- **R206 fix** (2 pre-existing tests)
