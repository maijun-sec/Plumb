# Kotlin AST Rule Engine (`plumb-dsl-kotlin`)

[English](../README.md) | [中文](../zh/tech/KOTLIN-DSL.md)

`plumb-dsl-kotlin` is a **standalone AST rule engine**. Rules are written in a small Kotlin-style DSL and
evaluated on JavaParser AST nodes. A project can ship its own AST rules without writing any Java.

**Data-flow / taint rules are not in this module.** They remain driven by the YAML configuration of
`plumb-dfa` (`sink-families.yaml`, `checkers.yaml`, `tuning.yaml`). The two engines do not overlap:

| Rule type | How it is configured | Module |
| --- | --- | --- |
| AST rules (syntactic shape, local patterns) | Kotlin DSL rule file (XML / YAML) | `plumb-dsl-kotlin` |
| Data-flow / taint rules (source → sink) | YAML | `plumb-dfa` |

---

## 1. Getting started

### 1.1 Writing a rule

The minimal shape of a rule file (YAML):

```yaml
rules:
  - id: plumb.dsl.demo-empty-catch
    name: demo empty catch block
    description: A catch block with no statements
    severity: MEDIUM
    type: BAD_PRACTICE
    cwe: [CWE-390]
    nodeType: CatchClause
    matcher: |
      CatchClause catchClause: catchClause match { empty }
```

The shape of `matcher` is:

```
<NodeType> <binding>: <predicate>
```

- `NodeType` -- the JavaParser node type name (`CatchClause`, `MethodDeclaration`,
  `Comment`...). Abstract types are supported too: `Comment` matches `LineComment` /
  `BlockComment` / `JavadocComment`, and `Statement` matches all statement subclasses.
- `binding` -- the name of the node inside the predicate.
- `predicate` -- the matching condition.

### 1.2 Running it

```bash
plumb -i src/main/java --dsl-rules rules/ast-rules.yaml
```

`--dsl-rules` may be repeated; the rules of several files are merged.

It can also be written into the YAML configuration:

```yaml
dsl:
  rules:
    - rules/ast-rules.yaml
  strict: false
```

The CLI's `--dsl-rules` **appends** to the files already listed in the YAML, it does not override them.

### 1.3 Output

When a rule fires, the finding it produces is exactly the same as for a built-in rule and goes through the same reporter pipeline:

```json
{
  "file": "src/main/java/Sample.java",
  "line": 5,
  "column": 11,
  "severity": "medium",
  "rule_id": "plumb.dsl.demo-empty-catch",
  "rule_name": "DEMO_EMPTY_CATCH_BLOCK",
  "rule_type": "bad_practice",
  "cwe": ["CWE-390"],
  "message": "A catch block with no statements",
  "dataflow": "dsl:plumb.dsl.demo-empty-catch",
  "sink_function": "run():void"
}
```

---

## 2. Rule file format

### 2.1 XML (upstream compatibility)

The upstream `java-sca-ast-dsl` project uses XML. Plumb reads the very same format:

```xml
<rules>
    <rule language="java">
        <id>8E2C4F1A-0B3D-4E5F-9A7B-1C2D3E4F5A6B</id>
        <name>empty catch block</name>
        <description>empty catch block</description>
        <matcher>
            <![CDATA[
                CatchClause catchClause: catchClause match { empty }
            ]]>
        </matcher>
    </rule>
</rules>
```

The child elements of a rule element:

| Element | Required | Description |
| --- | --- | --- |
| `id` | yes | The rule id (upstream uses a UUID, see §3.1) |
| `name` | yes | The short name |
| `description` | no | The description; `name` is used when it is absent |
| `matcher` | yes | The matching expression, usually placed inside CDATA |

The XML form has **no** `nodeType` field -- the node type is parsed from the head of the `matcher`.

XML also accepts the four optional elements `severity` / `type` / `enabled` / `cwe`
(semantically identical to the fields of the same name in YAML), but upstream files do not write them.

The `language` attribute on `<rule>` is ignored; unknown child elements do not cause a load failure either.

**The format is chosen by file extension**: `.xml` goes through XML, everything else goes through YAML.

### 2.2 YAML (the Plumb extension)

The YAML form adds Plumb's own metadata: severity, type, CWE and the enable switch.

