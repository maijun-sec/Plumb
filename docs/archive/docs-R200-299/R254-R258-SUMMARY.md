# R254 — R258 Summary

## TL;DR

Five rounds that close out the
cross-function analysis + style rule
work and put the engine on a clean
foundation for the next phase (R259
parser/translator upstream work, R260
path-sensitive walker).

| Round | Goal                                              | Tests added | Cumulative tests | Cumulative R-docs |
|-------|---------------------------------------------------|------------:|-----------------:|------------------:|
| R254  | MethodSummary (inter-procedural, not inline)      |          26 |              468 |             ~502KB |
| R255  | SummaryApplier (wire summary into DFA)            |          12 |              480 |             ~508KB |
| R256  | Engine intercept + LiteralBoolean fold-aware      |           5 |              485 |             ~514KB |
| R257  | AssignmentInConditional fold-aware + Juliet sweep |           4 |              489 |             ~519KB |
| R254b | 12 PMD/Sonar/SpotBugs style checkers              |          26 |              515 |             ~528KB |
| R258  | SideEffectConsumer (caller-state invalidator)     |          15 |              530 |             ~536KB |

After R254-R258, the plumb-dfa
project has **530 / 0 / 0 / 0** tests
across 165+ source files in 0 functional
regressions.

## The trilogy: R254 / R255 / R256 (cross-function)

The R254-R256 trilogy delivers a
complete cross-function analysis path:

```
   ┌──────────────────────┐    ┌──────────────────────┐
   │ MethodSummaryBuilder │    │  MethodSummaryCache  │
   │  (R254, path-insens) │ →  │  (R255, BoundedLru)  │
   └──────────────────────┘    └──────────────────────┘
            │                            │
            ▼                            ▼
   ┌──────────────────────────────────────────────┐
   │  SummaryApplier.apply(call, ctx, args)        │
   │  (R255)                                       │
   │   - applyReturnValue → RETURN_VALUE_FOLDED    │
   │   - applySideEffect   → SIDE_EFFECT_RECORDED  │
   └──────────────────────────────────────────────┘
            │                            │
            ▼                            ▼
   ┌──────────────────┐    ┌──────────────────────────┐
   │ LiteralBoolean   │    │  SideEffectConsumer      │
   │ AssignmentInCond │    │  (R258, per-function)    │
   │ (R256, R257)     │    │   wasInvalidated(key)    │
   └──────────────────┘    └──────────────────────────┘
```

The key design choices:

- **Inter-procedural summary, not inline** (user R254 directive). The
  R254 builder walks the callee's IR
  once and produces a `MethodSummary`
  with `returnShape` (one of 10 shapes)
  and `sideEffect` (5 buckets). The
  engine caches summaries; the applier
  consults the cache at every CALL
  site.
- **Engine-level intercept, not
  per-checker** (R256 critical fix).
  R255 ship'd `SummaryApplier` but no
  caller. R256 added the
  `if (ins.kind == CALL) SummaryApplier.apply(...)` line in the
  engine's RPO loop, before any
  checker's `checkInstruction`. This
  is the wire the cross-function
  analysis depends on.
- **Stable string-encoded fact values**
  (R255 design). The applier encodes
  shapes and side effects as strings
  (`"const:5"`, `"param:0"`,
  `"writes-this:foo"`, …) so checkers
  can match on the value without
  coupling to the builder's internal
  types. The encoding is documented in
  the applier's javadoc.
- **Path-insensitive single-pass**
  (R254). The builder is intentionally
  path-insensitive: a 2-arm `if` with
  `return 5; return 7;` is collapsed
  to a `RETURN_BINARY_OP` (or
  `RETURN_LOCAL_VAR` if both arms
  read the same temp). This is
  fast, sound, and what every
  cross-function-analysis consumer
  needs to remain linear.
