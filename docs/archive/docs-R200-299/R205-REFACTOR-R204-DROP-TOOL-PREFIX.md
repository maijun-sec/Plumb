# R205 — Refactor R204 Rule Naming (Drop Tool Prefix)

**Date**: 2026-07-31
**Round type**: Naming convention refactor (no new checkers, 0 new tests)
**Result**: 16/16 R204 rules refactored, 0 regression, 1122 tests still pass
**Trigger**: Post-R204 directive: "Java class names, rule names, etc. — in all
places — should not reflect the original tool's name, only the specific
content of the rule being implemented."

## 1. Why

R204 implemented 16 rules from PMD / SonarQube / SpotBugs / Coverity but
followed an old convention of including the source tool's name in:

- the **Java class name** (`PmdExtendsObjectCheck`,
  `SonarArrayHashCodeAndToStringCheck`,
  `SpotbugsMutableStaticShouldBeFinalCheck`,
  `CoverityDeadcodeCheck`)
- the **rule UUID** (`bu-0701-pmd-extends-object`,
  `bu-0901-spotbugs-ms-should-be-final`, `cov-0002-deadcode`)
- the **RuleId name** (`PMD_EXTENDS_OBJECT`, `SONAR_S2116_…`,
  `SPOTBUGS_MS_SHOULD_BE_FINAL`, `DEADCODE`)
- the **description** (`"PMD ExtendsObject: class explicitly extends Object …"`)

This made the rules' own identity entangled with the source tool, which
violates the design principle that a Plumb rule should be identifiable
purely by its content, not its provenance. The user explicitly called
this out: rules should be named for the bug pattern they detect, not
the tool that originally documented it.

Earlier rounds (R166/R170/R176/R192/etc.) had class names without
tool prefix (e.g. `AssignmentInOperandCheck`,
`DefaultEncodingCheck`, `MissingBreakCheck`) but their UUIDs and
RuleId names still had it. R205 cleans up R204 fully and documents
the new convention.

## 2. The 16 Renames

| Old class | New class | New UUID | New RuleId |
|---|---|---|---|
| `PmdExtendsObjectCheck` | `ExtendsObjectCheck` | `bu-0701-extends-object` | `EXTENDS_OBJECT` |
| `PmdEmptyMethodInAbstractCheck` | `EmptyMethodInAbstractCheck` | `bu-0702-empty-method-in-abstract` | `EMPTY_METHOD_IN_ABSTRACT` |
| `PmdUnnecessaryReturnCheck` | `UnnecessaryReturnCheck` | `bu-0703-unnecessary-return` | `UNNECESSARY_RETURN` |
| `PmdLocalVariableCouldBeFinalCheck` | `LocalVariableCouldBeFinalCheck` | `bu-0704-local-variable-could-be-final` | `LOCAL_VAR_COULD_BE_FINAL` |
| `PmdCommentDefaultAccessCheck` | `CommentDefaultAccessCheck` | `bu-0705-comment-default-access` | `COMMENT_DEFAULT_ACCESS` |
| `SonarArrayHashCodeAndToStringCheck` | `ArrayHashCodeAndToStringCheck` | `bu-0801-array-hashcode-tostring` | `ARRAY_HASHCODE_TOSTRING` |
| `SonarCaseInsensitiveComparisonCheck` | `CaseInsensitiveComparisonCheck` | `bu-0802-case-insensitive-comparison` | `CASE_INSENSITIVE_COMPARISON` |
| `SonarCatchNpeCheck` | `CatchNpeCheck` | `bu-0803-catch-nullpointer-exception` | `CATCH_NPE` |
| `SonarSynchronizationOnStringOrBoxedCheck` | `SynchronizationOnStringOrBoxedCheck` | `bu-0804-synchronization-on-string-or-boxed` | `SYNC_STRING_BOXED` |
| `SonarBooleanMethodReturnCheck` | `BooleanMethodReturnCheck` | `bu-0805-boolean-method-returns-null` | `BOOLEAN_RETURNS_NULL` |
| `SpotbugsMutableStaticShouldBeFinalCheck` | `MutableStaticShouldBeFinalCheck` | `bu-0901-mutable-static-should-be-final` | `MUTABLE_STATIC_SHOULD_BE_FINAL` |
| `SpotbugsLocaleDependentCaseConversionCheck` | `LocaleDependentCaseConversionCheck` | `bu-0902-locale-dependent-case-conversion` | `LOCALE_DEPENDENT_CASE_CONVERSION` |
| `SpotbugsStringBuilderConcatenationCheck` | `StringBuilderConcatenationCheck` | `bu-0903-string-builder-concatenation-in-loop` | `STRING_BUILDER_CONCATENATION_IN_LOOP` |
| `SpotbugsUselessObjectCheck` | `UselessObjectCheck` | `bu-0904-useless-object` | `USELESS_OBJECT` |
| `SpotbugsIntDivisionCastToDoubleCheck` | `IntDivisionCastToDoubleCheck` | `bu-0905-int-division-cast-to-double` | `INT_DIVISION_CAST_TO_DOUBLE` |
| `CoverityDeadcodeCheck` | `DeadcodeCheck` | `bu-1001-unreachable-code` | `UNREACHABLE_CODE` |

