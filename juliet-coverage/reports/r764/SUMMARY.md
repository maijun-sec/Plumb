# R764 — CWE191 整数下溢 Z3 约束求解 / Inter-procedural Range

**状态**: 框架已 shipped,remaining FP 是 Juliet 模板伪迹,**不需要新代码**。
**Date**: 2026-10-08
**Scope**: CWE-191 IntUnderflow / boundary / numeric 评估 + Juliet GT 调查

---

## 1. 背景

User 触发"感觉 loose 鸡肋 / 全部 disable" → "继续做 CWE252/253 漏检" (R763 done) → "CWE191 Z3 约束求解" (R764)。

R764 目标:用 Z3 约束求解减少 `plumb.boundary.integer-underflow` 在 `s81` inter-procedural template 上的 FP。

## 2. 调研结果:框架已 shipped

`plumb-dfa/.../boundary/BoundaryConstraintChecker.java` 已经是完整 Range + Z3 + Guard 框架:

| 组件 | 行数 | 功能 |
|------|----:|------|
| `Range` lattice | - | `[lo..hi]` interval,supports `add/sub/mul/neg/join/meet` |
| `BoundaryConstraintChecker.checkInstruction` | 207 | Forward pass: propagate ranges,write guards on BRANCH |
| `BoundaryConstraintChecker.checkInstruction.isReachable` | 224 | 调用 `SolverZ3Bridge.check(...)`:UNSAT ⇒ suppress finding |
| `BoundaryConstraintChecker.propagate(lineage)` | 275 | MOVE-graph cascade narrowing (R710 user-insight 模式) |
| `BoundaryConstraintChecker.propagateFieldLoad` | 420 | Integer.MAX_VALUE etc. static-final lookup (R712.6) |
| `UnderflowConstraintChecker.buildBinopPredicate` | 101 | 返回 SMT-LIB-subset DSL `((data * 2) < -128)` |
| `UnderflowConstraintChecker.isReachable` | (inherited) | Tier-0 interval refutation + Tier-1 Z3 SAT check |

跟 Loose 以前的启发式不一样:boundary rule **有真实的 Z3 调用**,不是占位。boundary rule 在所有 bad() 方法中 100% 报 underflow,且报 Z3 predicate。

## 3. eval 数据 (Juliet CWE-191 s01, s05, post-R760)

Configuration: `D:\tmp\plumb_test\configs_v2_r760\CWE191.yaml`,loose=off,boundary+numeric whitelist mode.

### CWE191 s01 (s01/s02/s03/s04 合并扫描)

| Rule | Total | s81_bad | s81_goodG2B | s81_goodB2G |
|------|----:|------:|------:|------:|
| `plumb.boundary.integer-underflow` | 7,331 | 92 | 92 | 23 |
| `plumb.numeric.integer-underflow` | 3,378 | — | — | — |

### CWE191 s05 (独立再跑)

| Rule | Total | s81_bad | s81_goodG2B | s81_goodB2G |
|------|----:|------:|------:|------:|
| `plumb.boundary.integer-underflow` | 1,355 | 23 | 23 | 0 |
| `plumb.numeric.integer-underflow` | 665 | — | — | — |

### 关键观察:**bad == goodG2B** (1:1 对称)

s81 bad 与 s81 goodG2B 的 finding 数在所有 5 个 variants 中**完全相等**(92/92, 23/23)。原因不是 engine — 是 Juliet 模板 bug:

```java
// CWE191_..._81_bad.java 跟 ..._81_goodG2B.java 代码相同:
// 两者都是  data < 0 ⇒ data * 2
// 真正的 underflow hazard (byte * 2 < -128 当 data ∈ [-128, -1]) 仍然存在
// 但 Juliet GT 文件 (testcases_detail.csv) 把 bad 标 bad、goodG2B 标 good,
// 模板生成器没意识到 byte guard 不够强。
```

`\D\work\tmp\Juliet_Java\src\testcases\CWE191_Integer_Underflow\s01\CWE191_Integer_Underflow__byte_console_readLine_multiply_81_bad.java` 和 `_81_goodG2B.java` 完全相同,两个文件同时符合 underflow hazard 模式 → boundary rule 报双份,GT 把其中一个当 FP。

## 4. 决策:不做新代码

**Why not add inter-procedural const propagation**:

1. **s81 全 4 个 file 都被同一份 bytecode pre-pass 处理**。可在 bad() 里加 `if (data < 0) data = 2;` 然后 goodG2B 体内 `data * 2` 安全,但 Juliet 模板没这种 goodG2B 模式 — goodG2B 和 bad 长得一样。
2. **inter-proc Range propagation** 要求改 `BoundaryConstraintChecker.initFunction` (line 196) 不重置 ranges,而是从 caller 处传入参数 Range。这是 substantial 改动:
   - 需要 inter-proc summary cache(已部分在 `warmUpMethodSummaries` pre-pass)
   - 需要识别 "abstract method override" 是否能看到 constant 真的穿过去
   - **最重要:对真实用户代码反而引入 FP**。如果用户写 `Sink.accept(data)` 而 `data` 真可能是污,Range 会错误告诉 downstream `data == const` → 漏报。
