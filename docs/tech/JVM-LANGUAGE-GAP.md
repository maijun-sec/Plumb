# The JVM gap, measured: what the TAC loses, and what the rule catalogues reach

[English](../README.md) | [中文](../zh/tech/JVM-LANGUAGE-GAP.md)

> Status: measured audit of the JVM language support and the rule catalogue
> Scope: **what the three frontends lose, what that costs, and what the two catalogues reach**
> Method: every claim below is a **measured** property of this repository or of the local
> reference material, and the measurement is named.

---

## 0. Conclusion first

Three questions were asked. Here is what the measurements say.

| Question | Answer |
|---|---|
| Q1. What do the Java/Kotlin/Scala frontends lose, and does it matter? | Java loses 2 constructs silently. Kotlin loses 7, all reported. Scala loses 11, all reported — **and 40 standard-library operations that produced no usable call: 31 were reported with the wrong reason, and 9 vanished silently**. |
| Q2. What can be borrowed from the two reference implementations' DFA? | 6 ideas, of which **4 are already implemented** in Plumb. The 2 that are not are listed. |
| Q3. What is the gap against the reference catalogues? | **All 28 of their JVM defect classes have a family here.** The gap is not breadth — it is that **29 families had no watcher at all**, 12 of them produced by the rule-pack migration. |

The last row is the finding that matters. The catalogue looked complete: 81 families, thousands
of patterns, a CWE for each. Twenty-nine of them could never produce a finding, because a family
is a *pattern set* and a **checker** is what looks at it. The twelve that came from the migration
have a checker each, and one of them is verified end-to-end: a family that could not fire now
does.

---

## 1. What the TAC loses, per language

### 1.1 The measurement method

Every construct was measured the same way: run the frontend over a file that uses the construct,
then ask three questions.

1. Does the IR contain an instruction for it?
2. Does a **problem** travel with the IR saying it was not lowered?
3. Is the function's precision downgraded?

The design rule the whole system obeys is:

> A construct that is not lowered must produce a problem and a downgraded precision. Never a
> silent drop.

A **silent** gap is the worst kind, because it is indistinguishable from "the code is clean". The
coverage contract for this is [`TAC-COVERAGE.md`](TAC-COVERAGE.md).

### 1.2 Java

Java is the only one of the three with an **enforced exhaustiveness invariant**: the node
inventory test enumerates every JavaParser node class from the jar and fails if one is unmatched.
That is why Java's silent-gap count is the lowest — and it is still not zero.

| Construct | Reported? | Effect of the gap |
|---|---|---|
| `case X when <guard>` (Java 21) | **SILENT** | The guard test is absent from the IR: the arm fires on the type test alone. **False positives**, not false negatives. |
| Enum constant bodies with overridden methods | **SILENT** | The override is not in the program, so a call resolves to the base method. |

Both remaining Java gaps produce a finding that is *wrong* rather than a finding that is
*missing*. That is why they are the top of the Java backlog and not the bottom.

### 1.3 Kotlin

Kotlin has 7 known gaps and **all 7 are reported**. The frontend's main contribution is
**nullability**: a platform type is recorded as nullable, and a `!!` is recorded as an assertion.
That is the thing Kotlin's type system knows and the TAC's language-neutral IR does not.

| Construct | Reported? | Effect of the gap |
|---|---|---|
| `is`-pattern branches of a `when` | reported | The branch cannot be lowered; the function is downgraded. |
| Companion-object members | reported | The members are absent from the program. |
| Default arguments | reported | Only the declared parameter list is modelled. |
| Varargs | reported | Only the first argument is modelled. |
| `inline` / `reified` | reported | The inline body is not inlined into the caller. |
| `suspend` functions | reported | The continuation is not modelled. |
| The receiver of an extension function | reported | The receiver becomes `null`, so a call on it is a false positive. |

### 1.4 Scala — the library-level gap

Scala has the largest gap list, and it is the language where a gap is most likely to destroy a
taint flow. All 11 construct-level gaps are reported. But the **library-level** gap was much
worse, and it was invisible until it was measured.

**The measurement.** A probe ran the Scala frontend over 84 standard-library String operations
and recorded, for each one: the instructions produced, and the problem reported. The result:

