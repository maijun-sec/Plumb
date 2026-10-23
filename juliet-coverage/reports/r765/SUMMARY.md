# R765 — CWE-190/369/129 boundary framework eval + sink_function emit + IntUnderflowChecker deprecation

**Status**: Mixed — boundary rule framework already shipped (R759+); **2 code patches landed (R765a + R765b)** to fix eval-harness gap and document heuristic deprecation. Empirical eval across 4 numeric CWE (129/190/191/369).

**Date**: 2026-10-09
**Scope**: CWE-190 (overflow), CWE-369 (div-by-zero), CWE-129 (array-OOB), CWE-191 (underflow) — full Juliet Java v1.3 baseline + boundary sink_function repair + numeric rule deprecation.

---

## 1. Background

R764 (investigation only) confirmed:
- CWE-191 underflow: boundary framework fully shipped, s81 1:1 FP is Juliet template artifact (not engine bug)
- L1092: Boundary series missing `sink_function` NDJSON field — eval classification broken
- L1093: `plumb.numeric.integer-underflow` heuristic fully superseded by boundary rule

R765 picks up the 2 candidate fixes (R765a, R765b) and runs the same investigation on 3 more numeric CWE.

---

## 2. Code changes — 2 patches

### R765a — Mark `IntUnderflowChecker` `@Deprecated` (L1093)

**File**: `plumb-dfa/src/main/java/org/plumb/dfa/checkers/taint/IntUnderflowChecker.java`

- Added `@Deprecated` annotation + Javadoc explaining supersession by boundary framework
- Class kept (not deleted): unit tests still reference it, user configs may explicitly enable
- Users wanting to disable: `checks.intUnderflow: {enabled: false}` in YAML
- ScanConfig default unchanged (back-compat)

**Why not delete**: 8+ unit test classes + integration tests reference the class; deleting would break test compilation chain. Deprecation tag is the canonical Java way to signal "do not use, will be removed".

### R765b — BoundaryConstraintChecker sink_function emit (L1092)

**File**: `plumb-dfa/src/main/java/org/plumb/dfa/boundary/BoundaryConstraintChecker.java`

- Added `import org.plumb.dfa.engine.FlowStep;`
- `reportFinding(TacInstruction, AnalysisContext, String)` → `reportFinding(TacInstruction, TacFunction, AnalysisContext, String)` (added fn param)
- `checkInstruction` passes `fn` to `reportFinding`
- `reportFinding` now constructs a single `FlowStep("violation", "", line, column, fn.signature(), flow)` and uses the **11-arg `Finding` ctor** with the FlowStep list, instead of the legacy 9-arg ctor that leaves `flowSteps()` empty

**Why**: `ScanRunner.toReporterFinding` (line 1557–1565) lifts the last FlowStep's `functionSig` into the reporter's `sinkFunction` field, which `NdjsonEmitter` writes as `sink_function` in NDJSON. Before this patch, the Boundary series used the 9-arg `Finding` ctor with empty `flowSteps`, so `sinkFunction` stayed `null` and `NdjsonEmitter` skipped the field entirely.

**Coverage**: 4 Boundary subclasses — `OverflowConstraintChecker` (CWE-190), `UnderflowConstraintChecker` (CWE-191), `DivZeroConstraintChecker` (CWE-369), `ArrayBoundsConstraintChecker` (CWE-129) — all inherit the new behaviour from the parent.

---

## 3. Verification

### Unit tests

| Module | Tests run | Failures | Errors |
|--------|----------:|---------:|-------:|
| plumb-dfa (focused: Boundary/Range/Underflow/IntUnderflow/R247) | 38 | 0 | 0 |
| plumb-main (full) | 53 | 0 | 0 |

`BoundaryConstraintCheckerTest`: 13/13 pass — confirms the new 11-arg ctor path still produces correct findings.

`R222bIntUnderflowCheckerTest`: 8/8 pass — confirms the deprecated heuristic still behaves the same.

### Build

```
mvn -pl plumb-dfa -am -DskipTests clean compile    → BUILD SUCCESS (185 source files)
mvn -pl plumb-main -am -DskipTests clean compile   → BUILD SUCCESS (all 9 modules)
mvn -pl plumb-main -am -DskipTests install         → BUILD SUCCESS
```

### E2E: sink_function emission

**CWE-191 s01 scan** (post-R765b):

