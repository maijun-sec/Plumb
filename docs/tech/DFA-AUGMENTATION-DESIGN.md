# DFA Augmentation Design: Why the AST Is Not Enough and How to Fill the Gap

[English](../README.md) | [中文](../zh/tech/DFA-AUGMENTATION-DESIGN.md)

This document explains the engineering trade-offs of adding **DFA-augmented versions** of 5 Coverity Java checkers to Plumb -- why the AST versions produce false negatives, how the DFA versions eliminate them, and how the 4 general-purpose DFA augmentation techniques are used.

> Companions: `tech/CROSS-FUNCTION-ANALYSIS.md` (the full mechanism of the cross-function summary), `tech/ARCHITECTURE.md §3.0` (the DFA engine SPI), `rules/RULE-TOOL-COMPARISON.md §1.2` (DFA vs AST trade-offs)

## 1. Background: why the AST is not enough

- The checkers of this series already catch most cases at the AST level. But the view of the AST is naturally limited to a **single method**:

| False-negative scenario | Limitation of the AST | How the DFA fixes it |
| --- | --- | --- |
| **Helper method call chain** | `this.foo()` assigns the field f, the AST does not see it | Pull the `FunctionSummary` of `foo()` and read `Operation.WriteToField("f")` |
| **Ctor chaining** | `this(other)` calls another ctor, the AST has trouble tracking the field initialisation of the other ctor | Follow the summary of the other ctor and union the field sets |
| **Aliasing** | After `y = x` comes `if (y == null)`, the AST only sees the null check at `x` | The MOVE instruction propagates the from-load state |
| **Cross-method field access** | The servlet's `getAttribute("k")` is in method A, `setAttribute("k", v)` is in method B | Accumulate the getAttribute/setAttribute calls across functions |

The commercial Coverity engine implements its "ground truth" with DFA. This batch adds 5 DFA-augmented versions in plumb-dfa, **alongside** the AST versions (under different rule ids) -- giving the user **two choices**:
- Want **recall first**: use the DFA version (trading a little performance for fewer false negatives)
- Want **performance first**: use the AST version (more false negatives but much faster)

## 2. The 4 general-purpose DFA augmentation techniques

### 2.1 FunctionSummary (the most commonly used)

The framework of `plumb-dfa` has two summary types, and they answer different questions:

| Type | Holds | Answers |
| --- | --- | --- |
| `FunctionSummary` | `Operation` variants, plus `StringOp` / `FieldOp` lists | "what does the callee do to my data?" |
| `MethodSummary` | a `ReturnShape` and a `SideEffect` | "what does the callee return, and what does it invalidate?" |

`FunctionSummary` is what the `Operation` framework produces:

```java
// Operation.WriteToField marks that a method writes a certain field
FunctionSummary summary = functionSummaries.get(calleeSig);
if (summary.returnOps().contains(new Operation.WriteToField("name"))) {
    // the caller now knows that the callee writes this.name
}
```

`FunctionSummary.returnOps()` returns the operations recorded on the **return** value, and
`paramOpsAt(i)` the ones recorded on formal parameter `i`. A caller projects only what it
cares about with `project(predicate)`.

`MethodSummary` is what the `MethodSummaryCache` holds, and it is keyed two ways:
`get(FunctionSignature)` for a resolved signature, and `getByCanonicalString(String)` for the
canonical spelling. The cache is **not** keyed by a raw signature string -- there is no
`cache.get(calleeSig)`; the string form goes through `getByCanonicalString`.

Before traversing the functions in RPO order, `DfaEngine` runs a **warm-up pass** (`warmUpMethodSummaries`) that computes the summary of every method first, so that the caller can look it up directly when it calls checkInstruction.

See `tech/CROSS-FUNCTION-ANALYSIS.md §3` for the complete mechanism (Phase 1 builds the summaries bottom-up).

### 2.2 MOVE alias propagation