UUIDs keep the numeric prefix (`bu-0701`, `bu-0801`, `bu-0901`) as a
feature-area tag, but the slug itself now describes only the rule
content. The `cov-` namespace was retired — `cov-0002-deadcode`
became `bu-1001-unreachable-code`, joining the rest of the
`bu-1000+` series.

## 3. What was preserved

The **aliases** in `RuleRegistry` are unchanged in form: they still
say `addAlias("bu-0701-extends-object", List.of("PMD:ExtendsObject"))`.
Aliases are explicit metadata showing the foreign-source mapping —
they are the Plumb rule's cross-reference, not the rule's own
identity. Keeping them lets downstream consumers show
"this Plumb rule also appears as PMD:ExtendsObject / SpotBugs:MS…"
in the UI without polluting the rule's own naming.

The test class names (`R204PmdBatchTest`, `R204SonarBatchTest`,
`R204SpotBugsBatchTest`, `R204CoverityDeadcodeTest`) are kept as
organizational labels — they group tests by source tool, which is a
legitimate test-suite taxonomy (think: `test/java/junit5/` vs
`test/java/junit4/`).

## 4. The new naming convention

From R205 forward, any rule added to Plumb follows:

| Layer | Convention | Example |
|---|---|---|
| Class name | Pure rule content. PascalCase. No source-tool prefix. | `ExtendsObjectCheck` |
| UUID | `bu-XXXX-<rule-content-slug>` (lower-kebab, no tool prefix) | `bu-0701-extends-object` |
| RuleId name | `UPPER_SNAKE_CASE` of the rule content, no tool prefix | `EXTENDS_OBJECT` |
| Description | Pure rule content, no "PMD:…" / "SonarQube Sxxxx:" / "SpotBugs XXXX:" prefix | `Class explicitly extends Object, the declaration is redundant` |
| Aliases | Optional cross-reference to foreign source. Format `<SourceTool>:<NativeId>`. | `PMD:ExtendsObject` |
| Javadoc | Describes the rule pattern, not the source tool. References to source code in Javadoc are removed. | "Detects: `class N extends Object {}` …" |

## 5. Test results

