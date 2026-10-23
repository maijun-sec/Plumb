# R220c — ExposeInternalRepresentationCheck fix

Date: 2026-08-01
Status: **DONE** (28/28 R206BatchTest tests pass, 166/166 plumb-style tests, 0 regression)

## Goal

Fix the last 1 of the 2 R206 pre-existing
test failures
(`exposeRep_firesOnArrayGetter`) so the
test suite is back to all-green.

## Root cause

`ExposeInternalRepresentationCheck.collectEnclosingFieldTypes`
was using `fd.getElementType()` to
record the field's type, but for an
array field like `int[] data`, the
element type is `int`, not `int[]`. The
`v.getType()` call returns the
variable's full type (the array), which
is what we need.

```java
// Before (R206, broken):
result.put(v.getNameAsString(), fd.getElementType());
// For `int[] data` → "data" -> int (wrong)

// After (R220c, fixed):
result.put(v.getNameAsString(), v.getType());
// For `int[] data` → "data" -> int[] (correct)
```

The bug went undetected because:
- All the existing tests use primitive
  `int x` (not `int[] x`) — for which
  `getElementType()` and `getType()`
  both return `int`.
- The new test in R206 used `int[] data`
  and exposed the difference.

## Test results

- R206BatchTest: 28/0/0/0 (was 27/1/0/0;
  +1 fixed)
- plumb-style: 166/0/0/0 (was 165/1/0/0;
  +1 fixed, 0 regression)

## Pitfall

- **`FieldDeclaration.getElementType()`
  vs `VariableDeclarator.getType()`**:
  for `int[] data` the two return
  different things. The former is the
  array's element type (`int`), the
  latter is the variable's type
  (`int[]`). The naming is confusing
  because `getElementType()` reads like
  "the field's element type" (which
  sounds like the whole thing), but
  JavaParser's API specifically means
  "the type of the array's elements".

## Backup

The change is in
`plumb-style/src/main/java/org/plumb/style/ExposeInternalRepresentationCheck.java`.
Doc: this file.