| Outcome | Before | After |
|---|---|---|
| Lowered to a named CALL | 44 | 82 |
| Dropped, and reported as unsupported | 31 | 0 |
| Dropped **silently** — no problem at all | 9 | 0 |
| Produced a CALL with an `<unknown>` name | 9 | 0 |

The nine silent ones are the worst of the set: the operation vanished and nothing said so. They
were the calls with a default argument (`sortBy`, `groupBy`, `foreach`, `reduce`, `foldLeft`,
`diff`, `intersect`, `union`, `zip`), whose emitted key was
`scala.collection.StringOps.<unknown>/1` — a key that matches no sink pattern and no semantics
table, so the call was invisible to every rule. The two operations still dropped after the fix
are `substringBefore` and `substringAfter`, which turned out not to be Scala methods at all: the
probe was wrong about them, and the parse problem it reports is the correct behaviour.

**Why the operations were dropped.** A Scala `s.trim` is not an `Apply` — there are no arguments to
apply — so dotc represents it as a `Select`. The frontend treated every method selection as either
a field read or an unlowerable "method reference used as a value", and a parameterless call on a
value fell into the second case. Three things went wrong at once:

1. The call produced **no instruction**, so the operation's effect on taint was unknown.
2. It was reported as an unsupported construct with a **wrong reason** — `s.trim` is a call, not a
   reference — so an ordinary file was flagged as degraded.
3. The operation could never reach a rule: no callee key meant no sink pattern matched and no
   semantics table entry applied.

Two more defects were found by the same probe and fixed in the same round:

- A **character literal aborted the whole function**. A Scala `Char` is boxed as
  `java.lang.Character`, which is not a `java.lang.Number`; the literal lowering cast it to
  `Number`, the cast threw, and the throw propagated out and killed every instruction after it.
  A file containing a single `'x'` lost the rest of its body.
- A **call with a default argument** (`s.sortBy(f)`, where `sortBy` also takes an implicit
  `Ordering`) is an `Apply` whose function is another `Apply`. The wrapper hid the method's name,
  so the emitted key was `scala.collection.StringOps.<unknown>/1` — a key that matches no sink
  pattern and no semantics table.

**The measured owners.** The fix required knowing what the frontend actually emits, and the
answer is not what the source text suggests. A Scala `String` *is* a `java.lang.String`:

| Source | Emitted callee key |
|---|---|
| `s.trim` | `java.lang.String.trim/0` |
| `s.substring(1)` | `java.lang.String.substring/1` |
| `s.take(2)` | `scala.collection.StringOps.take/1` (preceded by `scala.Predef$.augmentString/1`) |
| `s"text=$x"` | `scala.StringContext.s/1` |

A semantics table written from the source text alone would have named owners that never appear in
the IR, and every entry would have been dead.

### 1.5 What the loss actually costs

| Gap class | Cost | Is it a false negative or a false positive? |
|---|---|---|
| A dropped operation | The taint flow stops at the operation | False negative |
| A dropped guard (Java 21 `when`) | The arm fires when it should not | False positive |
| A missing receiver (Kotlin extension) | A call on `null` | False positive |
| A dropped capture (Scala, now fixed) | The taint stops at the capture | False negative |

The distinction matters because the two directions have opposite fixes. A false negative needs
the construct lowered; a false positive needs the construct *narrowed*. A tool that lowers
everything aggressively trades the first kind of error for the second.

---

## 2. What the two reference implementations do, and what was borrowed

### 2.1 Their approach to the same problem

Both solve the language-loss problem the same way, and it is the way Plumb now uses too: **the
language frontend lowers to a language-neutral IR, and the language-specific knowledge lives in
data, not in the analysis engine.**

| | Their approach | Plumb's approach |
|---|---|---|
| Frontend output | A language-neutral IR (a mirrored AST for one, a MIR for the other) | The unified TAC |
| Sink knowledge | A pattern catalogue keyed by a **mangled signature** | `sink-families.yaml`, keyed by `owner.method/arity` |
| Per-language differences | Per-language pattern tables inside the same family | `by_language:` inside the same family |
| Language-specific semantics | A primitive library: named source/sink/effect primitives | `LanguageSemanticsRegistry`: per-language callee behaviour |

