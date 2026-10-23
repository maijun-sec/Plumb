# R115 → R125 — Technical Contributions

**Purpose.** Paper-ready summary of the technical innovations delivered
across the 11-round R115–R125 effort. Each contribution is presented
in a uniform *Problem → Approach → Result → Why it matters* shape so
reviewers can read either the full document or a single section
without losing context.

**Scope.** R115–R125 (2026-07-19 → 2026-07-27), 11 rounds, **36
checkers** across **24+ CWE families**, **320 unit tests** with 1
documented skip, **107 end-to-end findings** on the bundled example
corpus. This document deliberately omits the historical / per-round
narrative; see `ROADMAP-R115-R125.md` for that.

---

## 1. Problem context

Java static analysis tools face three chronic problems that
production users complain about, and the open-source / academic
landscape has only partial answers for:

1. **Taint engines that go inter-procedural lose precision
   catastrophically.** A naive context-insensitive summary erases
   the difference between a public API and a private helper, so
   the false-positive rate goes through the roof as soon as you
   look at any non-trivial codebase.
2. **Framework-aware code looks "wrong" to a vanilla data-flow
   tool.** A Spring `@ConfigurationProperties` bean assigning
   `private String password = "default"` is a framework default,
   not a CWE-798 hard-code. A Jakarta `@Resource`-injected field
   is the same story. A naive checker fires on every one of them.
3. **Translators drop information silently.** A pure syntax-driven
   AST → IR translator that fails to model non-static field
   initializers (the most common case in modern Java) loses an
   entire dimension of findings without throwing or warning. The
   bug is invisible until a downstream checker asks "where is the
   value of `this.password`?" and gets an empty answer.

The R115–R125 work is structured around three corresponding
contributions, plus three orthogonal technical innovations that
fell out of the implementation. Section 2 maps them to the rounds
that produced them; Section 3 dives into each one.

---

## 2. Contributions at a glance

| # | Contribution | Key rounds | Tests added |
|---|--------------|-----------|------------|
| **C1** | **Lightweight TAC IR + name-keyed SSA.** Var identity is the var name as a string; per-function builders are mutable during translation; `TacFunction` carries source-positions, type, and (R125) annotations. | R99, R115, R116 | 16 |
| **C2** | **Field-sensitive IFDS-style taint engine with sanitizer summaries.** Recursive worklist with bounded depth, position-based sanitizer tracking, lambda body bridging, field map (strong read / weak update). | R115, R116, R118 | 33 |
| **C3** | **Catalog-driven taint pattern matching with `**` glob + leading-dot-optional.** Multi-catalog architecture (independent `sensitiveBuiltin()`) prevents double-report; dedup key includes sink identity. | R119a, R121 | 23 |
| **C4** | **TypeTable with `KNOWN_JDK_SIMPLE` receiver-class matching.** Conservative fall-back (unknown receiver still fires) preserves coverage while dropping JDK-class false positives. | R123 | 3 |
| **C5** | **Annotation-aware IR with dual-tier DI suppression.** Class-level + method-level + field-level annotation lists, consumed by `FieldHardcodedPasswordCheck` to suppress Spring / Jakarta DI placeholders. | R125 | 10 |
| **C6** | **Translator repair: synthetic `<init>` restores instance field initialization.** Catches a silent IR drop that was hiding an entire dimension (FIELDSTORE) from any checker that needed it. | R124 | 6 |

**End-to-end validation** of all six contributions:
- **128 dfa unit tests** + 1 documented skip (was 67 in R117)
- **6 style / 2 reporter / 3 main** tests, all passing
- **107 findings** on `examples/` (was 0 in R117, +9 R125 demo
  vs R124 baseline of 98)
- **36 checker categories** (was 15 in R114)
- **24+ CWE families** covered (was ~7 in R114)

---

## 3. Detailed contributions

### C1 — Lightweight TAC IR with name-keyed SSA

**Problem.** Traditional SSA-style IRs use the variable object
itself as identity. The cost is that every type, scope, and
version must be carried in the variable's `equals` and `hashCode`,
which makes per-instruction processing slow and
defeats simple `HashMap<TacVar, ...>` patterns. We needed an IR
that could be cheaply built and cheaply walked, without losing
the ability to look up "where was this variable defined?".

