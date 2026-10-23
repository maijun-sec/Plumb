# Operational Pitfalls — Plumb Juliet Coverage Test

> Verified "gotchas" that wasted hours during R720 → R759-E.
> Read this **before** writing any new checker, scan script, or eval pipeline.

This file is organized by category:

- [§1 Engine integration gotchas](#1-engine-integration-gotchas)
- [§2 Source reading & IR quirks](#2-source-reading--ir-quirks)
- [§3 Bulk scan & invocation](#3-bulk-scan--invocation)
- [§4 Eval pipeline quirks](#4-eval-pipeline-quirks)
- [§5 Encoding & file-write gotchas](#5-encoding--file-write-gotchas)
- [§6 Diagnostic sequence for 0% Recall CWE](#6-diagnostic-sequence-for-0-recall-cwe)

---

## 1. Engine integration gotchas

### 1.1 Silent registration of nested checkers

**Symptom**: A rule's yaml whitelist entry is present, but Plumb never emits that rule id in findings. The CWE is at 0% Recall but you can't see why.

**Root cause** (Lesson 1055): A checker class file existing at
`plumb-dfa/src/main/java/.../checkers/Cwe833DeadlockChecker.java` does **NOT**
mean it's instantiated in `ScanRunner.buildDfaCheckers()`. Even nested
checkers inside aggregator classes like `ClassThreadChecker` — if the
aggregator itself is not added to `buildDfaCheckers()`, ALL 5 nested
checkers silently stop firing.

**Real example (R759-E)**: `DeadlockChecker.java` existed but was never
registered. YAML whitelist `plumb.dfa.deadlock-ab-ba` matched nothing. The
CWE was at 0% from R720 → R759-D before we noticed. Adding 1 line
`new DeadlockChecker(ctx, ...)` in `ScanRunner.buildDfaCheckers()` made
all 4 missing-deadlock findings appear.

**Diagnostic**:

```powershell
# 1. Confirm yaml has the rule id (with `plumb.` prefix!)
Select-String -Path r720_full_strategy.yaml -Pattern 'dfa.cweXXX|infinite-loop|deadlock'

# 2. Confirm checker class file exists
Get-ChildItem .\plumb-dfa\src\main\java\org\plumb\dfa\checkers\ -Filter 'CweXXX*.java'

# 3. Confirm checker is INSTANTIATED in buildDfaCheckers()
Select-String -Path .\plumb-main\src\main\java\org\plumb\main\ScanRunner.java -Pattern 'new.*Checker\('

# If (3) returns no match, registration gap.
```

**Fix**:

```java
// In ScanRunner.buildDfaCheckers()
checkers.add(new DeadlockChecker(ctx, ...));
checkers.add(new ClassThreadChecker(ctx, ...));   // also covers 5 nested
```

### 1.2 `Finding.violation` arg-order trap

**Symptom**: Finding is emitted but yaml whitelist filter strips it (so 0 TP).

**Root cause** (Lessons 1039, 1046): yaml matches `plumb.dfa.<ruleId>`, NOT
bare `<ruleId>`. The 1st arg of `Finding.violation(...)` is the short class
name (`Cwe835InfiniteLoop`); the 2nd arg is the rule id, which must include
the `plumb.` prefix to match the whitelist.

```java
// WRONG — yaml strips this finding
finding = Finding.violation("Cwe835InfiniteLoop", "cwe835-infinite-loop", ...)

// RIGHT
finding = Finding.violation("Cwe835InfiniteLoop", "plumb.dfa.cwe835-infinite-loop", ...)
```

### 1.3 `Finding.violation(...)` 8th param is functionSig, NOT filePath

**Symptom**: After firing, the finding's `file` field is the *last function
analyzed* (e.g. `ServletMain.java`) — not the actual CWE test file. Eval
system skips these as "driver" findings.

**Root cause** (Lesson 1040): `FindingReporter.currentFunction()` leaks
the last function's metadata when `finish()` is called from a cross-TU
context. The 8th positional arg of `violation(...)` is `functionSig`, not
`filePath`.

**Fix**: Use `.withFilePath(filePath)` explicitly:

```java
// WRONG
Finding.violation(name, rule, msg, line, col, severity, ..., functionSig);

// RIGHT
Finding.violation(name, rule, msg, line, col, severity, ..., functionSig)
    .withFilePath(filePath);
```

Or for cross-TU checkers, get the path from the per-enclosing-class
source-path map: `ctx.sourcePathForEnclosingClass(className)`.

### 1.4 Stale `reported` set + cross-worklist rerun double-fires

**Symptom**: Same finding fires multiple times in a single run (visible as
duplicates in `*.ndjson`).

**Root cause**: `DfaEngine` calls `finish()` multiple times across
worklist reruns / taint drains. A per-instance `Set<String> reported`
gets cleared in `initEngine()` between runs, so previously-reported
findings fire again on the 2nd pass.

**Fix** (Lesson R759-E): Use a **static** `Set<String> reported`:

```java
private static final Set<String> reported = ConcurrentHashMap.newKeySet();

public void finish(AnalysisContext ctx) {
    // ... produce findings ...
    String key = file + ":" + line + ":" + ruleId;
    if (reported.add(key)) {
        ctx.report(finding);   // first time only
    }
}
```

---

## 2. Source reading & IR quirks

### 2.1 `SourceReader.readLines(prog)` returns empty on MergedProgram

**Symptom**: A source-reading checker fires on 0 files, even when run on
the bulk test directory. No log output, no exceptions.

**Root cause** (Lesson 1056): When the engine builds a `MergedProgram` for
cross-TU analysis, the synthetic `<merged>` program has
`sourcePath=null`. `SourceReader.readLines(prog)` returns empty Optional
on this stub, and the checker silently no-ops every file.

**Real example (R758)**: `LooseClassChecker` family (TrapdoorChecker,
AuthBypassChecker) called `readLines(prog)` and reported 0 CWE-510 / 506
findings from R720 → R757. R758 fixed it for LooseClassChecker only.

**Fix**: Switch to `SourceReader.readAllFiles(ctx)`, which walks the
per-enclosing-class source-path map registered by the engine via
`AnalysisContext.registerSourcePath`. Every cross-TU-aware source-reading
checker MUST use this.

```java
// WRONG — empty on MergedProgram
Optional<String> content = SourceReader.readLines(prog);

// RIGHT — walks per-class source paths
Map<String, String> fileContents = SourceReader.readAllFiles(ctx);
for (var entry : fileContents.entrySet()) {
    // analyze entry.getValue() with filePath = entry.getKey()
}
```

### 2.2 TAC IR dest type is `UNKNOWN` for `UNARYOP` / `BINOP`

**Symptom**: A `Range.ofType(dest.type())` call throws or produces
empty range, silently dropping findings for non-int types.

**Root cause** (Lesson 1052): For `UNARYOP` and `BINOP` instructions, the
dest's type is sometimes `TacType.UNKNOWN` (the type inference hasn't
fully resolved yet). Falling back to `dest.type()` returns UNKNOWN,
and `Range.ofType(UNKNOWN)` returns the default int range, which is
*wrong* for byte/short/char/boolean operands.

**Fix**: Always fall back to the operand's type first:

```java
// WRONG — dest.type() may be UNKNOWN
Range r = Range.ofType(dest.type());

// RIGHT — fall back to lhs1.type() if dest is UNKNOWN
TacType t = dest.type() == TacType.UNKNOWN ? lhs1.type() : dest.type();
Range r = Range.ofType(t);
```

**Real impact (R759-D)**: 1-line fix in
`UnderflowConstraintChecker.sinkPredicate()`. CWE-191 single-file
Recall went 96.64% → 100% (+72 TP, -18 FP).

### 2.3 Same-enclosing-class pair filter for cross-class heuristic

**Symptom**: A cross-TU data-flow checker produces ghost matches — it
fires on identifier pair comparisons that share names across files but
shouldn't be linked.

**Root cause** (Lesson 1057): When you use string-equality on identifier
names (e.g. `lockNameA == lockNameB`), two unrelated files that happen
to use the same identifier produce false AB-BA / data-flow matches.

**Real example (R759-E)**: Every Juliet CWE-833 file uses
`BAD_NUMBER1_REENTRANTLOCK` as the lock identifier. Without filter,
pair-comparison fires across all files → 200+ ghost AB-BA.

**Fix**: Filter pair comparisons to same `enclosingClass`:

```java
// WRONG — fires on every cross-file pair
if (sigA.lockName.equals(sigB.lockName)) report();

// RIGHT — scope to same enclosing class
if (sigA.enclosingClass.equals(sigB.enclosingClass) &&
    sigA.lockName.equals(sigB.lockName)) report();
```

### 2.4 Dedup key collapses to `<merged>` under MergedProgram

**Symptom**: A checker with per-program dedup only reports the FIRST
program's findings; all other programs are silently dropped.

**Root cause** (Lesson R759-E): `prog.typeName()` returns the literal
string `"<merged>"` for the synthetic MergedProgram. If your dedup key
is `(prog.typeName(), sigA, sigB)`, every program's pairs collide on
the same key. The map keeps the first-write-wins, so only the first
program's findings are ever stored.

**Fix**: Key on `(enclosingClass, signature)` tuple instead of program:

```java
// WRONG — collapses to "<merged>"
String key = prog.typeName() + "|" + sigA + "|" + sigB;

// RIGHT — distinct per class
String key = enclosingClass + "::" + sigA + "::" + sigB;
```

---

## 3. Bulk scan & invocation

### 3.1 JDK 25 + PowerShell 7 native warning trap

**Symptom**: A `java -jar` invocation appears to hang; bash subshell
captures `LASTEXITCODE=1` even though java ran successfully. You spend
hours debugging perceived hangs, killing finished processes.

**Root cause** (Lesson 1058): With `--enable-native-access=ALL-UNNAMED`,
JDK 25 outputs to stderr:

```
WARNING: A restricted method in java.lang.System has been called
```

PowerShell 7 wraps stderr lines containing the word "WARNING" as
`RemoteException`, propagates as command exit. Bash subshell captures
`LASTEXITCODE=1` even when java ran fine and the real exit was 0.

**Fix**: Always invoke via `cmd /c` with explicit log redirection:

```powershell
# WRONG — PS7 intercepts stderr exit code
& 'D:\jdk-25.0.1\bin\java.exe' -jar plumb.jar --foo | Out-Null

# RIGHT — cmd /c isolates stderr handling
cmd /c "D:\jdk-25.0.1\bin\java.exe -jar plumb.jar --foo > log 2>&1"
$LASTEXITCODE          # java's real exit code
Get-Content log        # full output
```

### 3.2 PowerShell heredoc with `&` and brackets breaks

**Symptom**: A multi-line PowerShell script with `&` or unescaped
`{}` characters fails to parse or executes only partially.

**Root cause**: PowerShell's interactive parser is fragile with complex
multi-line content. Bracket-matching and `&` operator interact badly
with here-strings.

**Fix**: Write the script to a `.ps1` file first, then invoke it:

```powershell
# WRONG — heredoc with & and {} breaks
$script = @"
& java -jar foo.jar
if ($x -gt 0) { ... }
"@
Invoke-Expression $script

# RIGHT — write to file
@'
& java -jar foo.jar
if ($x -gt 0) { ... }
'@ | Set-Content D:\tmp\run.ps1
& D:\tmp\run.ps1
```

### 3.3 PowerShell -Raw UTF-8 corruption of em-dash

**Symptom**: Maven compile fails with:

```
编码 UTF-8 的不可映射字符 (0xE2 80)
```

**Root cause** (Lesson 1059): Heredoc strings containing `—` (U+2014)
em-dash, when written via `Set-Content` with default encoding, become
`0xE2 0x80 0x3F` (last byte replaced with `?`).

**Fix**: Either use ASCII-only `--` in source code comments, or
post-process the written file to repair the bytes:

```powershell
# Scan bytes for 0xE2 0x80 0x3F and replace with 0x2D 0x2D (--)
$bytes = [System.IO.File]::ReadAllBytes($file)
$out = New-Object 'System.Collections.Generic.List[byte]'
$i = 0
while ($i -lt $bytes.Length) {
    if ($i + 2 -lt $bytes.Length -and
        $bytes[$i] -eq 0xE2 -and $bytes[$i+1] -eq 0x80 -and $bytes[$i+2] -eq 0x3F) {
        $out.Add(0x2D); $out.Add(0x2D)
        $i += 3
    } else { $out.Add($bytes[$i]); $i++ }
}
[System.IO.File]::WriteAllBytes($file, $out.ToArray())
```

### 3.4 Background tasks time out at 25 min default

**Symptom**: A long-running scan or eval hits the bash-tool 25-min
foreground default; bulk scans need ~8 hours.

**Fix**: Use `run_in_background=true` to start, then `task_query` /
`task_output` to poll. Set a self-reminder via `mavis cron self` to
resume the conversation.

---

## 4. Eval pipeline quirks

### 4.1 Per-CWE minimal yaml, not full strategy

**Symptom**: Per-CWE Recall numbers don't match your expectations; FP is
inflated; precision looks low.

**Root cause**: `r720_full_strategy.yaml` enables 399 rules, many of
which produce coincidental TP / FP noise on Juliet's specific patterns.
For per-CWE evaluation, you need a minimal yaml that only enables rules
whose `Finding.rule` actually fires for that CWE.

**Fix**: Use `cwe_to_rules_v2.py` to generate per-CWE yamls from
Plumb's actual emission:

```bash
python scripts/cwe_to_rules_v2.py \
  --rule-cwe-map data/plumb_rule_to_cwe.csv \
  --output-dir configs/
```

The output `CWE<N>.yaml` has only the rules Plumb actually emits for
that CWE. This is the canonical eval input.

### 4.2 Whitelist-dir vs baseline-dir semantics

`eval_findings_strict.py` takes two directories:

- `--whitelist-dir`: the new round's findings, used to compute **TP/FP per CWE**
- `--baseline-dir`: the reference round's findings, used to compute **delta** (or for sanity check)

A round's `--baseline-dir` is the previous round's `--whitelist-dir`.
For R759-E incremental eval, the right invocation is:

```powershell
python scripts/eval_findings_strict.py `
  --whitelist-dir D:\tmp\plumb_test\r759e_eval `     # R759-E
  --baseline-dir  D:\tmp\plumb_test\r759d_eval `     # R759-D (prior round)
  --out-dir       D:\tmp\plumb_test\r759e_eval_out
```

The script does NOT diff per-finding; it just compares per-CWE
TP/FP/precision numbers from each side. Use `r759e_delta.py` for the
delta table.

### 4.3 Re-running a scan doesn't always re-populate ndjson

**Symptom**: You re-run a scan expecting new findings, but the ndjson
file is unchanged. The file's `LastWriteTime` is old.

**Root cause**: `--out` may default to a path that already exists, and
Plumb may not truncate / overwrite. Or the cfg you passed is identical
to the previous run (e.g. mtime on yaml is the same → engine cache hit).

**Fix**: Always pass an explicit `--out` path; delete the existing
file before re-running. Avoid engine caches by passing a unique
`--cache-dir` per scan.

### 4.4 Eval-table TP/FP drops are not always regressions

**Symptom**: After enabling a new rule, the per-CWE TP count goes DOWN.

**Root cause**: The new rule may *displace* a coincidental TP from
another rule. E.g. R759-E: adding `deadlock-ab-ba` made
`dfa.cwe397-generic-throw-exception` no longer fire on the same file,
removing -4 accidentals. Net effect: -4 TP from the coincidental source,
+2 TP from the new genuine source. Eval-table sees -4.

**Fix**: Always cross-reference `r759e_delta.py` against
`r759e_report_data.py` to identify *which* rules shifted. The genuine
new findings (line==-1 deadlock-ab-ba, or new cwe835-infinite-loop with
`tp` from the new rule) are real; the displaced coincidentals are
*good* removals.

### 4.5 Driver files, support files, unknown filePath

`eval_findings_strict.py` skips:

- `Main.java` and `ServletMain.java` (driver / harness)
- Any path containing `testcasesupport/` (test infrastructure)
- File == `"unknown"` (Plumb didn't fill filePath)
- Findings whose `cwe` field doesn't overlap the file's CWE directory

These skips are correct (Juliet's test harness is noise, not signal),
but they mean **the eval-table TP/FP counts are NOT a true reflection
of Plumb's raw output**. To get raw output, run with
`--whitelist-dir-only` or remove the strict filter.

### 4.6 Ground truth CSV column semantics

`data/juliet_testcases_detail.csv` columns:

- `filename`: relative to `D:\work\tmp\Juliet_Java\src\testcases\`
- `cwe_id`: digits-only (`90`, not `CWE-90`)
- `cwe_name`: e.g. `LDAP_Injection`
- `flaw_line` / `fix_line`: line numbers in the test file
- `bad` / `good`: column indicating positive/negative variant
- `sink` / `source`: method names

`eval_findings_strict.py` uses `cwe_id` and `bad`/`good` to classify
TP/FP. Findings on `bad` flow are TP; findings on `good` flow are FP.

---

## 5. Encoding & file-write gotchas

### 5.1 PowerShell `Get-Content | Set-Content` silently corrupts CJK

**Symptom**: After a `Get-Content` + filter + `Set-Content` pipeline,
Chinese characters in Java source comments become `???` or `?` with a
`0x3F` byte. Maven compile fails on UTF-8.

**Root cause** (general Windows shell trap): PowerShell 5.1 defaults
to system ANSI codepage. Without `-Encoding UTF8`, `Get-Content`
mis-decodes UTF-8 multi-byte sequences as ANSI, and `Set-Content`
writes the corrupted data back. **Silent** (no error, no warning).

**Fix**: Always use `Read` / `Write` / `Edit` tools for source file
modification. They operate in UTF-8 and bypass shell encoding entirely.

For batch file modifications that dedicated tools can't express
efficiently, generate a Python script with `encoding='utf-8'`.

### 5.2 PowerShell `Set-Content` defaults to UTF-16 LE with BOM

**Symptom**: A file written via `Set-Content` then read by Maven shows
`MalformedInputException` or `UTF-8 stream has invalid bytes`.

**Root cause**: PowerShell's `Set-Content` default is `Default`
encoding — UTF-16 LE with BOM on Windows.

**Fix**: Always specify `-Encoding UTF8` (note: PS 5.1's `-Encoding UTF8`
adds a BOM; PS 7+ is BOM-free). Or use the dedicated `Write` tool.

---

## 6. Diagnostic sequence for 0% Recall CWE

When a CWE is at 0% Recall despite the rule being in the yaml whitelist:

1. **Check yaml whitelist contains the rule id** (bare + `plumb.` prefix):
   ```powershell
   Select-String -Path r720_full_strategy.yaml -Pattern 'dfa.cweXXX|infinite-loop|deadlock'
   ```

2. **Check if rule is in `plumb_rule_to_cwe.csv`**: maps R601 un-index → CWE id;
   ensures `convertUnifiedFinding` produces the right `Finding.rule`.

3. **Check if checker is instantiated in `buildDfaCheckers()`**:
   ```powershell
   Select-String -Path ScanRunner.java -Pattern 'new.*Checker\('
   ```
   Search for the specific checker class. **Critical for nested checkers**:
   aggregator class registration covers all (Lesson 1055).

4. **Check if checker class file exists**:
   ```powershell
   Get-ChildItem .\plumb-dfa\src\main\java\org\plumb\dfa\checkers\ -Filter 'CweXXX*.java'
   ```

5. **Check if checker output reaches FindingReporter**: scan stub shows
   `checkerName` / `ruleId` debug println.

6. **Check `Finding.violation` arg-order**: yaml whitelist matches
   `plumb.dfa.<ruleId>`, NOT bare `<ruleId>` (Lesson 1046, recurring trap).

7. **Check MergedProgram vs source-path**: `SourceReader.readLines(prog)`
   empty for `<merged>` stub. Switch to `readAllFiles(ctx)` (Lesson 1056).

8. **Check dedup key**: under MergedProgram, `prog.typeName()` =
   `"<merged>"`, so `(<typeName>, <sigA>, <sigB>)` collapses all pairs.
   Key on `(enclosingClass, signature)` (Lesson 1057).

9. **Check parent filePath propagation**: `FindingReporter.currentFunction()`
   leaks the last function analyzed. Use `ctx.sourcePathForEnclosingClass`
   instead (Lesson 1040).

10. **Check `dest.type()` for UNARYOP/BINOP**: `dest.type()` may be
    `TacType.UNKNOWN`; fall back to `lhs1.type()` before `Range.ofType(t)`
    (Lesson 1052).

11. **Check the regex / pattern** is broad enough. Juliet's
    `CWE506_*.java` uses `.equals("admin.<fqdn>")` not `.equals("admin")`;
    `CWE835` has 6 positive variants (1 trivial + 5 with bounded/unbounded
    conditions) not 1.

12. **Run the per-CWE minimal yaml** (not `r720_full_strategy.yaml`):
    ```powershell
    python scripts/cwe_to_rules_v2.py --rule-cwe-map data/plumb_rule_to_cwe.csv --output-dir configs/
    java -jar plumb-main-*.jar --config configs/CWE<N>.yaml --root <juliet CWE root> --out <out>.ndjson
    ```

13. **Inspect the per-finding ndjson** to see which rules fire and on
    which files:
    ```bash
    python -c "import json; [print(json.loads(l)['file'], json.loads(l)['rule_id']) for l in open('out.ndjson')]"
    ```

If the rule still doesn't fire after all 13 checks, the issue is likely
in Plumb's TAC IR (e.g. `MONITORENTER` instruction missing for
`synchronized`, or variable-bounded loops without value-range proof).
These are out of regex-checker scope — would need a new IR instruction
or value-range analysis pass.

---

## End of PITFALLS.md

For a more structured per-Round lessons list, see `MEMORY.md` in the
parent agent's memory file. PITFALLS.md covers the operational traps
that re-occur across rounds.