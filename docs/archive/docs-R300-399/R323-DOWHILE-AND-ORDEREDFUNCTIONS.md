# R323 OffByOne Do-While + Merged-Program Dedupe Fix (2026-08-09) — DONE

## TL;DR

两个互相独立的 fix，组合起来让 CWE-193 OffByOne 在 Juliet Test Suite
的所有 51 个 case 上 100% recall:

1. **`DfaEngine.orderedFunctions` 去重 bug**: 旧版用 bare signature 去重，
   `MergedProgram` 里多个 file 有同名 `bad()` / `good()` 方法时只处理第一个。
   改成按 function 引用去重 → 17 个 for_*.java 现在都 fire
2. **`OffByOneChecker.finishFunction` 二遍扫**: do-while 模式下 BINOP 出现在
   body 之后，第一遍扫时 `lengthOfVar` 还没建立，第二遍重走所有
   ARRAYLOAD/STORE 补刀 → 17 个 do_*.java 全部 fire

**结果**:
- 新单元测试 `doWhile_firesOnClassicOffByOne` (R323) 覆盖 do-while 模式
- CWE-193 Juliet 51 files: 0 → 17 (R322 only) → 34 (R322 + dedup fix) → **51/51** (R323)
- 0 regression, plumb-dfa 498→499, plumb-main 19 tests 0 fail

## 根因 #1: orderedFunctions 用 bare signature 去重

`MergedProgram.asProgram()` 给 stub `TacProgram` 里每个 function 用
`enclosingClass :: signature` 作为 key（避免 Juliet "flow 81+" family
同名方法 last-writer-wins 冲突）。但 `DfaEngine.orderedFunctions` 里
`seen` set 是用 `fn.signature()` 判重的：

```java
for (var node : cg.reversePostOrder()) {
    TacFunction fn = program.function(node.signature());  // null! merged-key 找不到
    if (fn != null && seen.add(node.signature())) { ... }
}
for (TacFunction fn : program.functions()) {
    if (seen.add(fn.signature())) {  // ← 这里只去重 bare signature
        out.add(fn);
    }
}
```

CWE-193 51 个文件全在 `extends AbstractTestCase` 的子类里，每个都有
`bad()` / `good()` / `good1()` / `good2()` 同样的方法名。`seen` set
只接受第一个 → 其余 50 个函数的 checkers 都不跑。

## 根因 #2: do-while 模式下 BINOP 在 body 之后

`for (i = 0; i <= arr.length; i++) { body }`:
```
BINOP i LE len   ← loop 头
BRANCH
LABEL body
ARRAYLOAD/STORE  ← 看到 lengthOfVar[i], fire
```

`do { body } while (i <= arr.length)`:
```
LABEL body
ARRAYLOAD/STORE  ← lengthOfVar 还没建
BINOP i LE len   ← 这里才建
BRANCH
```

BB-RPO 单遍扫的话，do-while body 里的 ARRAYLOAD 在 BINOP 之前出现，
`lengthOfVar[i]` 还是 null，老 checker 不 fire。

## 修复

### Fix 1: DfaEngine.orderedFunctions

```java
// 旧: Set<String> seen
// 新: Set<TacFunction> seen  (按引用去重)
Set<TacFunction> seen = new HashSet<>();
for (var node : cg.reversePostOrder()) {
    TacFunction fn = program.function(node.signature());
    if (fn != null && seen.add(fn)) { out.add(fn); }
}
for (TacFunction fn : program.functions()) {
    if (seen.add(fn)) { out.add(fn); }
}
```

注意：`program.function(bare_signature)` 在 merged program 里仍然返回
null（因为 key 是合并的），所以 call-graph 那个分支实际不进；catch-all
分支按引用去重就够。

### Fix 2: OffByOneChecker.finishFunction

加 default-method override，函数退出时二遍扫所有 ARRAYLOAD/STORE:

```java
@Override
public void finishFunction(AnalysisContext ctx, TacFunction fn) {
    for (TacInstruction ins : fn.instructions()) {
        if (ins.kind == TacInstruction.Kind.ARRAYLOAD
                || ins.kind == TacInstruction.Kind.ARRAYSTORE) {
            if (ins.lhs2 instanceof TacOperand.TacVar index) {
                String lenExpr = lookupLenExpr(index);
                if (lenExpr != null) emit(ctx, ins, fn, index.name(), lenExpr);
            }
        }
    }
}
```

这样:
- 单遍扫：BINOP → lengthOfVar 写入 → BRANCH → ARRAYLOAD 命中 (for / while)
- finishFunction 兜底：do-while 模式 body 在 BINOP 之前，body 里的
  ARRAYLOAD/STORE 在 finishFunction 阶段才 fire

## 测试

| Test | Type | Status |
|---|---|---|
| `realJavaSource_firesOnClassicOffByOne` (R315) | for static | ✅ |
| `nonStatic_firesWhenLoopVarReLoadedAsField` (R322) | for non-static this.i | ✅ |
| `doWhile_firesOnClassicOffByOne` (R323) | do-while | ✅ NEW |

## CWE-193 Juliet 召回 (51 files)

| Round | for (17) | while (17) | do (17) | total |
|---|---:|---:|---:|---:|
| Pre-R322 | 0 | 0 | 0 | 0 |
| R322 only (single-file scan) | 1 | 0 | 0 | 1 |
| R322 + orderedFunctions fix | 17 | 17 | 0 | 34 |
| **R322 + orderedFunctions + do-while** | **17** | **17** | **17** | **51** ✅ |

跑法:
```bash
java -jar plumb-main-0.1.0-SNAPSHOT-all.jar scan \
    -i <dir> -f ndjson -o CWE193_Off_by_One_Error.r323.ndjson
```

## 关键经验 (跨项目通用)

1. **MergedProgram 别用 bare signature 去重 function**: 多 file 同名
   method 是 SAST 工具的高频场景，dedup key 要么用合并 key，要么直接
   按 TacFunction 引用
2. **BB-RPO 顺序 ≠ 控制流顺序**: for-loop BINOP 在 BRANCH 之前,
   do-while BINOP 在 BRANCH 之后 (BRANCH 在 BRANCH 之后, body 在
   BRANCH 之前)。checker 用 fixed-point (finishFunction 二遍扫)
   比改 analysis order 简单可靠
3. **合并 program 改变了 function map key shape**: `enclosingClass :: signature`
   而非 bare signature。`TacProgram.function(sig)` 的旧实现默认按
   bare 查 → merged 路径上必然查不到。修复时要么两套 key 都支持,
   要么 catch-all 兜底

## 数字

- plumb-dfa: 498 → **499 tests** (+R323 doWhile test)
- plumb-main: 19 tests 0 fail (cascade from R323 dedup fix)
- CWE-193 recall: 0% → **100%** (51/51 Juliet files)
- CWE-396 sanity: 36/36 files fire (100% recall, 99 findings)
- CWE-78 sanity: 384 findings (R246 baseline 384, no regression)
- 0 regression across all 9 modules