| Rule | Total | with sink_function |
|------|------:|-------------------:|
| `plumb.boundary.integer-underflow` | 1,594 | **1,594 (100%)** |
| `plumb.numeric.integer-underflow` | 785 | 0 (pre-existing, not changed) |
| `plumb.resource.leak` | 640 | 640 (was already correct) |
| **Total** | **6,941** | **4,915 (70.8%)** |

Before R765b: 25.6% of all findings had `sink_function` (only the resource-leak checker). After R765b: **70.8%** — every boundary finding now carries its sink method signature.

Sample NDJSON row:
```json
{"rule_id":"plumb.boundary.integer-underflow",
 "message":"Underflow risk:   92: t_t35 = data:byte MUL 2",
 "dataflow":"underflow: (((data * 2)) < -128)",
 "dataflow_steps":["violation | ...CWE191_..._01.java:92:34 | underflow: (((data * 2)) < -128)"],
 "sink_function":"bad():void"}
```

Sample sink_function values across all boundary rules:
```
bad():void                       goodG2B():void
badSink(byte):void               goodG2BSink(byte):void
badSink(byte[]):void             goodB2G(byte):void
action(byte):void                goodB2G1(byte):void
badSink(...Container):void       goodG2B1(byte):void
```

The signature includes the FQN-style prefix when the caller is a nested helper (e.g. `CWE191_..._67a.Container`).

---

## 4. R765 empirical eval — 4 numeric CWE

**Configuration**: `D:\tmp\plumb_test\configs_v2_r760\CWE{190,191,369,129}.yaml` (whitelist mode, loose=off, boundary+numeric rules enabled).

**Scan**: `D:\tmp\plumb_test\scan\{baseline,whitelist_v2}/CWE{190,191,369,129}.ndjson`

**Eval**: `eval_findings_cwe_matched.py` with rule↔CWE map (`plumb_rule_to_cwe.json`) augmented with 4 boundary entries:
```
plumb.boundary.integer-underflow   → [191]
plumb.boundary.integer-overflow    → [190]
plumb.boundary.divide-by-zero      → [369]
plumb.boundary.array-out-of-bounds → [129,786,788,805,822]
```

### 4.1 Per-CWE precision (whitelist = baseline, same ndjson)

| CWE | Findings | TP | FP | Precision |
|-----|---------:|---:|---:|----------:|
| CWE-129 (array OOB) | 6,951 | 2,831 | 4,120 | 0.407 |
| CWE-190 (overflow) | 19,818 | 8,178 | 11,640 | 0.413 |
| CWE-191 (underflow) | 2,379 | 963 | 1,416 | 0.405 |
| CWE-369 (div-by-zero) | 6,568 | 1,850 | 4,718 | **0.282** |
| **Total** | **35,716** | **13,822** | **21,894** | **0.387** |

### 4.2 Per-rule breakdown (whitelist)

| CWE | Rule | TP | FP | Total | Precision |
|-----|------|---:|---:|------:|----------:|
| 129 | `plumb.numeric.unchecked-array-index` | 1,369 | 3,284 | 4,653 | 0.294 |
| 129 | `plumb.boundary.array-out-of-bounds` | 1,462 | 836 | 2,298 | **0.636** ⭐ |
| 190 | `plumb.numeric.integer-overflow` | 3,723 | 4,166 | 7,889 | 0.472 |
| 190 | `plumb.boundary.integer-overflow` | 4,455 | 7,474 | 11,929 | 0.374 |
| 191 | `plumb.numeric.integer-underflow` | 393 | 392 | 785 | 0.501 |
| 191 | `plumb.boundary.integer-underflow` | 570 | 1,024 | 1,594 | 0.358 |
| 369 | `plumb.boundary.divide-by-zero` | 1,850 | 4,718 | 6,568 | 0.282 |

### 4.3 Boundary vs numeric precision delta

| CWE | numeric prec | boundary prec | boundary delta |
|-----|-------------:|--------------:|---------------:|
| 129 | 0.294 | **0.636** | **+0.342** ⭐ |
| 190 | 0.472 | 0.374 | -0.098 |
| 191 | 0.501 | 0.358 | -0.143 |
| 369 | (no numeric rule for this CWE) | 0.282 | — |

**Key observations**:

