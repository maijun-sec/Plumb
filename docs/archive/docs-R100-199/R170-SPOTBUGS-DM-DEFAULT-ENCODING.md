# R170 — SpotBugs DM_DEFAULT_ENCODING Mirror

**Date:** 2026-07-31
**Status:** Complete, 6/6 tests pass, 0 regression
**Rule id:** `bu-0504-spotbugs-dm-default-encoding`
**CWE:** CWE-176 (Improper Handling of Unicode Encoding)

## 1. Goal

Mirror SpotBugs's `DM_DEFAULT_ENCODING` bug pattern in
Plumb, following the same narrow-whitelist philosophy as
R166-R169. This is the **first SpotBugs rule** in the
Plumb rule set — the pilot of the **SpotBugs source
integration** (Phase 4 in the post-R162 big-picture plan).

## 2. SpotBugs rule description

From `spotbugs.jar!/messages.xml`:
> Found a call to a method which will perform a byte to
> String (or String to byte) conversion, and will assume
> that the default platform encoding is suitable. This
> will cause the application behavior to vary between
> platforms. Use an alternative API and specify a charset
> name or Charset object explicitly.

Canonical bad examples:

```java
new FileReader(f)                    // platform default charset
new FileWriter(f)                    // platform default charset
new InputStreamReader(in)            // platform default charset
new OutputStreamWriter(out)          // platform default charset
new String(bytes)                    // platform default charset
new String(bytes, 0, len)            // platform default charset
```

Canonical good examples (have explicit charset):

```java
new FileReader(f, StandardCharsets.UTF_8)
new String(bytes, StandardCharsets.UTF_8)
```

## 3. Plumb detection pattern

`plumb-dfa` checker — TAC IR linear scan (same as
R124 / R166 / R169):

1. For every `NEW` instruction in every function.
2. Read `ins.ctorType` and `ins.args.size()`.
3. Look up the simple name (last `.`-segment) of the
   type.
4. Match against the default-encoding whitelist:
   - `String` with arity 1 (byte[]) or 3 (byte[], int, int)
   - `FileReader` / `InputStreamReader` with arity 1
   - `FileWriter` / `OutputStreamWriter` with arity 1
5. The 2-arg `String(byte[], Charset)` etc. variants
   are NOT fired (the user has specified a charset).

`simpleName()` helper handles both the simple-name
(`"FileReader"`) and the FQN (`"java.io.FileReader"`)
forms that JavaParser's `oce.getType().asString()` can
return depending on imports.

## 4. Innovation points (4)

1. **SpotBugs rules can be mirrored with the same
   narrow-whitelist template as PMD** (R170 design).
   Despite SpotBugs's reputation for bytecode-level
   detection, a sizable fraction of its bug patterns
   are observable at the source level. R170 demonstrates
   that the same Plumb `Checker` interface can be
   re-used: TAC IR linear scan + small whitelist
   + targeted finding report. No new infrastructure
   needed.

2. **The simple-name / FQN duality is a SpotBugs-style
   "type resolver" workaround** (R170 implementation).
   SpotBugs has full Soot type resolution; Plumb
   currently has none. The `simpleName()` helper
   (last `.`-segment extraction) is a minimal
   approximation that covers both the imported form
   (`new FileReader(f)` → `"FileReader"`) and the
   fully-qualified form (`new java.io.FileReader(f)`
   → `"java.io.FileReader"`). The same workaround is
   re-usable for any future SpotBugs rule that needs
   type-name matching.

3. **Constructor arity encodes "charset presence" for
   JDK I/O types** (R170 design observation). For
   `String`, `FileReader`, etc., the "no charset"
   overload has a *strictly smaller* arity than the
   "with charset" overload (e.g. 1 vs 2, 3 vs 4). So
   arity alone, without inspecting arg types, is
   sufficient to flag the default-encoding case for
   the 1-arg whitelist. This works because the JDK
   was designed so that the no-charset overload is
   the "fallback" — it has fewer parameters and is
   the one that triggers default encoding.

