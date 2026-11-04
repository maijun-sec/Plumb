# TAC frontend coverage — what is lowered, and what is not

[English](../README.md) | [中文](../zh/tech/TAC-COVERAGE.md)

**This document is the coverage contract.** It says which source constructs the three TAC
translators lower, which they do not, and -- the part that matters most -- whether a gap is
**reported** (a problem travels with the IR and the function's precision is downgraded) or
**silent** (the construct is dropped and nothing says so).

A silent gap is the worst kind, because it is indistinguishable from "the code is clean". A
reported gap is a visible limitation: the consumer knows the finding set is a lower bound for
that function.

The rule the whole design obeys:

> **A construct that is not lowered must produce a problem and a downgraded precision.** Never a
> silent drop.

---

## 1. The three mechanisms

The three languages use three different mechanisms, and they are not equivalent.

| Language | Mechanism | Where |
|---|---|---|
| Java | `TacBuilder.reportUnhandled` → a structured `UNHANDLED_NODE` problem on the IR; the fallback value is a `HAVOC` temp, never a `null` constant | `plumb-tac/.../translate/TacBuilder.java`, `ExpressionTranslator.java` |
| Kotlin | `KotlinTacTranslator.report(...)` → both a plain string and a structured `FunctionProblem`; sets `degraded = true` | `plumb-frontend/.../kotlin/KotlinTacTranslator.java` |
| Scala | a `ProblemCollector` that writes BOTH forms at once | `plumb-frontend/.../scala/ScalaTacTranslator.java` |

The Scala collector is new. It used to be a plain `List<String>`, and the frontend handed the
PHASE's structured problems to the IR — while the phase only ever had the outer
`TRANSLATION_ERROR`. So a Scala function that was only partly lowered produced findings that
looked exactly as complete as a fully lowered one, which is the failure mode the whole
`SOURCE_LOWERED` mechanism exists to prevent.

**The invariant now:** there is exactly ONE place a gap is recorded per language, and it writes
both forms. The two cannot drift apart, because there is no second place to drift to.

---

## 2. Java

Java is the only one of the three with an **enforced exhaustiveness invariant**:
`AstNodeInventoryTest` enumerates every JavaParser node class from the jar and fails if one is
unmatched. That is why Java's silent-gap count is the lowest.

### 2.1 Lowered

Literals (all primitive forms, `String`, `null`) · text blocks (Java 15+) · class literals ·
all 20 binary operators · all unary operators · assignment and compound assignment · name, field
and static-field access · `this` / qualified `this` / `super` · casts · `instanceof` plain and
**with pattern** (Java 16+) · method calls · object creation · array creation, initialiser and
access · conditional / ternary · method references (`String::length`, `Type::new`, `expr::method`)
· lambdas (→ synthetic function + CALL) · `switch` statement (classic and pattern labels) ·
`switch` expression (Java 14+, arrow + `yield` + block arms) · `case null` and multi-label arms ·
record patterns including nested ones · `if`/`else` · `while` · `do-while` · C-style `for` ·
for-each · `try`/`catch`/`finally` · `throw` · `return` · `break`/`continue` ·
enhanced `switch` with `yield` · sealed-type `permits` · `module-info` (declaration only).

### 2.2 Known gaps

| Construct | Reported? | Note |
|---|---|---|
| `case X when <guard>` (Java 21) | **SILENT** | The guard test is absent from the IR entirely: the arm fires on the type test alone. The severity is a false positive, not a false negative. |
| Enum constant bodies with overridden methods | **SILENT** | The override is not in the program, so a call to it resolves to the base method. |
| `module-info` service declarations | reported | Declaration-only lowering. |
| `permits` / sealed hierarchy | reported | The hierarchy is not modelled. |
| `var` in a record pattern | reported | The type is not inferred for the binding. |

**Severity note.** The first two are the ones that matter: both can produce a finding that is
wrong, rather than a finding that is missing. They are tracked as the top of the Java backlog.

---

## 3. Kotlin

### 3.1 Lowered

String templates · name references · calls (including the receiver, which the null-dereference
checker reads from `args[0]`) · dot-qualified calls · binary and comparison operators ·
`if`/`else` as an expression and as a statement · `when` as a branch chain · `val`/`var`
properties · `return` · `throw` · blocks · object creation · safe calls (`?.`) · elvis (`?:`) ·
lambdas · `object` declarations · data classes (their compiler-generated `copy`, `apply`,
`unapply` and `_N` accessors, marked synthetic) · **nullability** (the frontend's main
contribution: a platform type is recorded as nullable, and a `!!` is recorded as an assertion).

### 3.2 Known gaps

| Construct | Reported? | Note |
|---|---|---|
| `is`-pattern branches of a `when` | **reported** | The branch used to be skipped with a bare `continue`, so the function still claimed full precision. It is now reported and the function is downgraded. |
| The **value** of a `when` | **supported** | The arms' bodies were translated as STATEMENTS, so the value was computed and dropped: `val x = when (y) { ... }` produced an undefined `x`, and every taint that flowed through the `when` was lost — silently, with the function still claiming full precision. The `when` now produces a result temp that every arm assigns. |
| Companion-object members | reported | The members are absent from the program. |
| Default arguments | reported | Only the declared parameter list is modelled. |
| Varargs | reported | Only the first argument is modelled. |
| `inline` / `reified` | reported | The inline body is not inlined into the caller. |
| `suspend` functions | reported | The continuation is not modelled. |
| The receiver of an extension function | reported | The receiver becomes `null`, so a call on it is a false positive. |

### 3.3 The `when` value — the shape of the fix

The fix is the one the TAC already uses everywhere else: a result temp that every arm assigns and
the whole expression yields.

```
val v = when (x) {
    1 -> "one"
    else -> "other"
}
```

```
t_result = <temp>
  t_eq = x == 1
  BRANCH t_eq -> whenmatch -> whennext
whenmatch:
  t_result = "one"
  JUMP whenend
whennext:
  t_result = "other"
whenend:
  MOVE v = t_result
```

An arm whose body is used for its effect (`1 -> doSomething()`) is still lowered as a statement:
the source does not give it a value, and inventing one would put a value on the stack that the
source never had.

---

## 4. Scala

### 4.1 Lowered

Literals · name references · calls · binary and comparison operators · `if`/`else` · `while` ·
`match` (as a branch chain: one `INSTANCEOF` plus one `BRANCH` per typed case, with a `JUMP` to
a merge label — exactly the shape a Java `instanceof` chain has) · blocks · `val` definitions ·
`return` · `throw` · object creation · case classes (their compiler-generated members, marked
synthetic) · traits · Scala's own name-mangled locals (`x$1`).

### 4.2 Known gaps

**Scala has the largest gap list, and it is the language where a gap is most likely to destroy a
taint flow.** The construct-level gaps are all REPORTED now, and the function's precision is
downgraded — but a degraded function still produces findings, so the gap is a limitation rather
than a lie.

| Construct | Reported? | Note |
|---|---|---|
| `for` comprehensions | reported | **The most severe.** `for (x <- xs) { total += x }` lowers to a call to an uncalled anonymous function: the accumulator stays at its initial value and the loop body never runs. Every taint that flowed through the loop is destroyed. |
| Closures / lambdas | reported | The closure body is not modelled. |
| Varargs | reported | Only the first argument is modelled. |
| `try`/`catch` | reported | The catch clauses are not modelled. |
| By-name parameters | reported | The parameter is modelled as by-value. |
| Implicit parameter lists | reported | The implicit arguments are not modelled. |
| Implicit conversions | reported | The conversion is not applied. |
| `match` guards | reported | The guard test is absent: the case fires on the pattern alone. |
| Tuple destructuring | reported | The tuple's components are not modelled. |
| Top-level definitions | reported | The definition becomes a member of the package object. |
| Structural types | reported | The refinement is not modelled. |
| Scala 3 `enum`, `given`/`using`, extension methods, opaque types, context functions | reported | Not lowered. |

### 4.3 The `for` comprehension — why it is the worst one

The finding set for a Scala function with a `for` comprehension is not merely incomplete: it can
be **wrong**, because the accumulator is provably still zero. A rule that says "this value is
tainted" would not fire, and a rule that says "this value is a constant" would.

It is the top of the Scala backlog.

---

## 5. The gaps that are reported today

| What | Before | After |
|---|---|---|
| Scala's structured-problem channel | never written for construct gaps, so no gap could travel with the IR | one collector writes both forms; `ScalaTacFrontendTest.aLoweringGapTravelsWithTheIrAsAStructuredProblem` |
| Scala's `match` degradation | `degraded = true` with **no** problem recorded, so the degradation was invisible | the gap is recorded; the function's precision is downgraded and the reason travels with the IR |
| Kotlin's `is`-pattern branches | skipped with a bare `continue`; the function kept `SOURCE_FULL` | reported and downgraded; `KotlinTacFrontendTest.anIsBranchThatCannotBeLoweredIsReported` |
| Kotlin's `when` value | computed and dropped; `val x = when (...)` produced an undefined `x` and lost every taint through it | the `when` produces a result temp; `KotlinTacFrontendTest.aWhenProducesItsValue` |

The two Kotlin fixes are the same defect in different clothes: **a function that was only partly
lowered claimed to be fully lowered.** The precision flag is the mechanism that says "do not
trust the precision-dependent facts about this function", and it only works if the thing that
made the function partial is actually recorded.

---

## 6. How a gap is reported

The structured problem carries:

- the **function** the gap is in,
- the **kind** (`UNHANDLED_NODE`, `UNSUPPORTED_CONSTRUCT`, `TRANSLATION_ERROR`),
- the **construct** that could not be lowered,
- the **message**, in the user's terms,
- the **line** and **column**.

A source-less consumer therefore can answer "is this file incomplete because the frontend lacks a
feature, or because the source does not compile?" — and those need different responses: one is a
frontend backlog item, the other is a code fix.

---

## 7. The rule for adding a construct

When a construct is added to a translator, it has to come with:

1. a lowering, and
2. a **test that asserts the SEMANTIC result** — not that an instruction was produced, but that
   the value survives the construct. A construct that lowers to the right instruction kinds and
   loses the value is the failure mode this document exists to prevent.

And when a construct is NOT lowered, it has to come with:

3. a reported problem, and
4. a downgraded precision.

A construct that is neither lowered nor reported is a **silent gap**, and it is a bug in the
frontend, not a limitation of the tool.