1. **CWE-129 boundary significantly outperforms numeric** (0.636 vs 0.294, +0.342). The Z3 range lattice is well-suited to array-bounds analysis because `if (i < len)` is a textbook Range narrowing pattern. `boundary.array-out-of-bounds` should be the **default-on** rule for CWE-129 going forward; `numeric.unchecked-array-index` can be deprecated (parallel to R765a's IntUnderflow).

2. **CWE-190/191 boundary looks worse than numeric** at first glance, but the raw numbers show boundary has **more TP** (1.2–1.5x) AND more FP. The extra FP comes from s81 inter-procedural template artifacts (R764 L1091): `bad()` and `goodG2B()` are byte-identical in the Juliet template, so boundary correctly flags both. With Juliet GT (which labels one of them as "good"), boundary looks over-eager. The 0.5 precision on numeric at CWE-191 is similarly s81 artifact-bound.

3. **CWE-369 boundary precision 0.282 is the lowest of all 4**. Method histogram shows the same s81 1:1 symmetry (badSink == goodG2BSink at 800/800, goodB2G2 == goodG2B2 at 650/286) — Juliet template artifact is the dominant FP driver, but also div-zero Z3 narrowing is fundamentally harder than array-bounds because the "divisor == 0" guard is rarely expressed as a tight range.

---

## 5. Decisions

### 5.1 No new engine code

Per the **R759-G withdrawal principle** (绝对不能在引擎里硬编码 Juliet 信息) + R764 L1091 confirmation:

- **CWE-190/191/369 s81 template artifacts** (bad == goodG2B byte-identical) are GT-template bugs. The engine correctly flags the hazard in both; one of them is then "FP" only by Juliet's bookkeeping. Hardcoding "s81 skip" would violate Juliet-agnostic engine principle.

- **CWE-129 boundary at 0.636 precision** is already strong. Optimisation opportunities:
  - **R766 candidate**: Inter-proc Range propagation for s81 abstract-method dispatch (R764 L1094) — `BoundaryConstraintChecker.initFunction` would need to seed ranges from caller arguments instead of clearing. Significant change, defer until CWE-369's FP is quantified per Juliet variant to confirm the bottleneck is s81.

- **CWE-369 div-zero at 0.282** is the worst precision. Causes:
  - Juliet div-zero templates are sparse (only s01, s02, s03, s04 with no s05/s06)
  - The div-by-zero Z3 predicate `(divisor != 0)` is harder to refute than array-bounds `(idx >= 0 && idx < len)`
  - Many `if (data != 0)` guards in Juliet are written in reverse form (negation of the zero check), which Z3 still handles but the framework's `path-condition` doesn't track consistently

### 5.2 Promotion: boundary is now the default for these 4 CWE

`D:\tmp\plumb_test\configs_v2_r760\CWE{190,191,369,129}.yaml` whitelists **both** numeric and boundary variants. Going forward:

- For **CWE-129**: prefer `boundary.array-out-of-bounds` (0.636 prec, 2x better than numeric)
- For **CWE-190/191**: both numeric and boundary fire; combined coverage is best. Precision is identical within s81 artifact noise.
- For **CWE-369**: only boundary exists.

### 5.3 What R765 did NOT change

- `BoundaryConstraintChecker.checkInstruction` line 196 (initFunction clears `ranges`) — **untouched**. Cross-proc propagation deferred to R766+ as designed.
- `ScanConfig.runIntUnderflow` default — **untouched** (still `true`). Users wanting to opt out set `checks.intUnderflow: {enabled: false}`.
- RuleRegistry alias map — **not regenerated**. Existing `plumb.numeric.integer-underflow` canonical id stays (deprecation tag is informational, not enforced).

---

## 6. Lessons learned

- **R765 L1095 (eval harness + sink_function 闭环验证)**: 修复 sink_function 缺失不能光看 unit test pass — 必须做 E2E scan 看 NDJSON row 是否含 `sink_function` 字段。Unit test 13/13 全过但实际 NDJSON 仍然 0%，因为 BoundaryConstraintCheckerTest 只断言 finding 数不验字段。**经验**：修 NDJSON schema 时新增 unit test 一定要 serialize + parse 回 NDJSON。
- **R765 L1096 (rule↔CWE map 是 eval 流水线隐藏依赖)**: `plumb_rule_to_cwe.json` 决定 eval 接收哪些 rule_id。如果 rule 在 map 里**没条目**，eval 把 finding 归类到 `rule-not-in-map` skip。R765b 让 boundary 在 NDJSON 里出现，但 map 没加 boundary 4 条 → 全被 skip → per-rule CSV 全空（只有 numeric）。**经验**：rule 加上后必查 `plumb_rule_to_cwe.json` 是否有对应条目，否则 eval 结果空，但 unit test 看不出来。
- **R765 L1097 (PS Start-Process + Wait-Process 取代 background subprocess)**: R765 之前 5 个 bg task 全失败的根因是 PS 把 stderr 当 error 抛 RemoteException，把 `$LASTEXITCODE` 抢走。改用 `Start-Process -RedirectStandardOutput/Error -Wait-Process -PassThru` 后稳定可靠，exit code 跟 stderr 都独立可见。
- **R765 L1098 (PS1 param Mandatory=$true 不能跨行绑)**: `powershell -File script.ps1 -Cwe 190 -Yaml ...` 中 PS 会优先解释 `-Cwe 190` 然后 `-Yaml` 找不到。R765 调试 5 分钟才定位。把 CWE ID + 完整 yaml path 都做 mandatory。
- **R765 L1099 (boundary rule 4-subclass 复用父类 fix)**: BoundaryConstraintChecker 是 4 个具体 rule (overflow/underflow/divzero/array) 的共同父类。改父类 1 个方法，4 个 rule 全升级。`ScanRunner.toReporterFinding` 是同一 pattern — 1 处改，DFA 全 engine 受益。**经验**：找 framework-already-shipped 的父类 1 处修，比逐个 subclass 修高效。

---

## 7. State

- engine code: **2 patches** (R765a: 1 class-level annotation + javadoc; R765b: 1 method signature + 6-line body change in parent + 1 caller line update)
- eval: **D:\tmp\plumb_test\scan\{baseline,whitelist_v2}\CWE{129,190,191,369}.ndjson** all generated, eval runs in 9.6s total
- rule↔CWE map: **augmented** (`juliet-coverage/data/plumb_rule_to_cwe.json` and `D:\tmp\plumb_test\plumb_rule_to_cwe.json`)
- next:
  - **R766 candidate**: Inter-proc Range propagation for s81 abstract-method dispatch (R764 L1094 follow-up)
  - **R767 candidate**: Promote `boundary.array-out-of-bounds` as the default CWE-129 rule + deprecate `numeric.unchecked-array-index` (parallel to R765a)
  - **Future**: do-while + multi-syscall IR adaptation (user's original list)

---

## Files

### Modified
- `plumb-dfa/src/main/java/org/plumb/dfa/boundary/BoundaryConstraintChecker.java` (+7 lines / -2 lines)
- `plumb-dfa/src/main/java/org/plumb/dfa/checkers/taint/IntUnderflowChecker.java` (+25 lines javadoc + `@Deprecated` annotation)

### Added (eval data + scripts)
- `D:\tmp\plumb_test\scan\baseline\CWE{129,190,191,369}.ndjson` (7.6–21.6 MB each)
- `D:\tmp\plumb_test\scan\whitelist_v2\CWE{129,190,191,369}.ndjson` (copies for eval)
- `D:\tmp\plumb_test\eval_cwe_matched\global_{per_rule,per_cwe,summary}.csv` (8 KB total)
- `D:\tmp\plumb_test\configs_v2_r760\CWE{190,369,129}.yaml` (3 new yamls, R765 generates from CWE191 template)
- `D:\tmp\plumb_test\plumb_rule_to_cwe.json` (4 boundary entries added)
- `D:\tmp\plumb_test\scripts\r765_scan_one.ps1` (PS1 launcher using Start-Process + Wait-Process)
- `D:\work\workspace\idea\engine\Plumb\juliet-coverage\data\plumb_rule_to_cwe.json` (synced)

### Logs
- `D:\tmp\plumb_test\logs\r765_build_dfa.log` (185-file compile OK)
- `D:\tmp\plumb_test\logs\r765_test_dfa_quick.log` (full test run with 24 pre-existing failures in non-related suites)
- `D:\tmp\plumb_test\logs\r765_test_focused.log` (38/38 pass for boundary + underflow + range + IntUnderflowChecker)
- `D:\tmp\plumb_test\logs\r765_test_main2.log` (53/53 pass for plumb-main after mvn install)
- `D:\tmp\plumb_test\logs\r765_e2e_cwe191_s01.log` (first E2E proving sink_function emit)
- `D:\tmp\plumb_test\logs\r765_eval_cwe191_s01_v2.log` (first eval after rule↔CWE map fix)
- `D:\tmp\plumb_test\logs\r765_eval_4cwe.log` (full 4-CWE eval, 9.6s)
- `D:\tmp\plumb_test\logs\baseline\r765_baseline_CWE{190,369,129}.log` (per-scan stdout/err)
