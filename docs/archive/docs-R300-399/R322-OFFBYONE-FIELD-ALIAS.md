# R322 OffByOneChecker Field-Alias Fix (2026-08-08)

## TL;DR

修复 `OffByOneChecker` (CWE-193) 在非静态方法中的 `this.i` FIELDLOAD 模式下
失火的问题。根因是 checker 用 SSA 临时变量名（`t_t0`/`t_t3`）做 key，但
非静态方法的循环变量在 TAC 里有独立的 dest 名称（每次访问 `this.i` 产生
一个全新 temp）。修复后用 `varToField` 跟踪 dest → fieldName 映射，body
侧的 ARRAYLOAD 通过 field name 命中记录在 `fieldLengths` 的长度表达式。

**结果**:
- 1 个新单元测试 `nonStatic_firesWhenLoopVarReLoadedAsField` (R322)
- 修复 R323ScanRunnerUnifiedE2ETest 真实 CWE-193 漏报
- 修复 ScanRunnerTest MethodTooLongCheck 缺类（受 R323 失败影响）
- **0 regression**，plumb-dfa 498→499，plumb-main 19 tests 0 fail

## 根因

非静态方法的循环变量 `int i` 在 TAC 翻译时降级为 FIELDLOAD：

```tac
   t_t0 = this.i              ; FIELDLOAD, dest=t_t0, fieldName="i"
   t_t1 = arr.length          ; FIELDLOAD, dest=t_t1, fieldName="length"
   t_t2 = t_t0 LE t_t1        ; BINOP LE
   if t_t2 goto L_FOR_BODY_1 else goto L_FOR_END_3
L_FOR_BODY_1:
   t_t3 = this.i              ; 重新 load this.i，新 SSA dest=t_t3
   t_t4 = arr[t_t3]           ; ARRAYLOAD, lhs2=t_t3
```

`OffByOneChecker` 老逻辑：

```java
// BINOP 处理
lengthOfVar.put(loopVar.name(), ins.lhs2.toString());  // lengthOfVar["t_t0"] = "arr.length"

// ARRAYLOAD 处理
String lenExpr = lengthOfVar.get(index.name());  // lengthOfVar["t_t3"] → null!
```

`t_t0` 和 `t_t3` 是两个不同的 SSA 名字，body 侧的 `arr[t_t3]` 查不到，
checker 失火。

静态方法 `static int sum(...)` 没有这个问题，因为 `int i` 在 TAC 里
直接用 `i` 命名，每次访问复用同一名字（`arr[i]` 的 `i` 和 BINOP 的 `i` 是
同一个 TacVar）。

## 修复

加 `varToField: dest TacVar name → field name` 映射：

```java
case FIELDLOAD -> {
    if (ins.fieldName != null
            && (ins.fieldName.equals("length")
                || ins.fieldName.equals("size"))
            && ins.dest instanceof TacOperand.TacVar dest) {
        lengthOfVar.put(dest.name(), ins.lhs1.toString() + "." + ins.fieldName);
    }
    // R322: 记录每个 dest 来自哪个 field（不仅 length/size）
    if (ins.fieldName != null
            && ins.dest instanceof TacOperand.TacVar dest2) {
        varToField.put(dest2.name(), ins.fieldName);
    }
}
```

BINOP 时把循环变量的 field 名一起注册到 `fieldLengths`：

```java
if (ins.lhs1 instanceof TacOperand.TacVar loopVar
        && ins.lhs2 != null
        && looksLikeLength(ins.lhs2)) {
    lengthOfVar.put(loopVar.name(), ins.lhs2.toString());
    // R322: 如果 loopVar 是 FIELDLOAD 的 dest，额外按 field name 注册
    String fieldName = varToField.get(loopVar.name());
    if (fieldName != null) {
        fieldLengths.putIfAbsent(fieldName, ins.lhs2.toString());
    }
}
```

ARRAYLOAD/ARRAYSTORE 查长度时先查 SSA 名，miss 再查 varToField + fieldLengths：

```java
private String lookupLenExpr(TacOperand.TacVar index) {
    String lenExpr = lengthOfVar.get(index.name());
    if (lenExpr != null) return lenExpr;
    String fieldName = varToField.get(index.name());
    if (fieldName != null) {
        return fieldLengths.get(fieldName);
    }
    return null;
}
```

## 测试

新单元测试 `nonStatic_firesWhenLoopVarReLoadedAsField`：
- 静态版本（`static int sum`）已经通过
- 非静态版本（`int sumBad`）原来在 R323 E2E 失败，新单测覆盖

TAC 触发场景（test 输出）：

```
4: t_t0 = this:T.i            ; varToField["t_t0"] = "i"
4: t_t1 = arr:int[].length    ; lengthOfVar["t_t1"] = "arr.length"
4: t_t2 = t_t0 LE t_t1        ; lengthOfVar["t_t0"] = "arr.length"
                              ; fieldLengths["i"] = "arr.length"
5: t_t3 = this:T.i            ; varToField["t_t3"] = "i"
5: t_t4 = arr:int[][t_t3]     ; lookup(t_t3) → varToField["i"] → fieldLengths["i"] → "arr.length"
                              ; EMIT CWE-193!
```

## 影响

- **+1 finding** on R323 E2E 测试（6 → 7 expected findings all fire）
- 修复 `ScanRunnerTest` 的 MethodTooLongCheck NoClassDefFoundError（之前
  因为 R323 失败 cascade 触发了 class loading 异常）
- 0 regression across all 9 modules
- plumb-dfa: 498 → 499 tests
- plumb-main: 19 tests (was 19 with 1 fail + 2 errors)

## 关键经验 (跨项目通用)

1. **SSA 名字在 DFA checker 里不够**：当同一个语义变量被翻译成多个
   SSA temp（典型场景：非静态方法、accessor chain、函数参数转发），
   必须有 alias tracking。`varToField` 是一个轻量级 alias key。
2. **FIELDLOAD dest 不等于 field 名字**：TAC 把 `this.field` 翻译成
   `t_t0 = this.field`，dest 名字是 temp，不是 field 名。alias
   跟踪要 explicit，不能假设。
3. **跨测试隔离不要靠清空 map**：每个 `initFunction` 重置 `varToField`/
   `fieldLengths`/`lengthOfVar` 三个 map，checker 实例可以被复用而
   不串数据。
4. **测试套件间的 cross-pollution 容易掩盖 fix 效果**：R323 E2E 测试
   只在跑全 9 模块时才暴露 CWE-193 漏报（因为 plumb-dfa
   内的翻译器跑过 Juliet `_45` 模式后行为变化）。一个单测通过 ≠
   真实 E2E 通过。
