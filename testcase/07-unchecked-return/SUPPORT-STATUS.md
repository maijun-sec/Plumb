# Support status - 07-unchecked-return

**The question is whether a return value is observed, and the answer needs three facts: the call's name, its return type, and whether anything reads the result.**

CWE-252 is about a method whose return value says "did this work?" being thrown away. The value can be discarded in three shapes, and all three are real defects:

```java
target.delete();                // the statement drops the value
boolean ok = target.delete();   // stored, and 'ok' is never read
log(target.delete());           // the logger drops the value
```

The first shape is the one a name-based rule finds, and it is the *rarest* of the three in real code. The second needs a use analysis of the method's locals: a local that is written but never read is not observed. The third needs to know which callees ignore their arguments (`println`, `log`, `trace`, ...) and which use them (`valueOf`, `compare`, `requireNonNull`, ...).

## The void question

A void method has no return value, so there is nothing to discard. The JDK signatures are the authority: `InputStream.close()`, `Connection.commit()`, `Writer.flush()` and `Files.delete(Path)` are all void, and they report failure by **throwing**. A name-only rule reports every one of them.

Failing to handle the exception a void method throws is **CWE-703** (improper check of exceptional conditions) — a different rule, with a different mechanism. This category says nothing about it, and the rule does not pretend to.

The rule resolves the call's return type, and **an unresolvable call is reported**: an unresolved type is a missing fact, never a proof of safety. This matters because a source-only scan has no third-party jar on the classpath, so a library call usually cannot be resolved at all.

## What the fix underneath turned out to be

The void check needs symbol resolution, and the scan pipeline **translates to TAC first** and only then runs the AST checkers on the same tree. The translation was re-parenting the AST: the translator built a synthetic `VariableDeclarationExpr` around a field's original `VariableDeclarator` with `NodeList.nodeList(v)`, and that *moves* the declarator — it is detached from its `FieldDeclaration` and attached to a node that hangs off no `CompilationUnit`. JavaParser's `getSymbolResolver()` walks the parent chain to find the unit, so every `resolve()` on that declarator and everything under it threw.

The visible symptom was narrow — one rule reporting void methods as unchecked returns — but the cause was shared: **the translation silently disabled type resolution for every AST checker that ran after it**, and a checker that cannot resolve a type has no way to say "I do not know". The translator now *copies* the declarator instead of moving it, and `TranslationKeepsSymbolResolutionTest` holds that in place.

## Measured

- True positives: **TP 5/5**
- True negatives: **TN 9/9**
- Scan mode: per-file, callgraph strategy: `global`.

## Outstanding cases

none

## Known limitations

- **A return value passed to an UNKNOWN callee is not reported.** `report(f.delete())` is not
  reported, because the outer call may well use the value — and a call whose own result is unused
  cannot be shown to ignore its arguments. Only the callees that provably ignore their arguments
  (`println`, `print`, `log`, `trace`, `debug`, `info`, `warn`, `error`, `fatal`) count as
  discarding.
- **The read analysis is method-wide, not flow-sensitive.** A local read on ONE path counts as
  observed everywhere. `boolean ok = f.delete(); if (x) { use(ok); }` is therefore not reported,
  even though `ok` is unobserved when `x` is false.
- **A call nested inside a larger expression is not the discard shape.** `boolean ok = !f.delete();`
  assigns a negated value, and the rule does not claim to understand it. Only a DIRECT
  initializer and a DIRECT argument position are recognised.
- **The must-check list is a method-NAME list.** A call whose name is not on the list is not
  reported, whatever its receiver's type. A third-party method that returns a success flag under a
  different name is missed.
- **CWE-703 is not covered here.** A void method that throws and whose exception is not handled is
  a different rule.

Full per-case detail: `RESULT.json`. Expectations: `EXPECTATIONS.json`.