```java
// MOVE dest = lhs1: if lhs1 has the from-load state, dest is marked from-load too
if (ins.kind == TacInstruction.Kind.MOVE
    && ins.lhs1 != null && ins.lhs1 instanceof TacOperand.TacVar
    && ins.dest != null && ins.dest instanceof TacOperand.TacVar) {
    String from = ((TacOperand.TacVar) ins.lhs1).name();
    String to = ((TacOperand.TacVar) ins.dest).name();
    if (fromLoadVars.containsKey(from)) {
        fromLoadVars.put(to, true);
        loadCallSite.put(to, loadCallSite.get(from));
    }
}
```

`TacOperand` is a sealed interface and `TacVar` is one of its nested implementations, so the
cast is `TacOperand.TacVar` and the name comes from `TacVar.name()`. The null checks on
`lhs1` and `dest` are not optional: a MOVE can carry a non-variable operand.

The MOVE instruction of TAC corresponds to the pure assignment cases of Java such as `y = x` / `return x`. This is the key to field-level precision (per-variable) -- the null check of `y = x; if (y == null)` is then propagated back to the from-load state of `x`.

### 2.3 BRANCH null comparison

```java
// The lhs1 binop lhs2 of a BRANCH instruction is the if condition
if (ins.kind == TacInstruction.Kind.BRANCH && ins.binop != null
        && (ins.binop == BinaryOperator.EQ || ins.binop == BinaryOperator.NE)) {
    // One side of lhs1 / lhs2 is a variable name, the other is the
    // null literal
    String lBare = bareName(ins.lhs1.toString());   // "x"
    String rBare = bareName(ins.lhs2.toString());   // "null"
    // Pick the side that is a tracked variable; the other side must be
    // the null literal.
    String varSide = fromLoadVars.containsKey(lBare) ? lBare
        : (fromLoadVars.containsKey(rBare) ? rBare : null);
    String otherBare = varSide == lBare ? rBare : lBare;
    if (varSide != null && "null".equals(otherBare)) {
        fire();  // load + null check on l (or its alias)
    }
}
```

The bare-name extraction is a per-checker helper: the operand's `toString()` carries the
declared type as well as the name (`"x:UNKNOWN"`), and the checker has to strip it. There is
no shared `stripType` helper in the engine, and there is no `Const.NULL` -- the null literal
is matched as the text `"null"`. `OrmLoadNullCheckDfaChecker` is the reference implementation.

**A common source of bugs**: `BinaryOperator.EQ.toString()` is the string `"EQ"`, **not** `"=="`. A BRANCH null checker must compare the `.equals()` of the `==` operator object, not compare strings.

### 2.4 Cross-function field tracking

Cross-function field tracking is carried by `MethodSummary` + `Operation.WriteToField` in the DFA
engine (the operation table is in
[`CROSS-FUNCTION-ANALYSIS.md`](./CROSS-FUNCTION-ANALYSIS.md) §3, the side-effect half is in
[`CROSS-FUNCTION-OPERATION.md`](./CROSS-FUNCTION-OPERATION.md) §5.7):
the callee writes in its summary which fields it modified, and the caller-side machinery writes
these into the caller's fact bag.

The rules `SingletonRace` and `ServletAtomicity` do **not** go down this road -- they have
been migrated to `plumb-style` and are pure AST checkers:

- `plumb-style/.../concurrency/SingletonRaceCheck.java` -- overrides
  `visit(ClassOrInterfaceDeclaration, StyleContext)`
- `plumb-style/.../concurrency/ServletAtomicityCheck.java` -- overrides
  `visit(MethodDeclaration, StyleContext)`

The corresponding DFA versions (`SingletonRaceDfaChecker` / `ServletAtomicityDfaChecker`)
are still in the source code but are **not registered**: in `ScanRunner.buildDfaCheckers()`
the `config.runServletAtomicity` and `config.runSingletonRace` branches are deliberately
**empty**, with a comment explaining that the `plumb-style` AST equivalents cover the same
CWE with better precision. The DFA checkers remain in the source tree as a reference
implementation.

## 3. The DFA-augmented checkers in detail

### 3.1 Fields that must not be null: AST version only

The initialisation check of `@NonNull` fields currently only exists as an AST version
(`plumb-style/.../quality/UninitNonnullCheck.java`). The scenario it cannot see is
assignment through a helper method:

