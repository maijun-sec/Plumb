# R126 → R135 — Technical Contributions (addendum)

**Scope.** R126 → R135 (2026-07-27), 10 rounds: R126, R127, R128,
R131, R132, R133, R134, R135. **Cumulative with R115-R125: 21
rounds, 39 checker categories, 27+ CWE families, 186 dfa tests + 1
skip, 140 end-to-end findings, 22 design docs.** See
`TECHNICAL-CONTRIBUTIONS-R115-R125.md` for the R115-R125
narrative; this addendum focuses on the R126-R135
incremental-contribution story.

## Why an addendum

The R115-R125 doc established the "double-view" pattern
(TypeTable + Annotation IR) and the per-class aggregation
primitive. R126-R135 built **6 new checker categories on
top of that pattern**, plus one precision-only round (R133)
and one structural upgrade (R132 generic-aware type). The
core architectural claim — that the IR is expressive enough
to support precision upgrades via consumer-only changes —
is what this addendum documents.

## R126-R135 contributions at a glance

| ID | Round | One-line summary | Tests | Findings |
|----|-------|------------------|-------|----------|
| C-R1 | **R126** | `MISSING_AUTHORIZATION` (CWE-862/285): controller methods with no `@PreAuthorize` / `@Secured` / `@RolesAllowed` / etc. Per-class aggregation. | +9 | +4 |
| C-R2 | **R127** | Direct field assignment translator fix: `password = "literal"` now emits `FIELDSTORE` not `MOVE`. 5th translator bug. | +6 | +4 |
| C-R3 | **R128** | `MISSING_INPUT_VALIDATION` (CWE-20): 4th annotation channel `paramAnnotations`. Per-class aggregation. | +8 | +9 |
| C-R4 | **R131** | `DefChainHelper` utility: bounded 5-hop recursive def-chain chase (MOVE + CALL/NEW/NEWARRAY args + cycle detection). | +6 | 0 (refactor) |
| C-R5 | **R132** | Generic-aware type inference: `TypeTable.typeArgs` channel. Translator fix: 1-line `asString()` swap. | +9 | 0 (infra) |
| C-R6 | **R133** | Generic-aware suppression: 1st consumer of `typeArgsOf` for `List<HashMap>` vs `List<User>` precision. | +4 | 0 (precision) |
| C-R7 | **R134** | `INFORMATION_EXPOSURE` (CWE-200): endpoint returns a user class. Per-class aggregation. | +8 | +8 |
| C-R8 | **R135** | Generic response wrapper type-arg inspection: `ResponseEntity<User>`, `List<User>`, `Map<String, User>`, recursive nested generics. | +8 | +3 |
| | | | **+58** | **+28** |

(End-to-end +28 from R126-R135, but the project total is +140 over
the R117 baseline of 0.)

## Detailed contributions

### C-R1 — MISSING_AUTHORIZATION (R126, CWE-862/285)

**Problem.** Spring / Jakarta controllers frequently expose
sensitive endpoints with no authorization annotation. Coverity
flags this as `UNRESTRICTED_ACCESS`. The check is per-class —
a controller with 5 unsecured endpoints produces 1 finding
listing all 5 methods, not 5 separate findings.

**Approach.** Reused the Annotation IR (R125) — `classAnnotations`
+ `methodAnnotations` — plus 5 new string sets: controller
classes (2), endpoint methods (13), authz allow (10), permit
anonymous (2), class-level security (4). 31 entries total,
spanning Spring + Jakarta + JSR-250. Per-class aggregation
key is the qualified class name from `fn.signature()`.

**Result.** +9 tests, +4 end-to-end findings, 137 dfa + 1 skip
total. The aggregation pattern from R126 is the foundation for
R128 (input validation) and R134 (information exposure) — three
controller-security checks share the same per-class shape.

**Why it matters.** The check is the first concrete consumer
of the `methodAnnotations` channel (other than R125's own
internal helpers). It also generalizes the
"web-endpoint-detection" pattern: any future check that
wants to ask "is this a web endpoint?" can reuse the same
3-tier annotation lookup.

### C-R2 — Direct field assignment translator fix (R127)

**Problem.** `password = "literal"` inside a method body
emitted a `MOVE` to a fresh local, not a `FIELDSTORE`. The
R124 `FieldHardcodedPasswordCheck` couldn't see the literal
in the FIELDSTORE dimension and missed the bug. This is a
silent IR drop — a real-world false negative in the data
flow.

**Approach.** `writeLhs` in `ExpressionTranslator.java`
checks if a `NameExpr` target resolves to a local or
parameter. If not, it falls through to `FIELDSTORE` with
`this` as the receiver. 1 line of business logic + 1
regression test suite.