**The observation that shaped Plumb's design.** Both catalogues key their sinks on a *mangled
signature* — `java.lang.String.trim()Ljava/lang/String;`, or the mangled form of a Kotlin
function. That is the same decision Plumb made: the sink is identified by the **resolved
signature**, not by the receiver's syntactic type. It is why a Scala call to a Java method reaches
the same family a Java call reaches.

**The thing neither does.** Both key their catalogues on the *mangled* signature, which is
specific to one compiler's mangling scheme. Plumb keys on `owner.method/arity`, which is the
same information without the compiler's private encoding. The cost of that choice is real and
is stated in [`ENGINE-GAP-ANALYSIS.md`](ENGINE-GAP-ANALYSIS.md): overloads that differ only in
parameter types are indistinguishable.

### 2.2 The ideas that were borrowed

Six ideas from the two implementations' DFA were assessed against Plumb's engine. Four are
already implemented; two are not.

| Idea | Status in Plumb | Where |
|---|---|---|
| **Language-neutral IR + per-language pattern tables** | Implemented | `sink-families.yaml`'s `by_language:`; `LanguageSemanticsRegistry` |
| **Sink identified by resolved signature, not syntactic type** | Implemented | `owner.method/arity`; the Scala and Kotlin owner-resolution fixes |
| **Shared, language-agnostic primitive vocabulary** | Implemented | `Operation` (the summary algebra) + `LanguageSemantics` (the callee behaviour) |
| **Explicit "unknown" / black-box semantics** | Implemented | `TaintBehaviour.UNKNOWN` and `BLACK_BOX`; `Operation.HasUnmodelledBehavior`; `Operation.UnmodelledCall` |
| **Per-checker visibility of the analysis budget** | Implemented | `AnalysisBudget`; `AnalysisCompleteness`; the SARIF `executionSuccessful` flag |
| **Call-string context sensitivity (k-CFA)** | **Not implemented** | `context_depth: 0` disables cross-function folding; a positive value keeps the current recursive folding. This is the honest state, and the setting says so. |

The sixth is the one to be careful about. A `context_depth: 2` in the configuration does **not**
mean 2-CFA; it means "keep folding across function boundaries". Documenting it as k-CFA would be
a false promise, which is the specific failure mode
[`ENGINE-GAP-ANALYSIS.md`](ENGINE-GAP-ANALYSIS.md) was written to end.

### 2.3 The one structural difference worth naming

The reference implementations' primitive libraries name their knowledge in a **vocabulary**: named
sources, named sinks, named effects. Plumb's equivalent is split in two — `Operation` describes
what a *function* does (to its parameters, to its return value), and `LanguageSemantics` describes
what a *library call* does to taint.

The split is deliberate and it is what made the Scala defect findable. A primitive
vocabulary would have named "a call that strips whitespace" as one thing; Plumb had to say "a call
to `java.lang.String.trim` preserves taint", which is a fact about a *resolved signature* — and
that is a fact a frontend can be **wrong** about. And it was wrong, for 40 operations, until the
probe measured it.

---

## 3. The catalogue gap: the families nobody was watching

### 3.1 How the gap was found

The catalogue's breadth was never the question — it was measured and it is large. The question
was **reachability**: which families can actually produce a finding?

The method was mechanical:

1. Take every family key in `sink-families.yaml` (81).
2. Take every family named by a checker's rule, plus every family a dedicated checker names (52).
3. The difference (29) is the unreachable set.
4. Confirm it: search the whole tree for each of the 29 keys, outside the catalogue itself.

Step 4 returned nothing for all 29. Every one of them was referenced by no rule, no checker and
no test. They were **dead configuration**: real patterns, real CWEs, and no way to fire.

### 3.2 The part that came from the rule-pack migration

Of the 29, **12 came from the rule-pack migration**. Their patterns were converted from a real
rule pack, so the symbols are real and the CWEs are real — but the checker that should watch
each family was never written. The migration moved the *pattern* and forgot the *rule*.