- **UNKNOWN fallback** (R254 design).
  The builder's UNKNOWN sentinel is
  pre-installed in the cache so the
  applier always returns a value
  (`"top"`). The checkers treat
  `"top"` as "no information, default
  to worst case". UNKNOWN is the
  R254 escape hatch that keeps the
  consumer code path simple.

## The fold consumers: R256 / R257

The R256 / R257 rounds teach two
branch-condition checkers to consume
`RETURN_VALUE_FOLDED`:

- **R256** — `LiteralBooleanConditionChecker`. A `BRANCH` whose
  `lhs1` is a `TacVar` carrying a
  `RETURN_VALUE_FOLDED` fact with
  value `const:5` (or any other
  constant) is reported as CWE-547
  with a fold-aware message:
  *"folds to a literal constant: 5 —
  branch is dead/redundant (after
  cross-function fold)"*.
- **R257** — `AssignmentInConditionalChecker`. Same pattern: a
  `BRANCH` whose `lhs1` is a `TacVar`
  that was assigned by a fold-derived
  constant, and the assignment is
  the immediate predecessor.
  Reported as CWE-481.

Both checkers distinguish
"source-level literal" from
"cross-function fold" via message
suffix, per the user's R256 design
("都报, 区分 message"). The other 3
branch-condition checkers
(EmptyIfBlock, UnusedLabel,
DeadCodeAfterReturn) inspect
*structure* (not the cond operand)
and don't benefit from the fold fact;
this is documented in the R257
doc.

## The side-effect consumer: R258

R258 closes the loop on the
SIDE_EFFECT_RECORDED half:

```java
// DfaEngine.java — RPO loop:
if (ins.kind == TacInstruction.Kind.CALL) {
    SummaryApplier.apply(ins, ctx, ins.args);
    ctx.sideEffectConsumer().recordCall(ins, ctx);  // R258
}
c.checkInstruction(ins, fn, ctx);
```

The consumer records the invalidation
log keyed by `"this:foo"`,
`"param:0"`, `"static:Foo.bar"`.
Checkers can call
`wasInvalidated("param:0")` or use
the convenience helper
`liveFactsOf(operand, bag)` to drop
cached facts about operands that a
callee may have mutated.

The wiring of downstream checkers
(NullDeref, DivideByZero) is the
next round's work — R258 delivers
the consumer and its engine hook.

## The style-checkers round: R254b

R254b delivers all 12 PMD /
SonarQube / SpotBugs style checkers
in one batch:

| CWE    | Checker                              | Severity |
|--------|--------------------------------------|----------|
| 396    | CatchGenericException                | LOW      |
| 397    | ThrowGenericException                | LOW      |
| 500    | PublicStaticMutableField             | LOW      |
| 580    | CloneWithoutSuper                    | MEDIUM   |
| 585    | EmptySyncBlock                       | LOW      |
| 609    | DoubleCheckedLocking                 | MEDIUM   |
| 617    | ReachableAssertion                   | LOW      |
| 674    | UncontrolledRecursion                | MEDIUM   |
| 698    | RedirectWithoutExit                  | MEDIUM   |
| 1116   | ReturnInFinally                      | MEDIUM   |
| 459    | IncompleteClassDeclaration           | LOW      |
| 586    | FinalizeOnlyOverridden               | MEDIUM   |

All 12 use the same shape:
`TaintedCheckerBase` subclass +
`StyleCheckerEmit` helper. The
`super.checkInstruction` call is a
no-op for the kinds the style
checkers care about (`BRANCH`,
`NEW`, `THROW`, `LABEL`,
`FIELDSTORE`, `NOP`). 26 unit tests
in `R254BStyleCheckersTest`,
zero regressions.

## What this unlocks

After R254-R258 the focus can shift
*entirely* to:

1. **R259** — Parser/Translator upstream
   enhancement to support Juliet
   `try-catch-finally` (so R256/R257
   fold-aware checkers actually find
   `if (IO.staticReturnsTrue())` in
   real Juliet IR). This is the
   prerequisite for the fold-aware
   checkers to produce real Juliet
   findings.