```
$ mvn -pl plumb-style,plumb-rules -am test -Dtest="R204*" -Dsurefire.failIfNoSpecifiedTests=false
[INFO] Running org.plumb.style.R204CoverityDeadcodeTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running org.plumb.style.R204PmdBatchTest
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running org.plumb.style.R204SonarBatchTest
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running org.plumb.style.R204SpotBugsBatchTest
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 64, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Full sweep (all modules):

```
TOTAL: run=1122 fail=0 err=0 skip=2
```

No regression. R204's 64 tests still pass under the new class names.

## 6. R205 4 创新点

1. **Pure-content naming convention** (R205 design). The rule's
   identity is now decoupled from its source tool. Class name,
   UUID, RuleId name, and description all describe the bug
   pattern; only the alias (`PMD:ExtendsObject` etc.) tells you
   which external system originally documented it. This is the
   same principle as Wikipedia disambiguation: the page title
   describes the topic, not the encyclopedia that covers it.

2. **cov- namespace retired** (R205 cleanup). The `cov-NNNN-…`
   namespace was created ad hoc when R192 (Coverity MISSING_BREAK)
   and R204 (Coverity DEADCODE) shipped. With R205, all rules
   share the `bu-` namespace; the `cov-` variant is gone. `cov-0001`
   (MISSING_BREAK, R192) remains for backward compatibility — its
   alias key still points to the same Plumb rule.

3. **Test class name taxonomy** (R205 design decision). R205
   decided to keep test class names like `R204PmdBatchTest` /
   `R204SonarBatchTest` even though the source-tool prefix is
   now banned from rule names. Reason: test class names are
   organizational (they group tests by source), not part of
   any rule's identity. Removing them would lose a useful
   category label without any benefit.

4. **JavaParser 3.26 BOM gotcha** (R205 lessons). 16 of the
   16 R205-renamed files acquired a UTF-8 BOM (`\ufeff`) at
   the start, which `javac` rejects: "非法字符: '\ufeff'".
   The fix is to strip the first 3 bytes
   (`EF BB BF`) from the file. R205's pipeline now explicitly
   strips BOMs after every file-rename operation.

## 7. Files affected

```
16 main checkers renamed + content rewritten:
  plumb-style/src/main/java/org/plumb/style/
    ExtendsObjectCheck.java
    EmptyMethodInAbstractCheck.java
    UnnecessaryReturnCheck.java
    LocalVariableCouldBeFinalCheck.java
    CommentDefaultAccessCheck.java
    ArrayHashCodeAndToStringCheck.java
    CaseInsensitiveComparisonCheck.java
    CatchNpeCheck.java
    SynchronizationOnStringOrBoxedCheck.java
    BooleanMethodReturnCheck.java
    MutableStaticShouldBeFinalCheck.java
    LocaleDependentCaseConversionCheck.java
    StringBuilderConcatenationCheck.java
    UselessObjectCheck.java
    IntDivisionCastToDoubleCheck.java
    DeadcodeCheck.java

4 test files updated (class references, method names, comments):
  plumb-style/src/test/java/org/plumb/style/
    R204PmdBatchTest.java
    R204SonarBatchTest.java
    R204SpotBugsBatchTest.java
    R204CoverityDeadcodeTest.java

1 registry updated (16 constants + 16 def() calls + 16 alias keys):
  plumb-rules/src/main/java/org/plumb/rules/RuleRegistry.java
```

Backup: `D:\work\tmp\r205_done\java_source\` (21 files, ~120KB).

## 8. Cumulative impact (post-R205)

| Metric | Pre-R205 | Post-R205 |
|--------|----------|-----------|
| Total tests | 1122 | 1122 |
| Total rule count | 49 | 49 |
| Rules with tool-prefix class name | 16 (R204) | 0 |
| Rules with tool-prefix UUID | 33 (R166/R169/R170/R171/R176/R177/R180/R181/R182/R183/R197-R203/R204) | 16 (earlier rounds) |
| Rules with tool-prefix RuleId | 33 | 16 |

R205 is the start of a long-running deprefixing effort. Future
rounds (R206+) will follow the new convention from the start.
Earlier-round rules (R166-R203) are still pre-R205 and would
require their own pass to fully deprefix; that's a follow-up
round (R206 or later) once the next batch of new rules is
delivered.
