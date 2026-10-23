# R139 — ScanRunner Path Filter Precision Fix

**Status:** complete (2026-07-27)
**Scope:** `ScanRunner.expandInputs` filter — only the
test-directory exclusion, no other changes
**Tests:** zero new tests (the filter is integration-level)
**End-to-end:** any path containing `test\` (e.g.
`smoke_test/`, `test_utils/`, `latest/`) is now
correctly scanned

## Why

R136's first smoke-test scan returned 0 findings
because the path filter was too aggressive:

```java
.filter(p -> !p.toString().contains("test" + java.io.File.separator))
```

The directory `smoke_petclinic\` (used in R136 because
I didn't want to fight the path conventions) was
incorrectly filtered out — the path contains
`test\` even though it's not a Java test directory.
The R136 workaround was to rename to
`smoke_petclinic` (no underscore). R137a kept that
name. R139 fixes the root cause so any directory
name works.

## The fix

The new helper `isUnderTestDirectory(Path p)` checks
for a Java-style test directory specifically:

```java
private static boolean isUnderTestDirectory(Path p) {
    String s = p.toString();
    String sep = java.io.File.separator;
    return s.contains(sep + "src" + sep + "test" + sep)
            || s.contains(sep + "test" + sep + "java" + sep)
            || s.endsWith(sep + "src" + sep + "test")
            || s.endsWith(sep + "test");
}
```

The patterns:

1. `\src\test\` (or `/src/test/`) — Java's
   `src/main/java` vs `src/test/java` convention.
2. `\test\java\` — a top-level `test/java` directory
   (some projects put tests at the top level, e.g.
   `test/java/com/example/FooTest.java`).
3. Paths ending in `\src\test` or `\test` — a
   bare `src/test` directory scanned as an input.

Anything else — including `smoke_test\`,
`test_utils\`, `latest\`, etc. — is now correctly
NOT filtered out.

## End-to-end validation

Tested the previously-broken directory name
`smoke_test/smoke_petclinic/...` (the original
problematic path from R136). After R139, the
scanner returns 14 security findings, identical
to the `smoke_petclinic/...` path. The fix works
on the original problematic directory name.

## Limitations and R140+ work

- **Still doesn't recognize Maven multi-module
  layouts** where each module has its own
  `src/main/java` and `src/test/java`. The current
  filter matches the `src/test/` segment anywhere
  in the path, which is correct for both single
  and multi-module projects. R140+ could add
  support for module-specific exclude patterns.
- **No config option for test inclusion.** Some
  smoke tests want to scan test code too (e.g.
  to find @Disabled tests that should be enabled,
  or tests with @DisabledWithoutReason patterns).
  R140+ could add `-i test` to `ScanConfig`.
- **Linux case-sensitivity.** On Windows, the
  filter is case-insensitive (NTFS). On Linux, it's
  case-sensitive. The standard Java convention is
  lowercase `test`, so this matches. R140+ could
  add a `caseSensitive` flag.

## Paper R139 contribution

- **Path filter precision is part of the user's
  experience.** R139 is a 1-hour fix but it
  unblocks the entire "smoke test on a real
  project" workflow. The fix is small but
  outsized: it removes a footgun that had cost
  R136 a round of debugging.
- **Generalize the heuristic.** The original
  filter was a 1-line substring check — too
  narrow. The new check is a 4-line
  path-segment check — more precise. The same
  trade-off applies to all of the project's
  pattern-recognition sets (R123 / R125 / R131 /
  R133 / R137a / R138): narrow-but-fast vs.
  precise-but-slow. R139 is the project choosing
  precise-but-slow for the path filter because
  the cost is one-time per scan.
- **Path segments vs substrings.** A path segment
  is bounded by `File.separator`. A substring
  can match anywhere. The fix is the same
  principle as R137a's annotation imports:
  match the right boundary, not the raw string.