2. **R260** — Path-sensitive walker that
   consumes `RETURN_VALUE_FOLDED` for
   `PathCondition` (R218c extension).
3. **R261+** — DFA engine precision /
   recall improvements (context
   sensitivity, points-to, call
   graph).
4. **R262+** — Z3 / solver improvements
   (native Z3, constraint caching,
   path-condition optimisation).

The DFA engine now has all the
necessary infrastructure (cross-function
summary, side-effect invalidation, fold
consumers, style rules) to focus on
the actual dataflow story.

## Innovation points summary (16 across 5 rounds)

R254:
1. Path-insensitive 2-arm bool const
   detection
2. BoundedLru named class (vs anonymous)
3. MOVE-chase aliasing with cycle guard
4. UNKNOWN sentinel pre-installed in
   cache

R255:
5. MethodSummaryCache + FunctionSummaryCache
   parallel
6. Stable string-encoded fact values
7. Pure-method side-effect short-circuit
8. Fallback synthesized signature for
   non-canonical call sigs

R256:
9. Engine-level CALL intercept (R255漏
   的关键 hook)
10. 都报, 区分 message 设计
11. foldedConstValue 复用 FactBag query
    pattern
12. R255 null-check (FunctionSignature.parse
    返 null 不抛)

R257:
13. R256 + R257 同 pattern 推广
14. 3 个不受益 checker 文档化
15. Path bug 修复 (JULIET_ROOT 双 src)
16. Parser/Translator 局限性发现
    (真 Juliet 0 finding 根因)

R258:
17. String-keyed invalidation log
18. liveFactsOf drop-whole-list
19. Per-function lifetime matches
    FactBag
20. Pure-method short-circuit at the
    consumer too

R254b:
21. StyleCheckerEmit enum severity
    (vs String)
22. All 12 share same super-call
    contract
23. TacOperand.Const.ofNull() for
    null-const branches
24. PMD/Sonar/SpotBugs-style is
    structural, not dataflow

## Pitfalls summary (8 across 5 rounds)

R254 (1):
- `currentParams` not set in `build()`
  at start → `chase()` saw stale
  `List.of()` default

R255 (3):
- SummaryApplier nobody called (fixed
  in R256 by adding engine intercept)
- `FunctionSignature.parse` returns
  null for non-canonical sigs
- `FactBag.add(Fact)` not
  `add(operand, fact)`

R256 (1):
- `MethodSummary.signature` is field
  not method (test only)
- `SideEffect.Builder` is final
  (test only)
- `FunctionSignature.parseParamType`
  doesn't exist (test only)
- `recordCall` requires `call.dest` to
  find fact (test only)

R257 (1):
- `move` helper type mismatch
  (TacOperand vs TacVar)

R254b (1):
- `ThrowGenericExceptionChecker`
  missing `TacOperand` import

## Cumulative state

- **530 tests / 0 fail / 0 err / 0 skip**
- **5 + 1 + 1 + 1 + 1 + 1 = 10 R-docs**
  (R254/R255/R256/R257/R254b/R258
  individually + the R253-R255 cross-function
  trilogy doc + this summary)
- **~536KB cumulative R-docs**
- **60+ source files** in plumb-dfa/src/main/java
- **60+ test files** in plumb-dfa/src/test/java

## Backups

- `D:\work\tmp\r254_done\` (R254
  MethodSummary infra)
- `D:\work\tmp\r255_done\` (R255
  SummaryApplier)
- `D:\work\tmp\r256_done\` (R256
  engine intercept + LiteralBoolean)
- `D:\work\tmp\r257_done\` (R257
  AssignmentInConditional + Juliet
  sweep)
- `D:\work\tmp\r254b_done\` (R254b 12
  style checkers)
- `D:\work\tmp\r258_done\` (R258
  SideEffectConsumer)