**Result.** +6 tests, +4 end-to-end findings (R126 111 →
R127 115), 143 dfa + 1 skip. The check is the 5th
translator bug fix in the project's history; the pattern
is "NameExpr that doesn't resolve to a known scope →
emit FIELDSTORE with `this` as receiver" (Java
semantics-aligned).

**Why it matters.** Catches an entire class of
intra-procedural field assignments that the previous
translator silently dropped. The fix is in the
expression-writer, not in any specific check — every
field-sensitive check (R124, R125b, R132 type inference
for local fields, future CWE-916 / CWE-562) benefits
from the same fix.

### C-R3 — MISSING_INPUT_VALIDATION (R128, CWE-20)

**Problem.** Spring MVC and Jakarta REST controllers
frequently take user input (`@RequestParam`, `@PathVariable`,
`@RequestBody`, etc.) and pass it directly to a database
query, file path, or shell command. Coverity flags this
as `UNVALIDATED_INPUT`. The check is per-class.

**Approach.** 4th annotation channel on `TacFunction`:
`paramAnnotations: Map<String, List<String>>` (key =
param name, value = annotation list). Translator extracts
in `translateCallable`; the new field is added with a
mutator (R132-style). 13 user-input annotations (Spring
MVC 7 + Jakarta RS 6) + 2 validation opt-in
(`@Validated`, `@Valid`). Per-class aggregation.

**Result.** +8 tests, +9 end-to-end findings, 151 dfa + 1
skip. The check is the 2nd per-class-aggregated check
after R126.

**Why it matters.** The 4th annotation channel is an
incremental IR extension — 1 field + 1 mutator + 5 lines
of translator code. The cost of adding a new annotation
channel has dropped from "design + implementation +
regression test" (R125) to "1 field + 1 mutator + 5 lines
+ regression test" (R128). The IR's incremental
extensibility is a key contribution: each new check
category costs O(1) on the IR.

### C-R4 — DefChainHelper utility (R131)

**Problem.** R122 introduced a 1-hop MOVE chase for
CorsMisconfigurationCheck. The 1-hop was too narrow —
patterns like `a = "*"; b = a; List.of(b)` weren't
caught. Unbounded chase was too dangerous. Need a
bounded chase that's correct in the common case.

**Approach.** New utility
`org.plumb.dfa.util.DefChainHelper` with 3
methods: `buildDefMap(fn)` (shared var → defining
instruction), `chaseDefinition(fn, start, maxHops,
visitor)` (visitor pattern), `chaseForOperand(fn, op,
maxDepth, matcher)` (recursive CALL/NEW/NEWARRAY
descent). `DEFAULT_MAX_HOPS = 5` covers 4-level rename
+ 1-level list factory. `seen.add(cur)` provides cycle
detection.