| Family | CWE | Watcher (now) |
|---|---|---|
| `code-injection.sinks` | 94/95/494 | `CodeInjectionChecker` |
| `cookie.sinks` | 113 | `CookieInjectionChecker` |
| `credential.sinks` | 256/798 | `CredentialExposureChecker` |
| `dos.sinks` | 730/776 | `DenialOfServiceChecker` |
| `formula-injection.sinks` | 1236 | `FormulaInjectionChecker` |
| `header-injection.sinks` | 93/113 | `HeaderInjectionChecker` |
| `insecure-comms.sinks` | 297/319/326/327 | `InsecureCommunicationChecker` |
| `insecure-random.sinks` | 335/330 | `InsecureRandomFamilyChecker` |
| `log-injection.sinks` | 117 | `LogInjectionFamilyChecker` |
| `privacy.sinks` | 215/359/489/497 | `PrivacyViolationChecker` |
| `resource-injection.sinks` | 94/95/99/502 | `ResourceInjectionChecker` |
| `setting-manipulation.sinks` | 15 | `SettingManipulationChecker` |

**The remaining 17 are not migration debt.** They were already dead before the migration, and
they are a different kind of work: several name APIs that no longer exist (`atg.*`, `flex.*`,
`oracle.jbo.*` — vendor products that are not on the classpath of anything analysed here), and
several overlap a family that already has a watcher. They are listed in §3.4.

### 3.3 The verification

Reachability is now a **test**, not a claim:

- `SinkFamilyReachabilityTest` asserts each of the twelve families has a watcher, that its
  patterns still match the API they name, that it does not collide with a family that already had
  a checker, and that its checker is registered in `checkers.yaml`.
- The APIs in the test were **measured** with the registry itself, not read from the pattern
  text: a pattern's text and the API it matches are two different things.
- End-to-end: a fixture with `engine.eval(userInput)` now produces
  `Tainted value flows to code-injection.sinks (CWE-94,CWE-95,CWE-494) at userInput via
  ScriptEngine.eval/1`. A family with no watcher cannot fire at all, whatever its patterns say.

### 3.4 What is still open

| Item | Status | Note |
|---|---|---|
| 17 families with no watcher | **open** | Predate the migration. Several name APIs that no longer exist; several overlap an existing family. Each needs a decision: write a watcher, merge it into a family that has one, or delete it. |
| `context_depth` as real k-CFA | **open** | The setting is consumed (0 disables folding) but is not k-CFA. |
| Path-sensitive reachability | **partial** | The arithmetic contradiction check is in; the general path condition is not. |
| Object-sensitive analysis | **open** | The points-to propagation exists but has no consumer. |
| Scala: varargs, try/catch, by-name parameters, implicits, tuple destructuring, match guards | **open** | All reported. See [`TAC-COVERAGE.md`](TAC-COVERAGE.md). |

---

## 4. The Scala semantics table

Scala has a semantics table of 80 entries. Without one, every Scala callee would be `UNKNOWN` and
the engine's conservative default would make every Scala call taint-preserving — including
`s.length` and `s.hashCode`, whose whole purpose is to *remove* taint. A rule set that fires on
`length` stops being usable.

The table now has 80 entries, every owner measured against the frontend's real output. The
entries are the ones the engine's correctness rests on: the operations that carry taint through
(`trim`, `substring`, `concat`, `split`, `take`, `drop`, `stripPrefix`) and the operations that
remove it (`length`, `hashCode`, `isEmpty`, `contains`, `startsWith`, `endsWith`, `indexOf`,
`compareTo`, `matches`, `getBytes`, `toCharArray`, the numeric parsers).

One trap worth naming, because it is the kind that produces a table that *looks* right and
changes nothing: **Scala 3 emits these operations as static extension methods** (`toInt$extension`),
but the frontend resolves them to the instance method the compiler ultimately calls, so **the
emitted key carries no `$extension`**. A table written against the library's own method names
would match nothing at all. The test
`LanguageSemanticsTest.scalaTableNamesTheResolvedOwner` guards exactly this.

---

## 5. The rule that keeps this honest

Two rules, both learned from the defects in this document:

1. **A family with no watcher is dead configuration.** A catalogue is not a capability; a
   capability is a family *plus* a checker that watches it. The coverage report must count
   reachable families, not configured ones.

2. **A table's entries must be measured against the IR, not read from the source text.** The
   Scala owner table is the worked example: most of its first 80 entries would have been dead
   because the resolved owner is not the one the source suggests.

Both are now tests rather than conventions, which is the only part that survives a year.
