# Support status - 07-unchecked-return

**The question is whether a return value is observed, and the answer needs three facts: the call's name, its return type, and whether anything reads the result.**

CWE-252 is about a method whose return value says "did this work?" being thrown away. The value can be discarded in three shapes, and all three are real defects:

```java
target.delete();                // the statement drops the value
boolean ok = target.delete();   // stored, and 'ok' is never read
log(target.delete());           // the logger drops the value
```

The first shape is the one a name-based rule finds, and it is the *rarest* of the three in real code. The second needs a use analysis of the method's locals: a local that is written but never read is not observed. The third needs to know what the callee does with the argument -- and that is a fact about the callee's **body**, not about its name.

## Who decides, for each shape

- The **statement** drops the value.
- The **local that holds it** decides whether a stored value is observed. A declaration can hold several variables at once (`boolean a = f.delete(), b = true;`), and only the declarator that holds the value matters -- the other one is a different variable. How the value is spelled on the way in does not change what it is: `!f.delete()`, `(f.delete())` and `ok = f.delete()` all put the same value in a local, and a local that is never read drops it.
- The **callee's body** decides whether an argument is observed. Two methods called `log` can behave completely differently: one prints the value and does nothing else, the other throws when the value is false. The name lists cannot tell those apart, and the body can. So the body wins, and the name lists are only the fallback for a callee the resolver cannot reach -- a JDK method, a jar, an unresolved receiver.

## The void question

A void method has no return value, so there is nothing to discard. The JDK signatures are the authority: `InputStream.close()`, `Connection.commit()`, `Writer.flush()` and `Files.delete(Path)` are all void, and they report failure by **throwing**. A name-only rule reports every one of them.

Failing to handle the exception a void method throws is **CWE-703** (improper check of exceptional conditions) -- a different rule, with a different mechanism. This category says nothing about it, and the rule does not pretend to.

The rule resolves the call's return type, and **an unresolvable call is reported**: an unresolved type is a missing fact, never a proof of safety. This matters because a source-only scan has no third-party jar on the classpath, so a library call usually cannot be resolved at all.

## What the fix underneath turned out to be

The void check needs symbol resolution, and the scan pipeline **translates to TAC first** and only then runs the AST checkers on the same tree. The translation was re-parenting the AST: the translator built a synthetic `VariableDeclarationExpr` around a field's original `VariableDeclarator` with `NodeList.nodeList(v)`, and that *moves* the declarator -- it is detached from its `FieldDeclaration` and attached to a node that hangs off no `CompilationUnit`. JavaParser's `getSymbolResolver()` walks the parent chain to find the unit, so every `resolve()` on that declarator and everything under it threw.

The visible symptom was narrow -- one rule reporting void methods as unchecked returns -- but the cause was shared: **the translation silently disabled type resolution for every AST checker that ran after it**, and a checker that cannot resolve a type has no way to say "I do not know". The translator now *copies* the declarator instead of moving it, and `TranslationKeepsSymbolResolutionTest` holds that in place.

The same resolution is what lets the rule ask the callee what it does with an argument. That question has no answer from the name alone, and the name alone is what the rule used to ask.

## Measured

- True positives: **TP 10/10**
- True negatives: **TN 14/14**
- Scan mode: per-file, callgraph strategy: `global`.

## Outstanding cases

none

## Known limitations

- **A return value passed to a callee the RESOLVER cannot reach is not reported.** The callee's body decides, so the body has to be there. A method of the analysed source is reachable; a method of the JDK, of a jar, or behind an unresolved receiver is not, and there the callee's NAME is the only fact there is. `report(f.delete())` is reported when `report` is the project's own method, and is not reported when `report` comes from a library.
- **A callee whose parameter is unused in ITS OWN body counts as ignoring it.** The body is the fact the rule reads, and a body that never touches the parameter says the value is dropped. An OVERRIDE that uses the parameter is a different body, and the rule does not look at the override: the call has to be resolved to the overriding method for the value to count as used.
- **A value stored in a FIELD is not reported.** The holder has to be a local: whether a field is read afterwards is a question about the whole program, and this rule answers questions about one method.
- **The read analysis is method-wide, not flow-sensitive.** A local read on ONE path counts as observed everywhere. `boolean ok = f.delete(); if (x) { use(ok); }` is therefore not reported, even though `ok` is unobserved when `x` is false.
- **A call inside a COMBINING expression is not the discard shape.** A value-preserving wrapper is recognised -- `!f.delete()` and `(f.delete())` hold the value -- but a call inside a binary expression is not: `boolean ok = f.delete() && other;` assigns a combined value, and the rule does not claim to understand it.
- **The must-check list is a method-NAME list.** A call whose name is not on the list is not reported, whatever its receiver's type. A third-party method that returns a success flag under a different name is missed.
- **CWE-703 is not covered here.** A void method that throws and whose exception is not handled is a different rule.

Full per-case detail: `RESULT.json`. Expectations: `EXPECTATIONS.json`.