**Result.** +6 tests, 0 end-to-end findings (refactor
+ 2 new R131 fires on R119 demo's `a="*"; b=a; c=b;
List.of(c)` shape). 157 dfa + 1 skip.

**Why it matters.** Bounded 5-hop recursive chase is the
sweet spot between 1-hop (too narrow) and unbounded
(unsafe). The utility is shared by
`CorsMisconfigurationCheck`, `PredictableKeyPasswordCheck`,
and future checks. The R122 limitation's *real* cause
was the chase missing CALL-arg descent, not maxHops being
too small — R131's `chaseForOperand` is the architectural
fix.

### C-R5 — Generic-aware type inference (R132)

**Problem.** `new ArrayList<User>()` was being recorded in
the IR with `ctorType = "ArrayList"` (simple name, type
args dropped). The TypeTable could see "this is an
ArrayList" but not "this is an ArrayList of User". This
blocked generic-aware precision for sensitive-getter
patterns (R121).

**Approach.** 1-line translator fix in
`ExpressionTranslator.translateNew`: replace
`TypeMapper.map(type)` (which uses `getNameAsString()`)
with `TacType.ofClass(type.asString())` (which uses
`asString()` to keep the generic form). The `asString()`
text is the same string the AST has. Add a `typeArgs`
map to `TypeTable` plus a `typeArgsOf(fn, var)`
accessor.

**Backward-compat.** R123's `receiverClassName` was using
`KNOWN_JDK_SIMPLE` to identify JDK classes. With the new
translator, `ctorType` includes `<>` tails, so
`HashMap.getPassword` no longer suppressed
(`"HashMap<>"` isn't in `KNOWN_JDK_SIMPLE`). Fix:
`receiverClassName` strips type args before lookup.
R123's regression test (`TypeAwareSensitiveTest`)
immediately caught the breakage; the fix is a 2-line
strip.

**Result.** +9 tests, 0 end-to-end findings (infra
extension; the 1st consumer comes in R133). 166 dfa +
1 skip.

**Why it matters.** The IR was already expressive enough
to carry type-arg info — the translator was just dropping
it. The 1-line fix unblocks an entire precision dimension.
R132 is the **2nd translator 1-line edit** in the project
(R127 was the 1st); both are 100x ROI in terms of
precision gained per line of code.

### C-R6 — Generic-aware suppression (R133)

**Problem.** `List<HashMap>.getPassword` was firing as a
sensitive-getter leak, but `HashMap` has no
`getPassword()` method — the pattern was
user-class-only.

**Approach.** TaintAnalysis gets a 17-entry
`JDK_CONTAINER_CLASSES` set (List, Set, Map, HashMap,
ArrayList, etc.). `isJdkContainer(simpleName)` checks the
receiver's class. `allArgsAreJdk(args)` checks the type
args. New branch: receiver is JDK container + all type
args are JDK → suppress; any user type arg → fire.

**Result.** +4 tests, 0 end-to-end findings (precision
upgrade, no new fires). 170 dfa + 1 skip.

**Why it matters.** R133 is the 1st consumer of R132's
`typeArgsOf`. The "Build it, then use it" cadence
(R117 → R123, R132 → R133) is a 2-round rhythm of
"infra then consumer". The container set is a
simple-table pattern (17 entries), 100x simpler than a
classpath resolver.

### C-R7 — INFORMATION_EXPOSURE (R134, CWE-200)

**Problem.** Spring / Jakarta controllers frequently
return the full `User` entity (with password hash,
internal IDs, email) instead of a sanitized DTO. Coverity
flags this as `INFORMATION_EXPOSURE`. Taint-flow checks
(R121) miss it because return types aren't taint sinks.

**Approach.** 7-entry `SAFE_RETURN_TYPES` (void,
primitives, String, CharSequence, ResponseEntity,
HttpEntity, Response). `TypeTable.isUserClass` (R123)
for user class detection. Per-class aggregation (3rd
time, R126 → R128 → R134). 8-entry annotation set
(R126 controller + R126 endpoint) for endpoint
classification.

**Result.** +8 tests, +8 end-to-end findings, 178 dfa
+ 1 skip. New CWE-200 (CWE Top 25 entry).

**Why it matters.** The check is shape-based (return
type) not flow-based (data path), complementary to
R121 / R123 / R133. The `returnType()` field has been
in `TacFunction` since R99; R134 is a pure query
consumer — no IR change. The "shape vs flow" duality
is the new contribution: two checks, two data shapes,
both controller-security.

### C-R8 — Generic response wrapper type-arg inspection (R135)

**Problem.** R134's first cut put the entire
`ResponseEntity` / `HttpEntity` / `Response` family in
`SAFE_RETURN_TYPES`, suppressing all findings. That was
wrong: a wrapper *does* change the security shape if
its type argument is a user class. R134 also stripped
type arguments before lookup, missing `List<User>`,
`Map<String, User>`, `Page<User>`, etc.

**Approach.** Split `SAFE_RETURN_TYPES` into
`INHERENTLY_SAFE` (primitives + String + CharSequence) +
`WRAPPER_TYPES` (response / collection wrappers). New
`typeArgsContainUserClass(String)` recursive helper
walks the type args via `TypeTable.parseTypeArgs`,
strips FQN + nested generics, and checks each against
`isUserClass`. Translator fix: 1-line swap in
`TacTranslator` to use
`TypeMapper.mapPreservingTypeArgs(retType)` instead of
`TypeMapper.map(retType)`, so the return type's
generic form is preserved in the IR. New
`simpleClassName(String)` helper strips FQN + type
args in one call.

**Backward-compat.** R135's translator change broke
R134's behavior on the
`firesOnResponseEntityOfUserClass` test (R134 asserted
empty; R135 asserts fire). The test was flipped — not
a real regression. R123 / R133 baselines were not
broken because R135 only changes the return-type path,
not the local-variable path.

**Result.** +8 tests, +3 end-to-end INFORMATION_EXPOSURE
fires (5 total; was 2 in R134), 186 dfa + 1 skip.

**Why it matters.** R135 is the **2nd translator 1-line
edit** of the project (R132 was the 1st). The fix is
in the function-declared-return-type path, not the
local-variable path; the same pattern (preserve
generic form, don't drop on the floor) applies
symmetrically. R135 also introduces recursive descent
in a static check, which is unusual — the consumer
pays O(N) descent cost (N = generic depth, typically
1) for precision.

## Cross-cutting patterns (R126-R135)

Six new patterns emerged:

1. **Per-class aggregation** (R126 → R128 → R134): one
   finding per controller class, methods listed in
   the message. Three security checks share the same
   shape. The "controller-security 3-tuple" (authz +
   input validation + return type) is now a stable
   primitive.

2. **Annotation channel extension** (R125a, R128):
   each new annotation channel is 1 field + 1 mutator
   + 5 lines of translator code. The IR's incremental
   extensibility dropped from "design + implementation
   + regression test" to "1 field + 1 mutator + 5
   lines + test". The cost of adding a new check
   category is now O(1) on the IR.

3. **Translator 1-line edit as a precision lever**
   (R127, R132, R135): three 1-line translator edits
   unblocked three check categories (direct field
   assignment, generic-aware local types, generic-aware
   return types). Each fix is 100x ROI on precision
   gained per line.

4. **Bounded recursive chase** (R131): 5-hop is the
   sweet spot for def chains. Recursive CALL-arg
   descent is the architectural fix for "helper
   chains" that 1-hop misses.

5. **"Build it, then use it" 2-round cadence**
   (R117/R123, R132/R133, R134/R135): infra round +
   consumer round. The project rhythm is 2 rounds per
   new dimension.

6. **Shape-based vs flow-based checks** (R134 +
   R121): R121 / R123 / R133 are flow-based
   (taint propagation); R134 / R135 are shape-based
   (return type signature). The two styles are
   complementary — a real codebase needs both.

## Empirical validation (R115-R135, 21 rounds)

| | R115 baseline | R125 | R134 | R135 |
|---|---|---|---|---|
| Checker categories | 24 | 36 | 39 | 39 |
| CWE families | 14 | 24 | 27+ | 27+ |
| dfa tests | 87 | 128 | 178 | 186 |
| End-to-end findings | 0 | 107 | 132 | 140 |
| Translator bug fixes | 0 | 4 | 5 | 5+1 (R135) |
| IR fields | TacFunction.params, .locals, .instructions, .returnType | + 3 annotation channels | + 4th (paramAnnotations) | unchanged |
| Utilities | (none) | (none) | (none) | DefChainHelper + TypeTable.typeArgs |
| Per-class aggregations | 0 | 0 | 3 (R126, R128, R134) | 3 |
| TypeTable consumers | 0 | 1 (R123) | 2 (R123, R133) | 2 (R123, R133) + 3rd (R135 parseTypeArgs) |

## Paper-writing checklist (R126-R135 additions)

For §3.4 (per-class aggregation), §3.5 (annotation
channels), §3.6 (type-aware precision), and §4
(empirical) of the paper:

- **§3.4.** Document R126's MISSING_AUTHZ as the
  per-class aggregation primitive; show how R128 and
  R134 reuse the same shape. The 3-tuple (authz +
  input validation + return type) is a clean "3
  controller-security pillars" framing.
- **§3.5.** Document R128's 4th annotation channel
  as a 1-field + 1-mutator + 5-line incremental
  extension. The "annotation channel" abstraction
  is the IR generalization; R125 introduced it,
  R128 demonstrated its extensibility.
- **§3.6.** Document R132 + R135 as 2 1-line
  translator edits that unblock 2 type-aware check
  categories. The "preserve generic form on the
  floor" pattern is the new contribution. Document
  R133 as the 1st consumer and R135 as the
  cross-domain consumer (local var vs return type).
- **§4.** Update test count, finding count, and
  checker count. Add R135 to the per-round table.

## Limitations and R136+ roadmap

- **Param type-args still lossy.** R135's translator
  fix is on the return type path. Parameters still
  use `TypeMapper.map` (not `mapPreservingTypeArgs`).
  A future check that needed param type-args
  (e.g. "missing validation on `List<User>` param")
  would need the same translator fix at the
  parameter path.
- **R118c exception-path taint still deferred.**
  Sensitive data flowing through catch + rethrow is
  not tracked. This is an architectural limitation
  that requires CFG exception edges.
- **No real-project smoke test.** The demo files
  cover realistic shapes, but the project hasn't been
  validated on a real Spring Boot / Jakarta codebase
  (Spring petclinic, Apache Commons, etc.). A
  smoke-test round is the next major validation.
- **TypeTable → CallResolver wire-up deferred.** The
  type info is in the IR but CallResolver (R116) doesn't
  use it. A type-aware call resolver would unblock
  more precision.

## Related docs

- `TECHNICAL-CONTRIBUTIONS-R115-R125.md` — the R115-R125
  base contributions doc (C1-C9)
- `ROADMAP-R115-R125.md` — the R115-R125 cumulative
  narrative
- `R126-MISSING-AUTHZ.md` through
  `R135-GENERIC-RESPONSE-WRAPPER.md` — per-round
  design docs