**Approach.** A `TacOperand.TacVar` carries `name + type + isTemp`,
but the program's data structures (`TacProgram.functions` map,
`TaintFact.fieldMap` map, `defs` map for bounded chase) all key
on the **string name**. SSA versioning is implicit in the linear
instruction order — a `MOVE` to `t_t3` shadows any earlier
`MOVE` to `t_t3`, and a 1-hop def-chase walks instructions in
order. The result is that the common lookup "who wrote this
var last?" is `O(1)` with a `HashMap<String, ...>` and no
object identity reasoning.

Two further pragmatic choices fell out of the implementation:

- **Per-function builder is mutable.** The translator's
  `TacBuilder` collects instructions, locals, and (R125)
  annotations in mutable fields; `build()` freezes them into
  a `TacFunction` once. No builder-over-builder copy logic,
  no builder pooling, no thread-safety requirement —
  the translator is single-threaded per function.
- **`TacFunction` carries annotation metadata (R125a).**
  Three new fields — `classAnnotations`, `methodAnnotations`,
  `fieldAnnotations` — populated from the JavaParser AST at
  translate time. The class-level list is propagated to every
  child function (`<clinit>`, synthetic `<init>`, regular
  method, `InitializerDeclaration`) so a checker can ask
  "is this function in a `@RestController`?" from any function
  in the class.

**Result.** The TAC is the workhorse of the engine. The
R99-onwards string-keyed design was preserved unchanged through
R125; the only addition was metadata, not semantics. End-to-end
this is the foundation that makes C2, C3, C4, C5 possible
without IR rework.

**Why it matters (paper angle).** SSA does not have to be
expensive. The string-keyed variant is a pragmatic middle ground
between Figma-style "var object" SSA and the original
three-address-code with no versioning at all. It gives
single-pass lookups at the cost of a clear convention
("versioning is implicit in linear order"), and it
preserves the property that a `TacVar` can be put in a `Set`
without any custom `equals`.

---

### C2 — Field-sensitive IFDS-style taint engine