```java
// illustrative: this is the USER code the rule looks for, not engine code
@Entity
class User {
    @NonNull private final String name;
    @NonNull private String email;

    User(String name) {
        this.name = name;
        this.email = setEmail();   // ← helper method assignment
    }

    private String setEmail() {
        return "default@example.com";
    }
}
```

The AST sees `setEmail()` and does not know that it writes the field email. To cover this scenario, the `initFunction` of the ctor has to collect the `@NonNull` fields as the required set and walk all the instructions of the ctor
(`FIELDSTORE` marks a field as written; `CALL` pulls the `FunctionSummary.returnOps()` of the callee, and
`WriteToField(f)` marks f as written; `this(other)` follows the summary of the other ctor and takes the union);
finally, in `finishFunction` it fires when the required set minus the written set is not empty.

The DFA side does not have this rule yet, so 3.2 to 3.6 below are the implemented ones.

### 3.2 -DA: OrmLoadNullCheckDfaChecker

The entity returned by a JPA `find()` may be null (when no record is found). The AST sees the
`find()` call and does not know "whether there is a null check afterwards".

**The DFA fix** (the checker keeps `fromLoadVars` and `loadCallSite` per function -- not a
single `findResults` set):
1. A CALL to a load method marks the destination variable as "from-load" and records the
   originating CALL instruction
2. On a BRANCH `if (x == null)`, x is **not** removed from `fromLoadVars` -- the check marks
   the *null check* as the finding site and then reports the load
3. A CALL on a variable that is **still** in `fromLoadVars`, or a FIELDLOAD of it -> fire

### 3.3 -DA: OrmUnnecessaryGetDfaChecker

The JPA `entity.getXxx()` queries the DB every time. The "get it and then throw it away"
pattern -- the result is loaded and its contents are never used -- is a query with no purpose.

**The DFA fix** (the checker keeps `fromGetVars` and `getCallSite` per function):
1. A CALL to `get` / `find` / `findById` / `getById` / `read` marks the destination variable
   as "from-get" and records the originating CALL
2. The mark is propagated through MOVE, so an alias is tracked too
3. A "from-get" variable that is never read in the function is unnecessary -> fire on the
   original call site
4. A "from-get" variable that is only passed as an argument to other calls -- no FIELDLOAD on
   it, no call on it as the receiver, no assignment of its contents to a field -- is also
   unnecessary -> fire

### 3.4 -DA: OrmLostUpdateDfaChecker

The JPA `find → modify → save` pattern can lose an update under concurrency (last writer wins).

**The DFA fix**: the summary records which parameter a callee persists
(`Operation.PersistsParam`), so the caller can see that the entity it loaded is the one that
gets written back -- and that the load and the persist are separated by the mutation.

### 3.5 -DA: SingletonRaceDfaChecker

The field of a singleton is shared between several servlet requests; a read in one method and a
write in another -> race condition.

**The DFA fix** (the checker keeps two maps, `fieldReaders` and `fieldWriters` -- not a
`state.writtenFields` set):
1. `initFunction` records the class, its non-final / non-volatile fields, and the set of method
   signatures in the class
2. `checkInstruction` records a **reader** for every FIELDLOAD on `this` and a **writer** for
   every FIELDSTORE on `this` (a compound assignment counts as both)
3. `finish`: for each `(class, field)` that has at least one reader and at least one writer,
   and where the reader and the writer are **different methods**, fire once on the field

The checker deliberately does **not** verify synchronisation -- the AST version does that for
in-method accesses. The DFA version is purely the "is this field touched from more than one
method" signal, which the AST cannot see.

### 3.6 -DA: ServletAtomicityDfaChecker

`getAttribute` + `setAttribute` across method calls are invisible to the AST.

**The DFA fix** (same shape as SingletonRace): keep a `getSites` map and a `setSites` map from
the attribute key to the set of method signatures that touched it, and fire at `finish` when
the same key is accessed from more than one method. The key has to be a **string literal** --
a runtime key cannot be analysed statically, so the checker ignores it. As with SingletonRace,
the synchronisation analysis belongs to the AST version.

## 4. Design decisions

### 4.1 AST and DFA side by side

The `@NonNull` field-initialisation rule currently exists only on the AST side. Its canonical
rule id is **`ast-quality-uninit-nonnull`**, and the bare name `uninit-nonnull` is accepted as
an alias. There is no DFA version of it yet (§3.1), so this rule is the exception rather than
the rule: a checker that has both sides emits **two distinct rule ids**, and the user picks
between them.

