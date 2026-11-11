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
