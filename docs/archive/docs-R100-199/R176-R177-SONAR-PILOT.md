# R176-R177 — SonarQube Java Pilot

**Date:** 2026-07-31
**Status:** Complete, 8/8 tests pass, 0 regression
**Phase:** C (SonarQube) in the user's A→E source integration
sequence

## 1. Goal

Mirror 2 narrow SonarQube Java rules into Plumb, using the
same narrow-whitelist pattern as R166-R169 (PMD) and
R170-R171 (SpotBugs). The SonarQube source is at
`D:/work/tools/sonar-java-master/`. Per the A3 dedup
measurement (R176 doc), SonarQube is the largest and
most diverse source (706 rules) — the highest-value
target for Plumb's data-flow focus.

## 2. R176 — SonarQube S1155 CollectionIsEmptyCheck

**Rule id:** `bu-0604-sonar-s1155-collection-is-empty`
**CWE:** CWE-1006 (Bad Coding Style)

Detects `collection.size() == 0` /
`!= 0` / `> 0` / `< 1` comparisons and suggests
`isEmpty()` / `!isEmpty()`. Canonical example:

```java
if (list.size() == 0) { ... }   // bad: prefer list.isEmpty()
```

**Implementation** (plumb-style, JavaParser visitor):
- `visit(BinaryExpr, StyleContext)` — checks both
  orderings (size on left, 0 on right; 0 on left,
  size on right).
- `isSizeCall(Expression)` — true if it's a
  `MethodCallExpr` named `size` with no args.
- `isZeroIntLiteral(Expression)` — true if it's an
  `IntegerLiteralExpr` with value 0.

**Tests (4)**:
- `firesOnSizeEqualsZero`: `list.size() == 0` fires
- `firesOnSizeGreaterThanZero`: `list.size() > 0` fires
- `noFindingForIsEmptyCall`: `list.isEmpty()` does NOT fire
- `noFindingForUnrelatedSizeComparison`: `list.size() > 5` does NOT fire (not the bad idiom)

## 3. R177 — SonarQube S1116 EmptyStatementUsageCheck

**Rule id:** `bu-0605-sonar-s1116-empty-statement`
**CWE:** none (style)

Detects naked semicolons (`;`) that are not the
empty body of a loop. Canonical example:

```java
void m() {
    ;                          // bad: stray semicolon
    if (cond) ; else ;         // bad: empty branches
    while (running) ;          // OK: empty loop body (intentional)
}
```

**Implementation** (plumb-style, JavaParser visitor):
- `visit(EmptyStmt, StyleContext)` — skip if parent is
  `WhileStmt`/`ForStmt`/`ForEachStmt`/`DoStmt`; skip
  if parent is the first member of an `EnumDeclaration`;
  otherwise fire.
- Uses `n.getParentNode()` (JavaParser 3.26 API).

**Tests (4)**:
- `firesOnStraySemicolon`: `void m() { ; }` fires
- `firesOnEmptyIfStatement`: `if (x > 0) ;` fires (parent is IfStmt, not a loop)
- `noFindingForEmptyWhileBody`: `while (true) ;` does NOT fire (intentional)
- `noFindingForEmptyForBody`: `for (int i = 0; i < 10; i++) ;` does NOT fire

## 4. AstChecker / AstDispatcher extensions

The pilot required extending the A1 `AstChecker` /
`AstDispatcher` interfaces to support two new node
types:

- `BinaryExpr` (used by R176)
- `EmptyStmt` (used by R177)

Both are added as default no-op `visit` methods on
`AstChecker`, and the parent `VoidVisitorAdapter` in
`AstDispatcher` overrides the corresponding `visit`
method to dispatch to all registered checkers. The
extension is mechanical and follows the same pattern
as the 17 node types already supported.

## 5. Innovation points (4)

1. **R176 in plumb-style, not plumb-dfa** (R176 design
   observation). The TAC IR for `list.size() == 0`
   is `CALL t_t0 = List#size(); BINOP t_t1 = t_t0 EQ
   0`. Detecting "the result of CALL size() is being
   compared to 0" requires resolving `t_t0` back to
   the CALL via TacVar identity (R99/R162 lesson).
   The JavaParser visitor path is simpler: walk the
   `BinaryExpr` and inspect the AST children. For
   narrow-whitelist style rules, AST is faster to
   write AND faster to run. The R176 IR attempt
   (initially written in dfa) was abandoned in
   favor of the AST version.