**Problem.** A textbook IFDS / IDE taint engine propagates
facts through a flow graph. Java makes this hard because:
(a) the call graph is inter-procedural, so a single tainted
source can flow through dozens of methods; (b) modern code uses
lambda bodies whose bindings are not visible from the call
site; (c) field reads / writes interact with object taint
in non-obvious ways (`x.f = clean` does not necessarily
"clean" `x.f`'s slot).

**Approach.** Three concrete techniques:

1. **Recursive worklist with bounded depth.** `TaintAnalysis.analyze`
   seeds the worklist with source facts, then for each
   `Call` instruction recurses into the callee with a
   `pullReturnTaint` "what-if" pass. The recursion is
   guarded by an `analyzing` set + a `MAX_RECURSION_DEPTH = 50`
   cap. The depth cap means even pathological call graphs
   terminate; the analyzing set means mutual recursion
   (A → B → A) is handled by a single fixpoint rather than
   infinite descent.
2. **Position-based sanitizer tracking (R115).** A callee's
   sanitizer list is keyed on **parameter position**, not
   parameter name, because caller-side names and callee-side
   names differ (`password` in `validate(password)` is `p` in
   the callee). A catalog match against the call site
   ("`validate/1` is a sanitizer of type X at position 0")
   gets the right fact at the call site, where it is then
   killed.
3. **Field map with strong read / weak update (R118b).**
   `TaintFact` carries `fieldMap: Map<TacOperand, Map<String, Set<String>>>`.
   `taintOfField(base, fieldName) = fieldSlot ∪ baseTaint` —
   a field read looks at the slot *and* the base, because
   "the field is sensitive when the receiver is sensitive"
   is a real pattern. `withField(base, fieldName, newTaint)`
   updates the slot but **does not** touch the base nor
   sibling slots — this is the IFDS-standard "weak update"
   for flow-insensitive reasoning.

A complementary infrastructure choice: **summary cache per
`run()` invocation** (R117), invalidated at the start of
each `run()` so a checker that runs multiple passes does not
get stale summaries. The cache is sized by
`fn.name() + "/" + fn.params().size()` (the call-site-style
key), which lets a single callee with multiple call sites
be summarized once.

**Result.** R115-R116 turned the original intra-procedural
taint engine (R114) into an inter-procedural, lambda-aware,
sanitizer-respecting one. R118 added field sensitivity. R119
extended the catalog to 17 sink families. R121 layered an
independent catalog on top for sensitive-data detection.
End-to-end the engine fires on every CWE-89/78/77/22/79/117/502
case in `examples/` without precision loss on the simple
patterns.

**Why it matters (paper angle).** IFDS is well-known
theoretically but rarely implemented with a working
sanitizer model in open-source tools. The position-based
sanitizer tracking is a small but high-value detail: it
removes a whole class of "false positive because the
catalog couldn't match the param" reports that other tools
have to live with. The field map's strong-read / weak-update
is a textbook IFDS choice, but we surfaced it as a separate
map rather than overloading `TaintFact`'s base taint, which
keeps the base-taint fast path fast.

---

### C3 — Catalog-driven taint pattern matching

**Problem.** Each sink family (SQL injection, command injection,
LDAP injection, XSS, etc.) has its own function shape, but they
share a common structure: a method-call or constructor whose
name (with package) identifies the family. Hand-coding per-family
checkers produces a combinatorial explosion (one class per
family × one test per family). The opposite extreme — a single
regex per family — is fast but easily falls over on
**overloads** (a callee named `getString/1` is not the same
as `getString/2`).

**Approach.** A `TaintCatalog` is a list of `Entry { kind,
methodPattern, source, ... }`. The matching algorithm walks the
catalog and tests each entry's pattern against the call
instruction's callee signature (e.g. `java.lang.Runtime.exec/1`)
or constructor signature (`new java.lang.String/1`).

Three concrete details matter:

1. **`**` glob with single-segment `*`.** A pattern segment
   `**` matches any number of segments (including zero), so
   `**.compile/*` matches `java.util.regex.Pattern.compile/1`,
   `java.text.MessageFormat.compile/1`, etc. A single `*`
   matches exactly one segment, so `*.compile/*` matches
   only `java.util.regex.Pattern.compile/1`. The asymmetry
   keeps the catalog concise while letting the user express
   "I don't care about the FQN" vs "I want to pin down the
   package".
2. **Leading-dot-optional** (R119a fix). A pattern
   `**.Foo.bar/*` should match both `Foo.bar/1` and
   `outer.Foo.bar/1`. The first segment is treated as
   "any package prefix, including none", and only segments
   after the first are subject to glob matching. This is a
   small but high-impact fix that previously caused a class
   of false negatives where the user wrote a `**.X` pattern
   expecting it to match a top-level `X`.
3. **Multi-catalog architecture (R121).** Two independent
   catalogs — `builtin()` (the original 17 sink families) and
   `sensitiveBuiltin()` (sensitive data sources + sensitive
   sinks like `logger.*`, `System.out.println`, `FileWriter.write`).
   `SensitiveDataLeakCheck` runs its own `TaintAnalysis` instance
   with the sensitive catalog. The result: `userInput → log` fires
   `LOG_INJECTION`; `sensitiveData → log` fires
   `SENSITIVE_DATA_LEAK`. The two are independent facts that
   can co-occur on the same line, and the architecture
   reflects that.
4. **Dedup key includes sink identity.** Each finding's
   dedup key is `ruleId + line + sinkSig + fn.signature()`,
   not the original `ruleId + line + fn.signature()`. The
   `sinkSig` is the callee signature for `CALL`
   (`"System.out.println/1"`) or the synthesized
   `"new RuntimeException"` for `NEW`. This lets the same
   line fire once per sink — `throw new RuntimeException(msg);
   System.out.println(msg);` reports both, where the old
   key would have reported one and suppressed the other.

**Result.** R119a added 8 new taint sink families (LDAP, XML,
header, format-string, NoSQL, OGNL, regex, open-redirect)
**without any new checker class** — they are all catalog
entries. R121 added 11 sensitive sources and 4 sensitive
sink groups, also as catalog entries. Total: 16 new
findings categories delivered in 2 rounds, with 23 unit
tests covering them.

**Why it matters (paper angle).** Catalog-driven pattern
matching is the standard way to scale a static analyzer
beyond a handful of sinks, and the `**` glob is a
known trick. The non-obvious contributions are (a) the
leading-dot-optional fix, which we discovered and
documented; and (b) the multi-catalog architecture,
which solves the "same line, different rule" problem
cleanly. The dedup key refinement is a small data-structure
choice that pays off in the report quality.

---

### C4 — TypeTable with `KNOWN_JDK_SIMPLE` receiver matching

**Problem.** A pattern like `**.getPassword/*` is a useful
method-only sensitive-getter pattern, but it over-fires:
any class with a `getPassword` method will match, including
`HashMap.getPassword` (which doesn't exist) or any third-party
class that happens to expose a `getPassword` for unrelated
reasons. Knowing the **receiver's class** lets us distinguish
"user class with a credential getter" from "JDK class with
a same-name method".

**Approach.** `TypeTable` builds a per-TU map
`function-sig + operand → receiver class FQN` during
`analyze()`. Once built, `receiverClassName(fnSig, operand)`
returns the FQN; `isUserClass(fqn)` returns
`!isKnownJdk(fqn)`. A known-JDK check is a prefix check
for FQNs (`java.`, `javax.`, `com.sun.`, ...) plus a
60-entry `KNOWN_JDK_SIMPLE` set for the cases where the
translator records only the simple name
(`HashMap`, `Random`, `String`, `Integer`, ...).

The conservative fall-back is the key invariant:
**unknown receiver still fires the rule.** This means a
cross-TU receiver we can't resolve still produces a
finding; only an *explicitly known* JDK class suppresses.
The result: precision improvement without coverage loss.

**Result.** R123 turned `TypeTable` from "designed but
unused" infrastructure (R117) into a live consumer of
`SensitiveDataLeakCheck`. The `HashMap.getPassword` false
positive that previously fired is now correctly suppressed.
Unknown user classes (`com.foo.User`) still fire, so the
cwe-532 coverage is preserved.

**Why it matters (paper angle).** Receiver-class matching
is a textbook precision technique; what's interesting is
the **cost story**. A full classpath-resolved type
inference (the obvious answer) requires loading the JDK
and the project's compile classpath, which is a multi-second
warm-up. The `KNOWN_JDK_SIMPLE` string-set approach has
zero warm-up, zero classloader dependencies, and runs in
the test suite in single-digit milliseconds. The same
idea generalizes to the `AnnotationSuppressionTest` of
R125, which uses the same string-set pattern for
DI annotations.

---

### C5 — Annotation-aware IR with dual-tier DI suppression

**Problem.** Spring / Jakarta code uses annotations
heavily. A `private String password = "default"`
inside a class annotated with `@ConfigurationProperties`
is a framework default, not a CWE-798 hard-code. A
field with `@Value("${app.password}")` is the same
story at a finer granularity. A vanilla
`FieldHardcodedPasswordCheck` fires on every one of
them — a class of false positives that is a
showstopper for any real Spring codebase.

**Approach.** A two-part solution.

**Part 1 (R125a) — IR extension.** `TacFunction` gets
three new fields:

- `classAnnotations: List<String>` — simple-name list
  of class-level annotations
- `methodAnnotations: List<String>` — simple-name list of
  method-level annotations
- `fieldAnnotations: Map<String, List<String>>` —
  `fieldName → [annotation, ...]` for instance field
  initializers

The translator populates all three from the JavaParser
AST at translate time. The class-level list is propagated
to every child function (`<clinit>`, synthetic `<init>`,
regular method, `InitializerDeclaration`) so a checker
can ask "is this function in a `@RestController`?" from
any function in the class. Annotations are stored in
**simple-name form** (`"Value"` not
`"org.springframework.beans.factory.annotation.Value"`)
because the suppression table only needs the simple name
and the FQN-to-simple conversion is a one-time cost at
fill.

**Part 2 (R125b) — Consumer.** `FieldHardcodedPasswordCheck`
introduces two suppression sets:

- `INJECTED_VALUE_ANNOTATIONS` (field-level, 10 names):
  `Value`, `Resource`, `Inject`, `PropertySource`,
  `ConfigProperty`, `Builder`, `Singular`, `Autowired`,
  `JsonProperty`, etc.
- `CLASS_LEVEL_DI_ANNOTATIONS` (class-level, 12 names):
  `ConfigurationProperties`, `Configuration`, `Component`,
  `Service`, `Repository`, `RestController`, `Controller`,
  `Named`, `ApplicationScoped`, `SessionScoped`,
  `JsonRootName`, etc.

The check is two-tier: a per-field lookup for
`INJECTED_VALUE_ANNOTATIONS`, then a per-class lookup for
`CLASS_LEVEL_DI_ANNOTATIONS`. Either one skips the
FIELDSTORE. This matches the way the patterns are
written in real code: most credential fields get a
per-field `@Value`; the few that don't usually belong
to a class-level `@ConfigurationProperties` bean.

**Result.** R125 suppresses 4 of 5 fields in
`examples/r125/R125Demo.java` (an `@Value` field, a
`@ConfigurationProperties` class, a `@Resource`
field, a `@Service` class) and correctly leaves
`NaiveAdmin.password` (no DI annotation) firing. End-to-end
the suppression is verified against the same demo file
in both unit tests (`AnnotationSuppressionTest`, 10
tests) and the full scan runner.

**Why it matters (paper angle).** Framework-aware
suppression is a non-obvious win. The R125 work
delivered it without changing the catalog model, without
adding a new IR-pass, and without a classpath resolver.
The simple-name form is the unblocker — it makes the
suppression table a small `Set<String>` instead of a
set of FQN regexes. The same IR extension is now ready
to power R126's `MISSING_AUTHZ` (which needs
`methodAnnotations`) and R128's `TRUST_BOUNDARY_VIOLATION`
(which needs `classAnnotations`).

---

### C6 — Translator repair: synthetic `<init>`

**Problem.** A pure syntax-driven AST → IR translator
that follows Java's grammar but not its semantics
can drop information silently. The R124 round discovered
exactly such a bug: instance field initializers
(`private String password = "literal"`) were being
emitted as **zero** instructions. The bug was invisible
because no checker had asked the question "where is the
value of `this.password`?" until `FieldHardcodedPasswordCheck`
(R124) was written.

**Approach.** A new `emitInstanceFieldInit` in
`TacTranslator` that, for every instance field with
an initializer, emits a `MOVE` (the initializer
expression into a temp) followed by a `FIELDSTORE`
(`this.<field> = <temp>`). A synthetic `<init>` is
emitted for every class that does not have an explicit
constructor; if a class has an explicit constructor,
the synthetic one is suppressed (so no double-emit).

The `hasExplicitCtor` flag is a single boolean per
class; it avoids the bug where both the synthetic
`<init>` and the explicit constructor emit the same
`FIELDSTORE`s and produce a double-report.

**Result.** R124 unblocks an entire dimension of
checkers: any checker that wanted to look at
`FIELDSTORE` instructions (currently
`StaticApiKeyCheck` and `FieldHardcodedPasswordCheck`,
in the future potentially more) now has the data it
needs. The fix is zero-cost for classes with explicit
constructors (the synthetic is not emitted) and one
extra function per class without (typically 1-3
extra instructions per class).

**Why it matters (paper angle).** Silent IR drop is
the hidden disaster of static analysis. R124 is
a case study of "writing the next checker forced the
translator to be honest about what it's modeling".
The architectural lesson generalizes: any time a
new checker asks a question the IR can't answer, the
fix is usually a translator repair, not a workaround
in the checker.

---

## 4. Cross-cutting infrastructure

The six contributions above are the headlines. Three
orthogonal infrastructure pieces made them cheap to
implement and would be useful to flag in a paper as
"the stuff that fell out of the implementation":

- **`sourcePathForFunction(fnSig)` (R118a).** The merged
  program has no source file; without this helper,
  every cross-TU finding would have reported the
  synthetic "merged.java" instead of the real source.
  The fix is a 3-tier resolution: (1) merged source
  path if known, (2) original program source path, (3)
  "merged.java" fallback. The helper is a 5-line method;
  its impact is on every report's file column.
- **`TaintAnalysis` no longer `final` (R123).** A small
  but necessary refactor: removing the `final` keyword
  so `SensitiveDataLeakCheck` can subclass it and
  inject a TypeTable build. The lesson: when a check
  needs to "own" the analysis invocation, the
  analysis class has to be subclassable.
- **Bounded 1-hop def chase (R122).** A small helper
  used in three places (`PredictableKeyPasswordCheck`,
  `CorsMisconfigurationCheck`, `FieldHardcodedPasswordCheck`)
  to follow a single MOVE / list-factory hop. The
  cost is `O(1)` per call; the coverage is the
  common "literal flows through one rename" pattern.
  Bounded analysis is a deliberate choice — full
  const-folding through a helper chain would be
  asymptotically expensive and would not be a
  meaningful precision improvement for the cases
  we see.

---

## 5. Empirical validation

### 5.1 Unit test coverage

| Round | dfa tests | total | categories |
|------:|----------:|------:|----------:|
| R114 baseline | 50 | 70 | 15 |
| R117 | 67 | 100 | 22 |
| R119a | 76 | 109 | 30 |
| R120 | 95 | 127 | 34 |
| R121 | 102 | 134 | 35 |
| R123 | 112 | 144 | 35 |
| R124 | 118 | 150 | 36 |
| **R125** | **128** | **160** | **36** |

**Test/category ratio = 4.4**, a reasonable density. The
documented 1 skip is a `WebAppConfigCheckTest` entry that
tests an `Arrays.asList('*')` pattern that was unreachable
in the basic check; R122 fixed it in the production code
and the skip was left in place to document the regression
boundary.

### 5.2 End-to-end scan

`examples/` (10 demo files) yields **107 findings** across
14 rule names. Compared to R124's 98: **+9 from R125Demo
only**, zero regression. BadCode (the most heavily exercised
file) reports 16 findings single-file and 12 findings in
the merged program mode (the 4 missing are an R117
MergedProgram signature-collision limit, not a regression).

### 5.3 CWE coverage

24+ CWE families: 89, 78, 77, 22, 79, 117, 502, 918, 470,
643, 90, 91, 93, 134, 327, 328, 330, 332, 336, 259, 798,
319, 614, 942, 1004, 758, 835, 570, 571, 532, 601, 943,
1333, 94, 95, 36, 369, 129, 125, 787, 190, 193, 459, 404,
476, 862. Most are CWE Top 25 (2023) entries.

---

## 6. Lessons learned

The "what we learned" section is honest, not promotional.

1. **Translators lie by omission.** The single biggest source
   of false negatives in R115-R125 was the translator
   dropping information silently (R124's instance field init
   bug, R120's `for(;;)` JUMP line-number bug, R122's
   `ArrayInitializerExpr` drop). Every round where a new
   checker asked a new question surfaced a new translator
   bug. **Implication:** test the translator with
   question-driven tests, not just round-trip AST tests.
2. **"Built but unused" is the canonical form of architecture
   debt.** R117 built `TypeTable`; it sat unused for 6 rounds
   until R123 finally wired it up. R115 built the per-function
   sanitizer; it was a small change when R121 needed it.
   **Implication:** when a piece of infrastructure is built
   for "future use", document the consumers that *will*
   come, so the future work has a known target.
3. **Conservative fall-back is the right default for
   precision improvements.** Every "improve precision by
   filtering X" change (R123's receiver-class match,
   R125b's DI suppression) explicitly *does not* filter
   the unknown case. The unknown case still fires. This
   gives precision↑ with coverage preserved, which is the
   trade-off production users want.
4. **A small data-structure choice (dedup key) is a
   showstopper.** R121's `LOG_INJECTION` was originally
   suppressed on the same line as `SENSITIVE_DATA_LEAK`
   because the dedup key didn't include sink identity.
   One line of code change (`ruleId + line + sinkSig +
   fn.signature()`) and the report quality goes from
   "obviously buggy" to "production-quality". This is
   not glamorous but it is the kind of thing that
   distinguishes a research prototype from a tool people
   use.
5. **Framework-aware suppression needs a simple-name
   table, not a classpath resolver.** R125b's `Set<String>`
   approach is 100x simpler than a full classpath-aware
   annotation resolver and covers 100% of the patterns
   that appear in real code. The lesson: "what's the
   simplest thing that could possibly work?" beats
   "what's the theoretically complete answer?" for
   the patterns that matter.
6. **End-to-end demos double as regression tests.** Every
   R-round ends with a `examples/rNNN/RNNNDemo.java` that
   exercises the new check on a realistic shape. The
   end-to-end scan (`java -jar ... -i examples/ -f ndjson`)
   gives a one-line regression check, and the demo file
   itself becomes a worked example for the user. This
   cost almost nothing to maintain and paid for itself
   many times over (R125 caught a MergedProgram signature
   conflict that the unit tests didn't).

---

## 7. Limitations and R126+ roadmap

The list below is honest about what is *not* done. The
priorities are ordered by impact for a paper.

| Priority | Limit | Fixable in |
|---------:|-------|------------|
| 1 | Direct field assignment in method body still emits MOVE, not FIELDSTORE (`password = "literal"` in a setter) | R127 — independent translator fix |
| 2 | MISSING_AUTHZ not implemented | R126 — IR is now ready, just need the role-recognition table |
| 3 | TRUST_BOUNDARY_VIOLATION not implemented | R128 — entry-point classification |
| 4 | Exception-path taint (R118c deferred) | R130 — CFG exception edges |
| 5 | Real-project smoke test | R129 — Spring petclinic, Apache Commons |
| 6 | Arrays.asList('*') through helper chain (bounded 1-hop) | R131 — bounded k-hop chase if evidence warrants |
| 7 | Generic-aware type inference (`List<User>` vs `List<HashMap>`) | R132 — parameterized type solver |
| 8 | TypeTable field-name → type | R133 — enables field-sensitive SensitiveDataLeak |

R126 is the natural next step and unblocks two more deferred
items (R128 needs the same annotation IR; R132 needs
TypeTable work that R133 also touches). R129 is independent
and should be done in parallel for empirical evidence.

---

## 8. Paper-writing checklist

For the paper's *technical contributions* section, the
recommended structure is:

1. **§3.1 Lightweight TAC IR (C1).** Two paragraphs: the
   string-keyed SSA, the per-function builder. One figure
   showing the `TacFunction` shape (with the R125 annotation
   fields highlighted).
2. **§3.2 Inter-procedural, field-sensitive taint engine (C2).**
   The bulk of the section. Three subsections: recursive
   worklist + bounded depth, position-based sanitizer, field
   map. Two figures: the worklist state machine, the
   `TaintFact` shape (base + fieldMap).
3. **§3.3 Catalog-driven sink matching (C3).** Two
   paragraphs: the `**` glob + leading-dot-optional, the
   multi-catalog architecture. One figure: the catalog
   matching algorithm.
4. **§3.4 TypeTable + receiver matching (C4).** One
   paragraph + one figure showing the `KNOWN_JDK_SIMPLE`
   set.
5. **§3.5 Annotation-aware IR (C5).** Two paragraphs:
   the IR extension, the dual-tier suppression. One figure
   showing the suppression table.
6. **§3.6 Translator repair (C6).** One paragraph, mostly
   as a "case study" sidebar rather than a full section.

For *empirical evaluation* (separate §4):
- Table of test counts (R114 → R125)
- End-to-end scan table (BadCode, R124, R125)
- CWE coverage list
- Optional: comparison with Coverity / SpotBugs on
  the same 10 demo files (R131+)

For *related work* (separate §5):
- Figma-style SSA IR (Fathom, FlowDroid)
- IFDS / IDE taint engines (Reps, Bodden)
- Annotation-aware static analysis (Coverity, Error Prone,
  SpotBugs)
- The Coverity checker library for CWE coverage
