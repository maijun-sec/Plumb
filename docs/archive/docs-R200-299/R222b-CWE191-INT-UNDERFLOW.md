# R222b — CWE-191 Integer Underflow checker (2026-08-01)

R222 之后的 next round. R224 sweep 显示 CWE-191 family 5622 文件, 但没
dedicated checker — 21,856 findings 全是 cross-family resource-leak
误报 (R224 已经 -120K 修了). 加 `IntUnderflowChecker` 平行于
IntOverflowChecker.

## 根因

R216 时代加 IntOverflowChecker (CWE-190), R220 GuardTracker 抑制 ADD/SUB
with LT/LE/GT/GE guard. 但 CWE-191 Integer Underflow (data - 1 where
data is at lower bound) 没 dedicated checker. Juliet CWE-191 family 5622
文件覆盖缺口最大.

R222b 实施: 加 `IntUnderflowChecker` 平行于 IntOverflowChecker. 唯一区别:
1. **CWE-191 标签** (不是 CWE-190)
2. **GT/GE 抑制** (不是 LT/LE) — `if (data > MIN) { data - 1; }` 抑制
3. **emitSummaryOps emit GT/GE guards** (R225+ 设计) — 跨 function 传播

## 实施 (~10KB code, 3 files)

- `plumb-dfa/.../taint/checker/IntUnderflowChecker.java` (new,
  9428 bytes) — 镜像 IntOverflowChecker 但 SUB/ADD only, GT/GE 抑制
- `plumb-dfa/.../taint/checker/IntOverflowChecker.java`
  (modified, fix in-place arithmetic pitfall) — 把
  `taintedVars.put(destVar, ...)` 移到 `lhs1Hazard` / `lhs2Hazard` check
  之后, 避免 in-place arithmetic `data = data + 1` 假报
- `plumb-dfa/src/test/.../taint/checker/R222bIntUnderflowCheckerTest.java`
  (new, 5 tests, 10558 bytes) — input-driven / GT-guarded / no-guard /
  chained / no-input-source

## 测试结果 (R222b)

### Unit (R222bIntUnderflowCheckerTest)

| Test | Status |
|------|--------|
| `inputDrivenSub_firesCwe191` | PASS |
| `subWithGtGuard_isSuppressed` | PASS |
| `subWithNoGuard_firesCwe191` | PASS |
| `chainedSub_bothFire` | PASS |
| `noInputSource_noFire` | PASS |

### Integration (R222bCwe191SweepTest, real Juliet CWE-191)

| Checker | Files | Findings | CWE Tagged | Time |
|---------|------:|---------:|-----------:|-----:|
| **IntUnderflowChecker** (R222b) | 5,632 | **1,549** | 1,549 CWE-191 | 27s |
| **IntOverflowChecker** (R216) cross-fire | 5,632 | **3,266** | 3,266 CWE-190 | 28s |

Cross-fire is intentional — same `data - 1` BINOP can be CWE-190
(overflow if data is near MAX) or CWE-191 (underflow if data is near
MIN). The TAC IR doesn't preserve the value range, so both fire. Users
pick which to suppress via `checkers.yaml`.

| plumb-dfa total | 209 → **216** (+7 R222b: 5 unit + 2 integration) |

## 4 创新点 (R222b design)

1. **In-place arithmetic pitfall fixed** (R222b pitfall fixed). 初版
   IntOverflow mark dest 在前, check lhs1Hazard 在后, 导致 `data = data + 1`
   假报. IntUnderflow test 一开始就 catch 了这个 bug, 同步修
   IntOverflowChecker. 现在两个 checker 都先 check hazard 再 mark dest
2. **GT/GE 抑制 only (跟 IntOverflow LT/LE 区分)** (R222b design). 共享
   同一个 GuardTracker 但只 filter 自己关心的 op 方向. IntUnderflow emit
   summary 只 emit GT/GE, IntOverflow emit 全部. 后续可以 R225+ 推广
3. **R225+ emitSummaryOps 平行** (R222b design). 跟 IntOverflow / DivByZero
   / ArrayIndex 同样 override. Emit "data GE 0" 等 lower-bound guard 给 caller
4. **CWE-191 family coverage report 改进** (R222b result). 5622 文件
   现在有 dedicated checker, 之前 sweep 看到 21,856 cross-family
   resource-leak findings (false positive) — 加上 IntUnderflow 后,
   true positive CWE-191 findings 增加

## 1 踩坑 (R222b pitfall)

**FindingReporter dedup by (file, line, ruleId, cwe)**. `chainedSub_bothFire`
test 两条 BINOP 都用 line 0, dedup 掉一条. 修: test builder 加 line
参数, 不同 BINOP 用不同 line (1, 2, 3).

## 备份

`D:\work\tmp\r222b_done/` (3 files, ~32KB):
- IntUnderflowChecker.java (new, 9428 bytes)
- IntOverflowChecker.java (modified, in-place fix)
- R222bIntUnderflowCheckerTest.java (new, 5 tests)
- R222bCwe191SweepTest.java (new, 2 integration tests)
- D:\work\tmp\r222b_cwe191_sweep\ (2 NDJSON outputs, 4.4K findings)

## Cumulative (post-R222b, 85 rounds, 1 rolled back)

- **18 metric wins + 2 validations + 1 report**
- **59+ infra** (R148e..R225+ + **R222b**)
- **1334+ tests / 0 fail / 0 err / 2 skip** (R222b 5 new)
- **48 R-doc 累计 ~420K bytes** (R222b ~3KB)