For a checker that has both sides, the switch is the `checks.<rule>: {enabled: true|false}`
section of the YAML, or the `--rule <id>` CLI flag. The `rules.enabled` list exists in the
shipped `default-config.yaml`, but it is **commented out** -- it is an example of a force-enable
list, not the switch the engine reads:

```yaml
checks:
  ormLoadNullCheck: {enabled: true, aiDedup: true}   # the DFA side
```

### 4.2 The naming conventions

| Side | Naming | Example |
| --- | --- | --- |
| AST | `ast-<category>-<bare>` | `ast-quality-uninit-nonnull` |
| DFA, structural | `dfa-<category>-<bare>` | `dfa-controlflow-deadlock-ab-ba` |
| DFA, taint | `dfa-taint-<bare>` | `dfa-taint-sql-injection` |

The AST rule id has **no** trailing `-style`. `style` is one of the nine AST categories
(`ast-style-...`, `ast-quality-...`, `ast-security-...`, `ast-error-...`, `ast-bug-...`,
`ast-design-...`, `ast-naming-...`, `ast-performance-...`, `ast-concurrency-...`), not a suffix
on every id.

The DFA ids are per-checker constants, so they do not always follow the shape. The four ORM
checkers and the two race checkers emit a **bare** id of their own, without the `dfa-` prefix:
`orm-load-null-check-dfa`, `orm-unnecessary-get-dfa`, `orm-lost-update-dfa`,
`null-deref-from-return-dfa` (this one is `dfa-taint-null-deref-from-return`),
`singleton-race-dfa` and `servlet-atomicity-dfa`.

The `plumb.<...>` namespace is a **different** thing: it names *fact kinds*
(`plumb.nullable.may-be-null`, `plumb.resource.free-sink`), not rule ids.

All of these spellings are accepted by a YAML match: `ScanRunner.isRuleIdAllowed` normalises
in both directions (see `tech/ARCHITECTURE.md §8`).

### 4.3 The choice of the key

What does the summary cache use as its key?
- ❌ The function name (overload conflicts)
- ❌ Class name + function name (inner class / lambda naming differences)
- ✅ **a `FunctionSignature`**, whose canonical string form is
  `"package.Class#method(paramType,...)"` -- the choice that was kept

The canonical form is built by `FunctionSignature.canonical()`, which lowercases the whole
string so it is stable across runs. `toString()` is the same shape with the original casing.
The concrete rules of the signature:
- the class and the method are separated by **`#`**, not `.`
- the parameter types are comma-separated with no spaces
- the return type, when known, is appended after a `:` -- `"pkg.C#m(int):void"`
- a lambda's synthetic function is named `lambda$N` and its signature is `lambda$N(arity)`
  (see `plumb-tac/.../translate/ExpressionTranslator.java`, `translateLambda`)

`MethodSummaryCache` accepts the key two ways: `get(FunctionSignature)` for a resolved
signature, and `getByCanonicalString(String)` for the canonical spelling.

### 4.4 The criterion for AST vs DFA

**If any of the following conditions holds, implement it with DFA**:
1. **Cross-method data flow** is needed (helper method chain / ctor chaining)
2. **Field-level precision** is needed (aliasing / MOVE propagation)
3. **Typed aliases** are needed (the "several instances of the same type" scenarios such as cookie / socket / connection)
4. **Path sensitivity** is needed (accessing x after `if (x == null)`)

**Otherwise implement it with the AST**:
1. Only the shape of the method body matters (`if (x = y)` assignment-in-operand)
2. Pure naming / formatting / comments (style rule)
3. Simple type checks (`catch (Exception)` catching a generic)
4. The caller does not matter (a regex looking for `System.out.println`)

This criterion is this document's own: the two lists above are what
decides whether a checker belongs to the AST side or the DFA side.

## 5. Testing strategy

A DFA-augmented checker needs three kinds of test coverage:

| Test type | Purpose |
| --- | --- |
| **Positive sample** | Give a piece of code that can fire, verify that it fires |
| **Negative sample** | Give code that the AST version misses but the DFA version hits |
| **Regression sample** | The AST version already hits it, the DFA version does not break it |