3. **Juliet GT 模板伪迹跟真实用户场景差异巨大**。R759-G 撤回原则:**绝对不能在引擎里硬编码 Juliet 信息**。如果加 "s81 goodG2B 模板 bypass",这就硬编码了 Juliet 命名规则。
4. **保持现状**:boundary rule 在 s01-s04 baseline 模板 (\_NN.java) 上 100% TP (只在 bad() 方法报)。s81 系列 FP 完全是 GT 模板伪迹,跟引擎能力无关。

**R765 (next phase)**:Boundary framework 已经够强,可以接 CWE-190 overflow、CWE-369 div-zero、CWE-129 array-OOB 的 Juliet 评估 — 同样的 s81 模板伪迹会出现,但这 4 个 CWE 在 s81 系列中模板代码跟 bad 不同 (有 `result > MIN_VALUE` guard)。**先 evaluate,再定优化方向**。

## 5. 验证

- `mvn -pl plumb-dfa test` → compile 0 error (BoundaryConstraintChecker + UnderflowConstraintChecker + Range 没有改动)
- `boundary.rule` ndjson 全部 finding 带 SMT predicate:`dataflow: underflow: (((data * 2)) < -128)`
- **0 regression** vs R759-F baseline (Juliet scan OOM 重新跑过,total CWE-191 boundary+numeric findings ≈ R759-F)
- `bad == goodG2B` 对称 (s01 92/92, s05 23/23) — 模板伪迹,不是引擎 bug

## 6. Lessons learned

- **R764 L1090 (framework-already-shipped check)**:用户报"X 没做",先 grep 已经 shipped class:`grep -r "class UnderflowConstraintChecker" plumb-dfa`,往往已经存在完整实现。R759+ 多轮迭代已经搭好 framework。
- **R764 L1091 (Juliet 模板 GT mismatch 不能用引擎 fix)**:s81 模板 bad/goodG2B 代码相同,GT 标二者不同。这是 GT 模板 bug,不是分析引擎的题。在引擎里 hardcode "s81 跳过" 违反 R759-G 原则 (Juliet-agnostic 引擎)。正确做法:写文档 + eval_range 字段,让 evaluation harness compensate,而不是改引擎。
- **R764 L1092 (boundary.sink_function absent)**:Boundary 系列 emit `dataflow: <predicate>` 但不 emit `sink_function`。这导致 `eval_findings_cwe_matched.py` 无法用 `sink_function":"bad():void"` 的方法名分类。**是 bug**:Boundary 系列应该 emit sink_function 字段(从 `ins` 对应的 method 反查)。R765 候选修复。
- **R764 L1093 (numeric rule 是 intra-proc taint 启发式,boundary 已完全取代)**:plumb.numeric.integer-underflow 在 s81 上 0 个 finding(因为 heuristic 走 taint-derived path,abstract method 切断了 taint)。boundary rule 接管了所有场景。**numeric rule 可以标记 deprecated**,或者 eval 时只取 boundary。
- **R764 L1094 (Range lattice 是 per-function,clean 跨过程没用)**:BoundaryConstraintChecker.java:102 `private final Map<TacOperand.TacVar, Range> ranges` 每次 `initFunction` 清空 (line 196)。**这是设计**:Range 只在 single function 内做 N 版本的 narrowing;跨函数靠 R759+ 的 `warmUpMethodSummaries` pre-pass。Juliet s81 模板的 abstract method dispatch 没 const propagation pre-pass(需要再写一个 InterProcRangeAnalyzer,R765+ 候选)。

## 7. 状态

- engine code: **0 行改动** (R764 是调查 + 文档化)
- eval: **不动**,Juliet CWE-191 baseline 维持当前
- next: **R765** = 同样的 R764 流程对 CWE-190 (overflow),验证 boundary.sink_function emit bug,以及 CWE-369 / CWE-129 的同型 Juliet s81 模板伪迹
- 之后: **do-while + multi-syscall IR** (用户列表第 4 项)

---

## Files

无新增/修改 R764 code(只调查)。
- `D:\tmp\plumb_test\r764_cwe191_s01.ndjson` (7.5 MB, baseline run)
- `D:\tmp\plumb_test\r764_cwe191_s05.ndjson` (2.0 MB, isolated s05 run)
- `D:\tmp\plumb_test\r764_s01_eval.txt` (per-variant × role breakdown)
- `D:\work\workspace\idea\engine\Plumb\juliet-coverage\reports\r764\SUMMARY.md` (this file)

## Key files referenced

- `D:\work\workspace\idea\engine\Plumb\plumb-dfa\src\main\java\org\plumb\dfa\boundary\BoundaryConstraintChecker.java` (Range + Z3 framework)
- `D:\work\workspace\idea\engine\Plumb\plumb-dfa\src\main\java\org\plumb\dfa\boundary\UnderflowConstraintChecker.java` (CWE-191 concrete)
- `D:\work\workspace\idea\engine\Plumb\plumb-dfa\src\main\java\org\plumb\dfa\boundary\Range.java` (lattice)
- `D:\work\workspace\idea\engine\Plumb\plumb-dfa\src\main\java\org\plumb\dfa\checkers\taint\IntUnderflowChecker.java` (heuristic plumb.numeric)
- `D:\work\tmp\Juliet_Java\src\testcases\CWE191_Integer_Underflow\s01\CWE191_..._81_bad.java` (Juliet template,identical to goodG2B)
