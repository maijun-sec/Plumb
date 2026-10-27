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

The framework of `plumb-dfa` already has a `FunctionSummary` cache mechanism:

```java
// Operation.WriteToField marks that a method writes a certain field
FunctionSummary summary = cache.get(calleeSig);
if (summary.returnOps().contains(new Operation.WriteToField("name"))) {
    // the caller now knows that the callee writes this.name
}
```

Before traversing the functions in RPO order, `DfaEngine` runs a **warm-up pass** (`warmUpMethodSummaries`) that computes the summary of every method first, so that the caller can look it up directly when it calls checkInstruction.

See `tech/CROSS-FUNCTION-ANALYSIS.md §3` for the complete mechanism (Phase 1 builds the summaries bottom-up).

### 2.2 MOVE alias propagation

```java
// MOVE dest = lhs1: if lhs1 has the from-load state, dest is marked from-load too
if (ins.kind == MOVE && ins.lhs1 instanceof TacVar
        && ins.dest instanceof TacVar) {
    if (fromLoadVars.containsKey(ins.lhs1.name())) {
        fromLoadVars.put(ins.dest.name(), true);
    }
}
```

The MOVE instruction of TAC corresponds to the pure assignment cases of Java such as `y = x` / `return x`. This is the key to field-level precision (per-variable) -- the null check of `y = x; if (y == null)` is then propagated back to the from-load state of `x`.

### 2.3 BRANCH null comparison

```java
// The lhs1 binop lhs2 of a BRANCH instruction is the if condition
if (ins.binop == BinaryOperator.EQ || ins.binop == BinaryOperator.NE) {
    // One side of lhs1 / lhs2 is a TacVar name, the other is Const.NULL
    String l = ins.lhs1.toString();   // "x:UNKNOWN"
    String r = ins.ins.lhs2.toString();   // "null"
    String lBare = stripType(l);      // "x"
    String rBare = stripType(r);      // "null"
    if (fromLoadVars.containsKey(lBare) && "null".equals(rBare)) {
        fire();  // load + null check on l (or its alias)
    }
}
```

**A common source of bugs**: `BinaryOperator.EQ.toString()` is the string `"EQ"`, **not** `"=="`. A BRANCH null checker must compare the `.equals()` of the `==` operator object, not compare strings.

### 2.4 Cross-function field tracking

Cross-function field tracking is carried by `FunctionSummary` + `Operation.WriteToField` in the DFA engine
(see [`CROSS-FUNCTION-OPERATION.md`](./CROSS-FUNCTION-OPERATION.md) §5.7):
the callee writes in its summary which fields it modified, and the `SummaryApplier` of the caller writes
these into the fact bag of the caller.

The rules `SingletonRace` and `ServletAtomicity` do **not** go down this road -- they have
been migrated to `plumb-style` and are pure AST checkers:

- `plumb-style/.../concurrency/SingletonRaceCheck.java` -- overrides
  `visit(ClassOrInterfaceDeclaration, StyleContext)`
- `plumb-style/.../concurrency/ServletAtomicityCheck.java` -- overrides
  `visit(MethodDeclaration, StyleContext)`

The corresponding DFA versions (`SingletonRaceDfaChecker` / `ServletAtomicityDfaChecker`)
are still in the source code but are **not registered** (the registration statement in
`ScanRunner.buildDfaCheckers()` is commented out), because the precision of the AST version is better.

## 3. The DFA-augmented checkers in detail

### 3.1 Fields that must not be null: AST version only

The initialisation check of `@NonNull` fields currently only exists as an AST version
(`plumb-style/.../quality/UninitNonnullCheck.java`). The scenario it cannot see is
assignment through a helper method:

```java
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

The entity returned by the JPA `EntityManager.find()` may be null (when no record is found). The AST sees the `find()` call and does not know "whether there is a null check afterwards".

**The DFA fix**:
1. A CALL to `EntityManager.find` pulls the summary of the callee and adds dest to `findResults`
2. On a BRANCH `if (x == null)` x is removed from `findResults`
3. A CALL to `x.getXxx()` / `x.setXxx()` / access of `x.field` hits an x that is **still** in `findResults` -> fire

### 3.3 -DA: OrmUnnecessaryGetDfaChecker

The JPA `entity.getXxx()` queries the DB every time, **but if the result is assigned to a local variable and used several times** -- the typical "get it and then throw it away" pattern.

**The DFA fix**: MOVE alias propagation tracks `local = entity.getXxx()` and fires on the second `entity.getXxx()` (unless the entity was modified in between).

### 3.4 -DA: OrmLostUpdateDfaChecker

The JPA `find → modify → save` pattern can lose an update under concurrency (last writer wins).

**The DFA fix**: accumulate the `find` + `save` call pairs across functions and check for each pair whether there is a read/write of an @Version field in between.

### 3.5 -DA: SingletonRaceDfaChecker

The field of a singleton is shared between several servlet requests; a non-synchronised read/write -> race condition.

**The DFA fix**:
1. The first time checkInstruction meets a FIELDSTORE, the "fields that were written" is added to `state.writtenFields`
2. The second time it meets a FIELDLOAD, it is removed from `state.writtenFields`
3. finishFunction: if `state.writtenFields != ∅` and the class is a singleton (private ctor + static getInstance) -> fire

### 3.6 -DA: ServletAtomicityDfaChecker

`getAttribute` + `setAttribute` across method calls are invisible to the AST.

**The DFA fix** (same pattern as SingletonRace):
1. Accumulate the `getAttribute` / `setAttribute` key sets across functions
2. finishFunction: if a key is get/set several times but not inside a synchronized block -> fire

## 4. Design decisions

### 4.1 AST and DFA side by side

Every checker produces **two rule ids**:
- AST version: `ast-style-uninit-nonnull-style` ( naming convention)
- DFA version: `plumb.dfa.uninit-nonnull` ( naming convention)

The user picks one of the two with the YAML `rules.enabled`:

```yaml
rules:
  enabled:
    - ast-style-uninit-nonnull-style   # fast, more false negatives
    - plumb.dfa.uninit-nonnull        # slower, fewer false negatives
```

### 4.2 The naming conventions

| Style | Naming | Example |
| --- | --- | --- |
| AST version | `ast-style-<bare>-style` | `ast-style-uninit-nonnull-style` |
| DFA version | `plumb.dfa.<bare>` | `plumb.dfa.uninit-nonnull` |
| Taint version | `plumb.taint.<bare>` | `plumb.taint.sql-injection` |

All 3 styles support a YAML match in any of these spellings (`ScanRunner.isRuleIdAllowed` normalises in both directions, see `tech/ARCHITECTURE.md §8`).

### 4.3 The choice of the key

What does FunctionSummary use as its key?
- ❌ The function name (overload conflicts)
- ❌ Class name + function name (inner class / lambda naming differences)
- ✅ **signature = "Class.method(param1,param2,...)"** ( the choice that was kept)

The concrete rules of signature normalisation:
- Inner classes use the `$` qualifier (JVM style)
- The signature of a lambda synthetic function is `lambda$N(arity)` (see
  `plumb-tac/.../translate/ExpressionTranslator.java`, `translateLambda`)
- Overload disambiguation uses the arity + the parameter types

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

Every DFA-augmented checker gets 3 kinds of tests:

| Test type | Purpose |
| --- | --- |
| **Positive sample** | Give a piece of code that can fire, verify that it fires |
| **Negative sample** | Give code that the AST version misses but the DFA version hits |
| **Regression sample** | The AST version already hits it, the DFA version does not break it |

The tests live in the corresponding packages under `plumb-dfa/src/test/java/org/plumb/dfa/checkers/`.

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

The variants of `Operation` are **not** infinitely extensible. Every newly added Operation requires:
- a sealed variant in `Operation.java`
- a builder in `MethodSummaryBuilder`
- an applier in `SummaryApplier`
- a matching test (both ends: the callee summary and its application in the caller)

**Leaving out one of the 3 steps fails silently** ——

### 8.3 Do not write a DFA for a style rule

The characteristic of a style rule: **only the AST shape is needed**. Implementing it with DFA:
- ❌ does not improve the precision (the AST is already enough)
- ❌ does not improve the recall (a style rule does not miss anything in the first place)
- ❌ increases the maintenance cost (an IR visitor is harder to debug than an AST visitor)

If you are not sure, ask yourself: *does this rule need the judgement "data flows from A to B"?* No -> AST.