The tests live under `plumb-dfa/src/test/java/org/plumb/dfa/`, in the package that matches the
checker: `checkers/taint/`, `checkers/boundary/`, `checkers/controlflow/` and `taint/`. Not
every checker has a dedicated test class -- the behaviour of several of them is covered
through the shared engine tests (the alias-guard matrix, the null-fact domain, the v2 trace
verification) rather than one file per checker.

## 6. The default switches of the rules

Whether the AST version and the DFA version are on or off by default is controlled by the
`checks.<rule>: {enabled: true|false}` section of the YAML
(the DFA side also has the entries of the same name in `plumb-dfa/src/main/resources/checkers.yaml`).

A Coverity-style `aggressiveness` knob (`--aggressiveness=low|medium|high`) has already been
deprecated: the CLI has no such flag, and an `aggressiveness:` in the YAML is ignored by `ConfigLoader`, which prints a warning to
stderr and lets the scan continue as usual. Use the `checks.<rule>: {enabled: true|false,
aiDedup: true|false}` section instead.

## 7. DFA augmentations that are not implemented yet

The DFA augmentations that already exist: the four of the ORM (`OrmLoadNullCheckDfaChecker`,
`OrmUnnecessaryGetDfaChecker`, `OrmLostUpdateDfaChecker`, `NullDerefFromReturnDfaChecker`),
as well as `SingletonRaceDfaChecker` / `ServletAtomicityDfaChecker` (these two have an AST version with
better precision, so the DFA version stays in the source code but is not registered).

Those without a corresponding implementation yet:

| DFA augmentation | Scenario |
| --- | --- |
| `ORM_LAZY_INIT` | Hibernate lazy loading + cross-method |
| `CONCURRENT_ITERATION` | Cross-method collection iteration |
| `THREAD_LOCAL_LEAK` | Strongly related to servlets |

### 7.1 Z3 integration

The "single Z3 query" of a DFA augmentation (the per-finding verification of Phase 2) is described in `tech/CROSS-FUNCTION-OPERATION.md` §5.1 (Operation.SolverZ3Predicate).

## 8. Lessons learned

### 8.1 The migration path from AST to DFA

Do not try to rewrite an AST checker into a DFA checker all at once. The recommended path:

1. **Keep the AST version** (under a different rule id) to guarantee the basic recall
2. **Add the DFA version** as reinforcement, focused on the concrete scenarios the AST misses
3. Use one minimal per-CWE YAML set to let the user choose between the two
4. In the long run, once the DFA recall has caught up with the AST plus one order of magnitude, drop the AST version

### 8.2 The boundary of FunctionSummary

`Operation` is a **sealed** interface with ten variants, and the variants are **not**
infinitely extensible. A new variant needs:

- a sealed variant in `Operation.java` -- and it has to be added to the `permits` clause, or
  the interface does not compile
- a producer: the place that records the operation into the summary
- a consumer: the checker that looks for it when it sees a CALL
- a matching test (both ends: the callee summary and its application in the caller)

**Leaving out the consumer fails silently** -- the operation is recorded, and nothing ever
reads it. The `SummaryApplier` does **not** dispatch on `Operation` variants: it applies the
summary's *return-value shape* and *side-effect record* as facts. The variants are consumed by
the individual checkers, each of which projects only the operations it cares about (a taint
checker looks for `Operation.TaintPropagate`, a SQL-injection checker for `Operation.Sink` with
the matching `familyKey`).

Two variants are currently only declared and never produced: `Operation.UnmodelledCall` and
`Operation.PointsToPropagate`. They are part of the sealed interface but no code path creates
them, so they are inert today.

### 8.3 Do not write a DFA for a style rule

The characteristic of a style rule: **only the AST shape is needed**. Implementing it with DFA:
- ❌ does not improve the precision (the AST is already enough)
- ❌ does not improve the recall (a style rule does not miss anything in the first place)
- ❌ increases the maintenance cost (an IR visitor is harder to debug than an AST visitor)

If you are not sure, ask yourself: *does this rule need the judgement "data flows from A to B"?* No -> AST.