| Field | Required | Default | Description |
| --- | --- | --- | --- |
| `id` | yes | -- | The rule id. See §3.1 |
| `name` | yes | -- | The short name; it becomes the `rule_name` of the finding |
| `description` | no | `name` | The message of the finding |
| `matcher` | yes | -- | The matching expression |
| `nodeType` | no | matches every node | The JavaParser node type name |
| `severity` | no | `MEDIUM` | `INFO` / `LOW` / `MEDIUM` / `HIGH` / `CRITICAL` |
| `type` | no | `OTHER` | A Plumb rule type, e.g. `BAD_PRACTICE` / `SECURITY` |
| `cwe` | no | empty | A list of CWE numbers (a string or a list) |
| `enabled` | no | `true` | Turns this rule off |

When `nodeType` is left empty the rule applies to **every** AST node -- usually not what you want, but the engine allows it.

A wrong `nodeType` (a node class that does not exist) is recorded as a WARN and the rule is **skipped**, instead of
silently degrading into "matches every node".

---

## 3. Rule ids and aliases

### 3.1 Id normalisation

Upstream uses a random UUID as the rule id. Plumb's registry, its SARIF output and the documentation gate all require
the `plumb.dsl.<slug>` convention, so the engine performs one normalisation pass:

- An id that already starts with `plumb.` is kept as it is.
- Any other id (an upstream UUID) -> a slug is derived from `name` and it becomes `plumb.dsl.<slug>`.

The normalised id is the id the finding really uses; **the original id is kept as an alias**, so filtering
rules by the upstream id still works.

### 3.2 Enabling and disabling a rule

The enabled state of a DSL rule has three sources, from lowest to highest priority:

1. `enabled: false` in the rule file.
2. The state in the `RuleRegistry` -- the runner registers the DSL rules into the live registry,
   and the user's YAML `rules.enabled` / `rules.disabled` apply to them in the usual way.
3. `--rule <id>` on the command line.

---

## 4. The matcher language

### 4.1 Basic shape

```
<NodeType> <binding>: <predicate>
```

The predicate is a Kotlin expression, but **not** arbitrary Kotlin: the engine rewrites it into calls
against a set of static facade methods (see §5).

### 4.2 Node properties (bare names)

Inside a predicate you can write node properties directly; the engine rewrites them into calls
against `NodeAccess`:

| Written as | Actual call |
| --- | --- |
| `simpleName` | `NodeAccess.simpleName` |
| `qualifiedName` | `NodeAccess.qualifiedName` |
| `methodName` | `NodeAccess.methodName` |
| `parameterTypes` | `NodeAccess.parameterTypes` |
| `parameters` | `NodeAccess.parameters` |
| `exception` | `NodeAccess.exception` |
| `enclosingFunction` | `NodeAccess.enclosingFunction` |
| `enclosingClass` | `NodeAccess.enclosingClass` |
| `static` | `NodeAccess.isStatic` |
| `final` | `NodeAccess.isFinal` |
| `type` | `NodeAccess.type` |
| `content` | `NodeAccess.content` |
| `method` | `NodeAccess.method` |
| `empty` | `NodeAccess.isEmpty` |

### 4.3 Infix operators

| Written as | Actual call |
| --- | --- |
| `s startsWith p` / `s startWith p` | `StringOps.startsWith` |
| `s endsWith p` | `StringOps.endsWith` |
| `s matches regex` | `StringOps.matches` |
| `c contain e` / `c contains e` | `Ops.contains` |

### 4.3.1 Boolean operators

| Written as | Becomes |
| --- | --- |
| `a and b` | `a and b` -- Kotlin's own `Boolean.and` |
| `a or b` | `a or b` -- Kotlin's own `Boolean.or` |
| `a && b` / `a \|\| b` | unchanged |
| `not a` | `!(a)` |

`not` is the one that needs a rewrite. The upstream DSL spells its boolean operators in words,
because it defines them as Kotlin extension infix functions. Kotlin has `Boolean.and` and
`Boolean.or` as member functions, so those two work unchanged -- but it has no **prefix** `not`
operator: `Boolean.not()` is a function call, so `not a` is a syntax error and the rule fails to
compile.

The operand is parenthesised as well, because `!` binds tighter than the comparison operators:
`not a == b` has to become `!(a == b)`, not `!a == b`. The second one compares a Boolean with a
String, which is a type error rather than the negation the rule asked for.

`not` is only rewritten when it stands alone as a word. A name that merely starts with `not` --
`nothing`, `annotate` -- is a different word and is left alone.

### 4.4 Accessing nodes and types

The predicate of a rule can access the current node and its type:

| Facade | Purpose |
| --- | --- |
| `NodeAccess.simpleName(n)` | The simple name of the node |
| `NodeAccess.qualifiedName(n)` | The qualified name of the node |
| `NodeAccess.content(n)` | The source text of the node |
| `NodeAccess.isEmpty(n)` | Whether the node is empty |
| `NodeAccess.method(n)` | Gets the method declaration |
| `NodeAccess.methodName(n)` | The method name |
| `NodeAccess.methodNameOf(n)` | The method name (another way to get it) |
| `NodeAccess.resolvedMethodName(n)` | The resolved method name |
| `NodeAccess.parameters(n)` | The parameter list |
| `NodeAccess.parameterTypes(n)` | The list of parameter types |
| `NodeAccess.type(n)` | The type declared by the node |
| `NodeAccess.exception(n)` | The exception carried by the node |
| `NodeAccess.exceptionName(n)` | The exception name |
| `NodeAccess.exceptionType(n)` | The exception type |
| `NodeAccess.enclosingClass(n)` | The enclosing class name |
| `NodeAccess.enclosingFunction(n)` | The enclosing function name |
| `NodeAccess.constructorName(n)` | The constructor name |
| `NodeAccess.isStatic(n)` | Whether it is static |
| `NodeAccess.isFinal(n)` | Whether it is final |
| `TypeAccess.typeName(n)` | The fully qualified name of the resolved type |
| `TypeAccess.resolve(n)` | Resolves it into a `ResolvedType` |
| `TypeAccess.supers(n)` | The "is-a" closure: the type itself plus all its supertypes |
| `TypeAccess.strictSupers(n)` | The strict supertypes (without the type itself), matching upstream semantics |
| `TypeAccess.supersOf(typeRef)` | Same as `supers`, applied to a type reference |
| `TypeAccess.strictSupersOf(typeRef)` | Same as `strictSupers`, applied to a type reference |
| `TypeAccess.typeNameOf(typeRef)` | The fully qualified name of the type reference |
| `TypeAccess.resolveTypeName(typeRef)` | The fully qualified name of the type reference |
| `TypeAccess.resolveDeclaredTypeName(typeRef)` | The fully qualified name of the declared type |
| `TypeAccess.typeAt(types, index)` | Gets the type at position `index` in the list |

> **Difference in upstream semantics.** Upstream's `ResolvedType.supers` does not include the type itself, so upstream's
> "poor logging practice" rule does not fire for a directly declared `Logger` field.
> Plumb's `TypeAccess.supers` uses the "is-a" closure (**including** the type itself),
> while `TypeAccess.strictSupers` preserves the upstream behaviour.

### 4.5 Strings and collections

| Facade | Purpose |
| --- | --- |
| `StringOps.startsWith(s, p)` | Prefix |
| `StringOps.endsWith(s, p)` | Suffix |
| `StringOps.matches(s, regex)` | Regular expression match (a `(?!` prefix means case-insensitive) |
| `StringOps.contains(s, sub)` | Substring containment |
| `Ops.contains(collection, element)` | The collection contains an element |
| `Ops.containsText(collection, text)` | An element of the collection contains the text |
| `Ops.size(collection)` | The size of the collection |

### 4.6 Structural matchers

| Facade | Corresponding DSL form | Purpose |
| --- | --- | --- |
| `Matchers.match(node) { ... }` | `node match { ... }` | The predicate applies to the node itself |
| `Matchers.contain(node) { ... }` | `node contain { ... }` | A descendant at any depth satisfies the predicate |
| `Matchers.ancestor(node) { ... }` | `node ancestor { ... }` | An ancestor satisfies the predicate |

The predicates of `contain` and `ancestor` receive **the node being inspected** as their argument, so
`contain { simpleName == "ReturnStmt" }` means "there is a return statement somewhere below".

### 4.7 Multi-level matching

`contain` can be nested, so a single rule can express "a call whose parameter type is X inside some method":

```
MethodDeclaration method: method contain {
  MethodCallExpr call: call.name == "getPassword"
}
```

---

## 5. How the engine runs

### 5.1 Compile once, evaluate repeatedly

Every rule is **compiled exactly once**; the compiled form (`javax.script.CompiledScript`) is reused for the whole scan.

This was measured, not assumed:

| Approach | Time per evaluation |
| --- | --- |
| `engine.eval(text)` (recompiled every time) | about 236,551 µs |
| `CompiledScript.eval()` (the compiled form is reused) | about 543 µs |

A factor of about 435. After warm-up, compiling a single rule costs about 45 ms, which is paid once at the start of the scan.

### 5.2 The current node lives in a ThreadLocal

A Kotlin script resolves free variables at **compile time**, so the script cannot take the node as a parameter -- once it
has been compiled there is no place left to pass a different node on every evaluation.

