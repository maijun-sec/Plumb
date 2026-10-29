# Per-language rules: where the language actually enters

[English](../README.md) | [中文](../zh/tech/LANGUAGE-RULES.md)

This document answers one question: **if the taint engine is to support
Kotlin and Scala as well as Java, where does the language enter?**

The answer is decided by measurement, not by reading the code and
guessing. `scripts/survey_language_readiness.py` produces the numbers
below; re-run it after any change to the configuration or the engine.

```bash
python scripts/survey_language_readiness.py
```

## 1. What the configuration is today

Six YAML files, loaded by `plumb-dfa`'s `ConfigLoader`:

| File | Shape | What it holds |
| --- | --- | --- |
| `sink-families.yaml` | `families:` | 39 families, 416 patterns -- the taint sources and sinks |
| `receiver-passthrough.yaml` | `families:` | 9 families, 95 patterns -- which getters return tainted data |
| `fact-kinds.yaml` | -- | the fact kinds the engine propagates |
| `checkers.yaml` | -- | per-checker parameters |
| `cwe-mappings.yaml` | -- | the CWE each checker carries |
| `tuning.yaml` | -- | the aggressiveness knob and friends |

`ConfigLoader` loads them from the classpath by default, or from a
user-supplied directory. The override mechanism is a
`filename -> path` map, so a user can already replace any one file.

## 2. The measurement

**The rule model has no language notion.** `SinkFamily`'s fields are:

```
String pattern, int argIndex, Pattern compiled, Pattern relaxed,
String name, String cwe, String severityLabel, boolean enabled,
int defaultArgIndex, List<SinkPattern> patterns
```

There is no `language` field, and no need for one: a family is a set of
patterns, a pattern is a regex, and a regex matches a callee signature.

**65% of the sink patterns name a Java library class.** Of the 416
patterns in `sink-families.yaml`, **270 name a `java.` / `javax.` /
`jakarta.` class** and 146 name something else (a project class, or a
bare method name).

Eleven families are **entirely** Java-library patterns:

```
cleartext-tx.sinks (21)    file-path.sinks (20)    format-string.sinks (13)
mem-alloc.sinks (13)       xss.sinks (13)          taint.sources.io (6)
taint.sources.network (3)  taint.sources.console (2)
taint.sources.env (2)      regex.sinks (2)         xquery.sinks (1)
```

**The receiver-passthrough patterns name Java classes in a relaxed
form.** They match `^((?:[\w.]+\.)*)HttpServletRequest\.getParameter` --
the class NAME without its package, so the pattern text itself carries
no `java.`. The class it names is still `javax.servlet.http.HttpServletRequest`.

**Three places carry the language, and only three:**

| Where | What |
| --- | --- |
| `plumb-parser`'s `JavaSourceParser` | the parser -- 11 references to the Java parser |
| `plumb-tac`'s `TypeMapper` | the type mapper -- 8 references to the Java parser's type model |
| `plumb-main`'s `ScanRunner` | the source-file dispatch -- 11 `.java` extension checks |

## 3. What this means

**The rule model is already language-agnostic.** Nothing in
`SinkFamily`, `FactKind` or the YAML shape knows what a language is.
A Kotlin rule set is the same YAML with different patterns.

**The configuration is per-language only insofar as the LIBRARY it
names differs.** That is a **data** difference, not a structural one.
A Kotlin program does not call `javax.servlet.http.HttpServletRequest`;
it calls `io.ktor.server.request.*` or `spark.Request`. The defect
class -- "untrusted input reaches a SQL sink" -- is the same; the
patterns are not.

**So the per-language layout is a DIRECTORY, not a schema change.**

```
plumb-dfa/src/main/resources/
  rules/
    java/
      sink-families.yaml
      receiver-passthrough.yaml
    kotlin/
      sink-families.yaml
      receiver-passthrough.yaml
```

with the shared files (`fact-kinds.yaml`, `checkers.yaml`,
`cwe-mappings.yaml`, `tuning.yaml`) staying where they are, because
they describe the ENGINE, not the language.

**The loading rule has to be decided, and this is the part that is a
real design question.** Three options:

1. **One directory per language, loaded by the language of the file.**
   The scan is per-file, so a directory that holds both `Foo.java` and
   `Foo.kt` loads two sets of families and merges them. Merging by
   family name means a family defined in both is the union of the
   patterns -- which is wrong, because `java.sql.Statement#execute` and
   `scala.collection.mutable.Set#+=` are different sinks that should
   not share a CWE attribution by accident.

2. **One directory per language, and the family key carries the
   language.** `java.sql.sinks` and `kotlin.sql.sinks` are separate
   families with separate CWEs. No merge ambiguity. The cost is that
   the family names in the reports carry the language.

3. **The patterns carry the language.** Every pattern says which
   language it belongs to, and the engine filters by the language of
   the file being scanned. One family, many patterns, one filter.

Option 3 is the one this document recommends, and the reason is
specific: **the defect class is the same across languages, and the
report should say the defect class.** A finding that says
`CWE-89 SQL injection` is true whether the project is Java or Kotlin.
If the family is split per language, the rule ids split too, and a
user's whitelist for one language silently stops applying to another.

But option 3 needs the sink-family model to learn about languages,
and today `SinkFamily` has no language field. That is the ONE place
where the model has to change.

## 4. What has to happen, in order

1. **The language reaches the scan.** `ScanRunner` dispatches on the
   `.java` extension in 11 places. That becomes a per-language
   dispatch: extension -> language -> parser. The three components
   that are already per-language (parser, type mapper) get an
   interface.

2. **`SinkPattern` learns its language.** A pattern says which
   language's library it names. The engine filters the family's
   patterns by the language of the file being scanned. A pattern with
   no language applies to all of them -- which is what a
   language-independent pattern like "a method named `exec` on any
   receiver" wants.

3. **The configuration is split per language, and the shared files
   stay shared.** The directory layout above, with the language's
   patterns in the language's file.

4. **The rule catalogue keeps one rule per defect class.** The CWE
   mapping does not change; only the patterns that can reach the sink
   do.

## 5. What is deliberately NOT decided here

- **The parser for Kotlin and Scala.** Each needs its own front end
  (the JavaParser for Java; the Kotlin compiler's PSI, or the Scala
  compiler's trees, for the others). That is a separate piece of work
  with its own measurements.
- **Which languages.** The layout above is per-language, but which
  languages get one is a product decision.
- **The user-facing override shape.** `ConfigLoader` already takes a
  `filename -> path` map. A per-language layout makes that map
  per-language too, and whether the user overrides one language's file
  or all of them is a decision about the user experience, not about
  the engine.