4. **R170 is the first rule to cover the
   `new String(byte[])` family** (R170 surface). The
   Plumb rule set already had
   `HardcodedCredentialCheck` (R124) for
   `char[]/byte[]` literals but no rule for the
   "platform-default decode" anti-pattern itself.
   R170 closes that gap. Combined with the existing
   `InsecureRandomCheck` (R119b) and
   `PredictableRandomSeedCheck`, R170 means Plumb now
   covers the three most common CWE-330/176/798
   surfaces in a single line of YAML config.

## 5. Tests

| Test | Asserts |
|---|---|
| `firesOnFileReaderWithoutCharset` | `new FileReader(f)` → finding |
| `firesOnInputStreamReaderWithoutCharset` | `new InputStreamReader(in)` → finding |
| `firesOnStringFromByteArray` | `new String(bytes)` → finding |
| `firesOnStringFromByteArrayWithOffset` | `new String(b, off, len)` → finding |
| `noFindingForFileReaderWithCharset` | `new FileReader(f, UTF_8)` → no finding |
| `noFindingForUnrelatedConstructor` | `new ArrayList<>()` → no finding |

All 6 pass, 0 regression in the 358-test plumb-dfa
suite.

## 6. Files

- New: `plumb-dfa/src/main/java/org/.../DefaultEncodingCheck.java`
  (6620 bytes, ~140 lines)
- New: `plumb-dfa/src/test/java/.../R170SpotBugsDefaultEncodingTest.java`
  (3644 bytes, 6 tests)
- New: `plumb-style/src/main/java/.../SuperfluousInstanceofCheck.java`
  (5475 bytes, ~120 lines)
- New: `plumb-style/src/test/java/.../R171SpotBugsSuperfluousInstanceofTest.java`
  (2894 bytes, 4 tests)
- Modified: `plumb-rules/src/main/java/.../RuleRegistry.java`
  (added 2 new rule constants + 2 register calls)
- Modified: `plumb-style/src/main/java/.../StyleCheckRegistry.java`
  (added `register(new SuperfluousInstanceofCheck())`)

## 7. Disaster backups

- `D:\work\tmp\r170_done\java_source\DefaultEncodingCheck.java` (6620 bytes)
- `D:\work\tmp\r170_done\java_source\R170SpotBugsDefaultEncodingTest.java` (3644 bytes)
- `D:\work\tmp\r170_done\java_source\SuperfluousInstanceofCheck.java` (5475 bytes)
- `D:\work\tmp\r170_done\java_source\R171SpotBugsSuperfluousInstanceofTest.java` (2894 bytes)

## 8. Cumulative R148 series (29 rounds, 1 rolled back)

- 14 metric wins: R148c / R148d / R148g / R151a / R152a / R153 / R151b / R152b / R156 / R157 / R158 / R159 / R161 / R162
- 1 validation: R149b
- 14 infra: R148e / R148f / R148h / R148i / R149 / R150 / R155 / R157 (4) / R158 (5) / R159 (2) / R161 (4) / R162 (3) / R163 (alias-aware) / R164 (path condition) / R165 (path condition extension) / R166 (PMD mirror template) / R167 (PMD EmptyControlStatement) / R168 (PMD EmptyBlock) / R169 (PMD HardcodedIP) / **R170 (SpotBugs DM_DEFAULT_ENCODING) / R171 (SpotBugs SIO_SUPERFLUOUS_INSTANCEOF)**
- 1 rolled back: R148h+2
- 379 tests / 0 fail / 0 error / 1 skip (R151 expected disabled)

## 9. What's next (B → C → D → E)

- **R172+ (B continues)** More SpotBugs rules:
  - `DMI_BIGDECIMAL_CONSTRUCTED_FROM_DOUBLE` (data flow, TAC IR)
  - `DMI_RANDOM_USED_ONLY_ONCE` (data flow, requires alias analysis)
  - `RV_RETURN_VALUE_IGNORED` (data flow, requires result-use analysis)
  - `NP_NULL_PARAM_DEREF` (data flow, requires param-null analysis)
- **C (SonarQube)**: pending user source path
- **D (Coverity)**: `D:/wsl/tools/cov-analysis-linux64-2023.6.0/doc/en`
- **E (Juliet)**: `D:/work/tmp/Juliet_Java` 113 CWE dirs / 46803 .java files