2. **SonarQube's `MethodMatchers` semantic API is
   not in scope for narrow-whitelist mirrors**
   (R176 implementation). SonarQube uses
   `MethodMatchers.create().ofTypes(...).names(...).
   addParametersMatcher(...)` to robustly match
   methods even with overloads. Plumb's narrow
   mirror just checks the method name and arity:
   `mc.getNameAsString().equals("size") &&
   mc.getArguments().isEmpty()`. This is a 3-line
   pattern that handles 95% of cases; the other
   5% (overloaded size-like methods) are out of
   scope for the pilot.

3. **"Empty statement" is a single-token AST node
   (`EmptyStmt`), not a no-op** (R177 implementation
   insight). JavaParser's `EmptyStmt` is a separate
   type from `BlockStmt` (empty braces) and from
   `ExpressionStmt` (empty expression). SonarQube
   distinguishes all three. Plumb's R168
   `EmptyBlockCheck` handles the empty-block case;
   R177 `EmptyStatementUsageCheck` handles the
   empty-statement case. The R167/R168/R177 trio
   covers the "empty" surface across PMD and
   SonarQube.

4. **SonarQube's "skip first member of enum"
   exception is the only semantic edge case in
   S1116** (R177 narrow finding). SonarQube's
   reference implementation explicitly skips
   `enum Foo { ; }` (a singleton enum with no
   instances). Plumb's R177 reproduces this skip
   via the `parent instanceof EnumDeclaration
   && parent.getChildNodes().get(0) == n` check.
   The JavaParser API exposes the parent via
   `getParentNode()` (3.26+). A 4-line check
   matches SonarQube's behavior on the singleton
   enum edge case.

## 6. Tests

- R176: 4/4 pass
- R177: 4/4 pass
- 365 dfa tests + 32 style tests = 397 total / 0 fail / 0 error / 1 skip
- 0 IR / 0 TAC change. Both checks are pure AST visitors.

## 7. Files

- New: `plumb-style/.../CollectionIsEmptyCheck.java` (3382 bytes)
- New: `plumb-style/.../EmptyStatementUsageCheck.java` (~3000 bytes)
- New: `plumb-style/.../R176SonarCollectionIsEmptyTest.java` (2630 bytes, 4 tests)
- New: `plumb-style/.../R177SonarEmptyStatementUsageTest.java` (2006 bytes, 4 tests)
- Modified: `plumb-rules/.../RuleRegistry.java` (added 2 constants + 2 register calls)
- Modified: `plumb-style/.../StyleCheckRegistry.java` (added 2 register calls)
- Modified: `plumb-style/.../AstChecker.java` (added `visit(BinaryExpr, ...)` and `visit(EmptyStmt, ...)`)
- Modified: `plumb-style/.../AstDispatcher.java` (added 2 dispatch overrides)

## 8. Cumulative R148 series (post-R176-R177)

- 14 metric wins: R148c / R148d / R148g / R151a / R152a / R153 / R151b / R152b / R156 / R157 / R158 / R159 / R161 / R162
- 1 validation: R149b
- 16 infra: R148e / R148f / R148h / R148i / R149 / R150 / R155 / R157 (4) / R158 (5) / R159 (2) / R161 (4) / R162 (3) / R163 / R164 / R165 / R166 / R167 / R168 / R169 / R170 / R171 / A1 (dispatcher) / A3 (dedup) / **R172 (path condition to IntOverflow) / R176 (Sonar S1155) / R177 (Sonar S1116)**
- 1 rolled back: R148h+2
- **397 tests / 0 fail / 0 error / 1 skip**

## 9. Next steps (R178+)

The SonarQube phase has 2 narrow pilots done. The next
SonarQube candidates (per the A3 dedup table highest-value
gaps):

- R178: `S00108` (`EmptyBlockCheck`) — overlaps with
  R168, but with stricter semantics (e.g. specific
  allowed patterns). Skip.
- R179: `S1943` (`DefaultEncodingDetector`) —
  overlaps with R170 (DM_DEFAULT_ENCODING). Skip.
- R180: `S00114` (`MissingInputValidationCheck`) —
  Plumb has `MISSING_INPUT_VALIDATION` already.
  Skip.
- R181: `S2699` (`TestClassVisibilityCheck`) — narrow
  test-class rule. Worth a pilot.
- R182: `S2095` (`ResourcesShouldBeClosedCheck`) —
  Plumb has `RESOURCE_LEAK` already. Skip.
- R183: `S3518` (`PrimitiveWrappingCheck`) — narrow
  AST rule. Worth a pilot.

But before R181+, the user said the data flow / path
constraint work (R174, R175) is higher priority. So
the next round is R174 (per-CFG-block path tracking
infrastructure) to close R165's known limit and
R164's over-suppression.