Upstream rebuilds the script for every node, that is a full recompilation per node (236 ms).
Plumb works differently:

- The node is read at **run time** from a `ThreadLocal`;
- The compiled form is **shared between all threads**.

Why the node has to be per-thread: Plumb scans in parallel with a worker pool. Sharing a single static field
means two workers overwrite each other's node -- in a measurement with 8 threads and 2400 evaluations there were **635**
misdetections. With a `ThreadLocal` there are **zero** misdetections under the same conditions.

### 5.3 Why the rules do not hang off the per-node dispatch of `AstDispatcher`

Plumb's `AstDispatcher` dispatches by **exact node class**: it builds a table for every node type declared by a checker,
and a node is only handed to the checkers registered under that exact class. This has two consequences:

1. **The dispatcher never hands a `CompilationUnit` to a checker.** `CompilationUnit` is the
   **root** of the tree, not a node in it, and `unit.walk(...)` does not visit the root. So a checker that overrides
   `visit(CompilationUnit, StyleContext)` **never runs**.
2. **Abstract node types cannot be expressed.** A checker that overrides `visit(CatchClause, ...)` only sees
   `CatchClause`; DSL rules on the other hand can write `Statement` / `Expression` / `Comment`.

Therefore DSL rules **cannot** use the per-node dispatch of the dispatcher. They run in
`AstChecker.finish(ctx)` -- the only hook the dispatcher calls once per file, which guarantees that the
whole unit is visible.

`DslAstChecker` therefore runs its own traversal and groups the nodes by the target node type of each rule.

### 5.4 Comments need separate handling

JavaParser attaches comments to the node they annotate, but **does not put the comments into the tree**:
`findAll(Node.class)` never reaches a comment. Comments have to be obtained from
`getAllContainedComments()`. So a rule whose target is `Comment` has to add them to the candidate list explicitly.

### 5.5 Rules must enter the registry

`StyleContext.report(...)` silently drops a rule that is "off by default and not in the registry".
The runner therefore registers the metadata of the DSL rules into the live `RuleRegistry`, so that they take part in the
whitelist / blacklist filtering just like the built-in rules.

---

## 6. Error handling

| Situation | Behaviour |
| --- | --- |
| The rule file does not exist | Recorded as ERROR, the file is skipped |
| The XML is malformed | Recorded as ERROR, the file is skipped |
| The rule has no usable node type | Recorded as ERROR, the rule is skipped |
| The matcher cannot be rewritten | Recorded as ERROR, the rule is skipped |
| The script fails to compile | Recorded as ERROR, the rule is skipped |
| The script throws during evaluation | Recorded as WARN, the node does not match, the scan continues |

**One bad rule does not bring the whole scan down.**

`--dsl-strict` (or `dsl.strict: true` in the YAML) changes this: a compilation failure aborts the scan
immediately. Strict mode suits CI -- a rule with a mistake should not be skipped silently.

---

## 7. Modules and dependencies

```
plumb-dsl-kotlin
  ├── plumb-parser          (ParsedSource)
  ├── plumb-rules           (RuleDef / RuleRegistry)
  ├── plumb-reporter        (Finding)
  ├── plumb-style           (AstChecker / StyleContext SPI)
  ├── javaparser-core
  ├── javaparser-symbol-solver-core
  ├── kotlin-scripting-jsr223
  └── kotlin-stdlib
```

`plumb-style` depends on this module, but this module does **not** depend on any submodule of `plumb-style` --
the SPI lives in `plumb-style` and the implementation lives here, so there is no cycle.

`plumb-main` depends on `plumb-dsl-kotlin` and plugs `DslAstChecker` into the style check chain.

The Kotlin version is pinned to **2.2.0**: it is the first version that runs on JDK 25 and still produces Java 17
bytecode (1.9.22 is incomplete, 2.1.0 throws `IllegalArgumentException: 25.0.1` on JDK 25).

---

## 8. Related documentation

| Document | Content |
| --- | --- |
| [`../usage/CLI.md`](../usage/CLI.md) | The CLI manual for `--dsl-rules` / `--dsl-strict` |
| [`../usage/CLI-REFERENCE.md`](../usage/CLI-REFERENCE.md) | A quick reference for YAML v2, including the `dsl:` block |
| [`STYLE-RULES.md`](STYLE-RULES.md) | The built-in style rules, `AstDispatcher`, `AstCheckerRegistry` |
| [`../rules/RULE-AUTHORING.md`](../rules/RULE-AUTHORING.md) | The two ways of writing rules: Java checker and Kotlin DSL |
