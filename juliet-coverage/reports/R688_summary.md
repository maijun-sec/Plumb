=== Plumb R688 全局 Juliet 覆盖报告摘要 ===

## 数据来源
- R688_full: 112 个 CWE 用 R688 修复版 jar 全量重跑
- 排除 multi-file 测试（variant 22/51/52 等需要跨文件 taint 追踪，Plumb 单文件分析无法覆盖）

## 全局指标
- Total POSITIVE files (single-file): 15,082
- TP files: 11,730 (77.77%)
- FN files: 3,352 (22.23%)
- FP files (good hit): 3,199
- File-level Precision: 78.57%
- File-level Recall: 77.77%

## Recall Distribution (single-file)
- 100% (full): 31 CWEs
- 80-99%: 14 CWEs
- 50-79%: 16 CWEs
- 10-49%: 1 CWEs
- 1-9%: 2 CWEs
- 0% (silent): 39 CWEs (Plumb 无对应 rule)

## FN Top 20 (single-file, 单文件分析)
- CWE190 Integer Overflow: 632 FN
- CWE129 Array Index: 331 FN
- CWE191 Integer Underflow: 326 FN
- CWE197 Numeric Truncation: 192 FN
- CWE563 Unused Variable: 191 FN (style rule, 不计入)
- CWE400 Resource Exhaustion: 182 FN
- CWE789 Mem Alloc: 140 FN
- CWE398 Poor Code Quality: 137 FN (无 Plumb rule)
- CWE369 Div by Zero: 102 FN
- CWE606 Unchecked Loop: 95 FN
- CWE546 Suspicious Comment: 85 FN (无 Plumb rule)
- CWE506 Embedded Malicious Code: 67 FN
- CWE511 Logic Time Bomb: 51 FN (无 Plumb rule)
- CWE470 Reflection: 43 FN
- CWE78 Command Injection: 43 FN (variant 15, 21)
- CWE690 NULL Deref From Return: 42 FN
- CWE325 Missing Crypto: 34 FN (Plumb rule 存在)
- CWE338 Weak PRNG: 34 FN
- CWE382 sizeof on Pointer: 34 FN
- CWE617 Reachable Assertion: 34 FN

## FP Top Rules (semantic, single-file)
- UncheckedReturn: 14,598 FPs (R686 P0 fix 可降)
- bu-1104-string-equality-with-equals: 6,860 FPs
- plumb.numeric.integer-overflow: 4,508 FPs
- plumb.numeric.unchecked-array-index: 1,152 FPs
- plumb.numeric.integer-underflow: 1,106 FPs
- InsecureCommunication: 792 FPs
- plumb.dfa.insecure-cookie: 546 FPs
- cov-regex-confusion: 324 FPs
- plumb.pointer.null-dereference: 294 FPs
- plumb.taint.sql-injection: 240 FPs
- ...

## Root Cause 分析

### FN Root Cause
1. **常量 source**（30% of FN）: e.g. `data = Byte.MAX_VALUE; byte r = data+1;` — taint 设计假设 source 是 untrusted input
2. **byte/short 类型**（30%）: TaintedCheckerBase 没为 byte/short 路径传播 taint
3. **跨函数调用**（20%）: 跨方法的 taint 传递不完整
4. **Sink 表达式 mismatch**（10%）: Taint 流的 sink 表达式包含 parentheses / cast
5. **Plumb 没对应 rule**（10%）: e.g. CWE398/546/511 等

### FP Root Cause
1. **Guard 没识别**（40%）: e.g. `if (data < MAX) ...` 后再算术，guard 在 taint 引擎看不到
2. **close() 返回值**（25%）: UncheckedReturn 在 .close() 调用上 FP，但实际可能是真 bug
3. **string == interned constant**（15%）: bu-1104 FP 但 Juliet 测试 fixture 经常==常量字符串
4. **Taint 跨调用假阳**（10%）: taint 流过 sanitized path 但 sanitizer 没识别
5. **其他**（10%）: 各种 noise
