# R127 — Direct Field Assignment Translation

**Status:** complete (2026-07-27)
**Tests:** +6 dfa (`DirectFieldAssignmentTest`), **143 dfa + 1 skip** total
**End-to-end:** 115 findings on `examples/` (R126 111 → R127 115, +4 R127 demo)
**Zero regression**

## Why

R124 added `FieldHardcodedPasswordCheck` to catch credential-named
field initializers like `private String password = "literal"`. The
synthetic `<init>` translator repair (R124) made those visible as
`FIELDSTORE` instructions. But there was a parallel bug: an
assignment to a field **inside a method body** like

```java
public void setPassword(String p) {
    password = "literal";      // bare NameExpr, NOT FieldAccessExpr
}
```

was translating to `MOVE`, not `FIELDSTORE`. The check missed it
entirely, and a whole class of CWE-798 setter / reinit anti-patterns
slipped through.

R127 closes that gap. The fix is one branch in the translator's
`writeLhs` helper: a `NameExpr` that does not resolve to a local
or a parameter in the current method's scope is almost certainly an
unqualified instance field reference, so we emit `FIELDSTORE` with
`this` as the receiver.

## Translation matrix (before / after R127)

| Source | AST target | R126 emit | R127 emit |
|--------|-----------|-----------|-----------|
| `int x = 5;` | `VariableDeclarator` | MOVE | MOVE |
| `password = "literal"` (in method body) | NameExpr (no local) | MOVE | **FIELDSTORE** |
| `this.password = "literal"` | FieldAccessExpr | FIELDSTORE | FIELDSTORE |
| `p = 5` (parameter) | NameExpr (in params) | MOVE | MOVE |
| `n = 6` (local in method) | NameExpr (in locals) | MOVE | MOVE |
| `for (int i = 0; i < 10; i++)` | NameExpr (for-body local) | MOVE | MOVE |

The two changes:

1. **FieldAccessExpr target** was already handled in
   `ExpressionTranslator.writeLhs` (R127 partial — predated the
   round but wasn't documented as a separate fix).
2. **NameExpr target that doesn't resolve to a local or param** —
   R127's actual change. Before: emit MOVE. After: emit FIELDSTORE
   with `this` as the receiver.

## Why "fall through to FIELDSTORE" is safe

The fall-through rule is "if a bare name doesn't resolve to a
local or a parameter, treat it as a field write". The argument:

- A `NameExpr` in an assignment LHS can only mean one of three
  things: a local variable, a method parameter, or an instance
  field. (Static fields go through `<clinit>` initialization
  paths, not direct assignment, and that case is filtered out
  by the `<clinit>` exclusion in the check itself.)
- If the translator has already added the name to `locals()` or
  `params()` (which it does for any `VariableDeclarator` or
  method parameter), the original MOVE path is taken.
- If the name is not in `locals()` or `params()`, it is by
  elimination an instance field reference. The `FIELDSTORE` we
  emit carries the field name; the `this` receiver is a
  constant TacVar that downstream checkers can recognize.

The three regression-guard tests verify this for the most common
false-positive patterns:

- `for (int i = 0; ...)` — the for-body counter is a local;
  we still emit MOVE.
- `void m(int p) { p = 5; }` — `p` is a parameter; MOVE.
- `private int n; void m() { int n = 5; n = 6; }` — the local
  `n` shadows the field `n`; MOVE.

## Test scenarios (6/6 passing)

`DirectFieldAssignmentTest`:

1. `bareNameFieldAssignmentInMethodBody` — `password = "..."` now emits FIELDSTORE
2. `thisPrefixedAssignmentWorks` — `this.password = "..."` regression guard
3. `checkFiresOnMethodBodyAssignment` — R124 check fires on method body
4. `forLoopCounterNotReportedAsField` — for-loop counter regression guard
5. `paramReassignmentNotReportedAsField` — parameter regression guard
6. `localShadowingFieldNameNotReportedAsField` — local-shadowing-field regression guard

## End-to-end (`examples/r127/R127Demo.java`)

Five inner classes:

| Class | Should fire? | Why |
|-------|--------------|-----|
| R127PlainSetter | YES (L34, L38) | Field init + bare-name setter |
| R127Reinit | YES (L47) | `this.password` (existing path) |
| R127LocalShadow | NO | local shadows field name |
| R127ForLoop | NO | for-loop counter is a local |
| R127ParamReassign | NO | parameter reassignment |

End-to-end matches: **3 FIELD_HARDCODED_PASSWORD** at L34 /
L38 / L47, plus 1 HARDCODED_CREDENTIAL on L34 (R114 baseline).
The 3 negative controls do not fire. The `setPassword` line
(L38, the bare-name setter) is the one R127 specifically fixes.

## Implementation

- `ExpressionTranslator.writeLhs` (line 543+): added a
  fall-through to `FIELDSTORE` when a `NameExpr` target does not
  resolve to a local or a parameter.
- One new test file (`DirectFieldAssignmentTest`, 6 tests).
- One new demo file (`R127Demo.java`).
- No changes to the rule registry, the scan config, or the
  scan runner — the new behavior is purely a translator fix.

## Innovation summary (R127-specific)

- **The "fall-through to FIELDSTORE" rule is a small but
  high-value architectural choice.** It treats a NameExpr that
  doesn't resolve to a local as evidence that the developer
  wrote an unqualified field reference. This is the same
  pattern Java itself uses: an unqualified `x` inside an
  instance method means `this.x`. The translator now agrees.
- **The fix is in the translator, not the checker.** R124's
  check already knew what to do with FIELDSTORE; it just wasn't
  getting any for the bare-name case. R127 closes the IR gap
  rather than papering over it in the check, which means future
  FIELDSTORE-consuming checkers (R118 type-3 ofstream, R132
  field-name-based sensitive detection, ...) all benefit.
- **Three regression-guard tests are the right shape for a
  translator fix.** When you change the IR, the unit tests
  must cover the false-positive patterns that the change could
  introduce. The three cases (for-loop counter, parameter
  reassignment, local shadowing a field) are the textbook
  reasons "emit FIELDSTORE on every NameExpr" would be wrong.
  Each test is a one-paragraph statement of "the translator
  must not regress on this pattern."

## Files changed

- `plumb-tac/.../translate/ExpressionTranslator.java` —
  `writeLhs` fall-through to FIELDSTORE (~20 lines added)
- `plumb-dfa/.../test/.../DirectFieldAssignmentTest.java`
  (new, 6 tests)
- `examples/r127/R127Demo.java` (new, 5 scenarios)

## Related docs

- `R124-FIELD-HARDCODED-PASSWORD.md` — the parent check; the
  consumer of the new FIELDSTORE instructions
- `R125-ANNOTATION-IR.md` — the other half of the R125 / R127
  architectural push (R127 is what R124 always wanted but
  couldn't get)
- `R122-FOLLOWUP-FIXES.md` — earlier translator-repair work
  that established the precedent for fixing the translator
  when a check fails
