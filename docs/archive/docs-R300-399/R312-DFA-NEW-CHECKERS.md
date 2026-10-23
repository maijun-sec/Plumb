# R312 DFA New Checkers (2026-08-03)

## TL;DR

按用户要求"如果使用数据流分析更好，不要强制参考 pmd/sonar-java/spotbugs/checkstyle 去实现风格类规则"，加 2 个**纯 DFA** checker:

1. **R312a `IteratorConcurrentModificationChecker` (DFA)** — CWE-820 (ConcurrentModificationException)
2. **R312b `CollectionSelfReferenceChecker` (DFA)** — CWE-754 (Improper Check for Unusual Conditions)

**结果**:
- plumb-dfa: 21 → **23 checkers** (+2 R312)
- plumb-dfa tests: 396 → **410 (+14 tests)**
  - 6 R312a unit tests (hand-built IR)
  - 6 R312b unit tests (hand-built IR)
  - 2 R312 end-to-end tests (real Java source → parse → translate → TAC → DFA)
- **0 regression** 跨 9 modules
- 4 工具 coverage 不变 (PMD 98.1% / SonarQube 97.6% / SpotBugs 96.0% / Checkstyle 97.3%)

## R312a IteratorConcurrentModificationChecker (DFA, CWE-820)

**Bug 类型**: `for (T x : list) list.add(x);` / `while (it.hasNext()) list.remove(it.next());` — 在迭代过程中改集合触发 `ConcurrentModificationException`

**DFA > AST 的理由**:
- AST 看 for-each 语法简单, 但跨 scope 的迭代 (helper 里 iter, caller 里改) 跟 aliasing (两个 local 指向同一集合) AST 看不到
- DFA per-function 跟踪: 每个 source var + 一个 open iterator 集合, 后续 mutating call (add/remove/clear/addAll/removeAll/retainAll/put/putIfAbsent/removeIf/replaceAll) 命中就 fire
- Dedup per (recv, arg) 避免重复报

**实现要点**:
- per-function: `Map<String, TacInstruction> openIterators` + `Set<String> alreadyFired`
- `isIteratorCall(sig)`: 接受 `iterator/0` (for-each), `{var}.iterator/0` (显式), `{class}#iterator/0` (老格式) — 抽 `methodName()` helper
- `isMutatingCall(sig)`: 同上, 检查 method name 段

**测试 (6 unit + 1 e2e)**:
- `listAddWhileIterating_firesCwe820` — `list.iterator(); list.add(x);`
- `listRemoveWhileIterating_firesCwe820` — `iter(); list.remove(x);`
- `listClearWhileIterating_firesCwe820` — `iter(); list.clear();`
- `readOnlyGetWhileIterating_doesNotFire` — `iter(); list.get(0);` (read-only)
- `mutationOnDifferentCollection_doesNotFire` — `iter(list); list2.add(x);` (不同集合)
- `multipleMutations_fireOnce_dedup` — `iter(); add(x); add(y);` → 1 finding (dedup)
- `R312JavaEndToEndTest.cme_firesOnRealSource` — 真 .java 源, 2 CME patterns → 2 findings

## R312b CollectionSelfReferenceChecker (DFA, CWE-754)

**Bug 类型**: `list.addAll(list)` / `list.removeAll(list)` / `list.retainAll(list)` / `list.contains(list)` — 自引用 (useless / catastrophic)

**DFA > AST 的理由**:
- AST 直接 pattern match `x.op(x)` 太容易误报 (x 可能不是同一变量)
- DFA 跟踪 per-function alias classes: `a = list; b = list; a.addAll(b)` — a 跟 b 同一 alias class, fire
- 通过 union-find-like 合并, 链式 alias (a→b→c) 正确传递

**实现要点**:
- per-function: `Map<String, Set<String>> aliasClasses` (canonical name → set of names in class)
- MOVE handler: 合并 src 跟 dest 的 alias class, 写回所有成员
- CALL handler: 命中 self-ref pattern (addAll/removeAll/retainAll/containsAll/contains), 检查 recv 跟 arg 的 class 是否相交
- Dedup per `(recv, arg)` 避免重复

**测试 (6 unit + 1 e2e)**:
- `directSelfAddAll_firesCwe754` — `list.addAll(list)`
- `aliasedSelfAddAll_firesCwe754` — `a = list; b = list; a.addAll(b)` (DFA value)
- `removeAllOnSelf_firesCwe754` — `list.removeAll(list)`
- `retainAllOnSelf_firesCwe754` — `list.retainAll(list)`
- `containsOnSelf_firesCwe754` — `list.contains(list)`
- `addAllWithDifferentCollections_doesNotFire` — `a.addAll(b)` 不同集合
- `R312JavaEndToEndTest.selfRef_firesOnRealSource` — 真 .java 源, 3 self-ref → 3 findings

## 跟 R310c-Dfa (UncheckedReturnChecker) 一致的设计

- 用 `DfaChecker` interface (不是 `TaintedCheckerBase`) — "may-discard" / "self-ref" 不是 source→sink taint propagation
- per-function state 清在 `initFunction`
- 不写 summary (cross-function 在后续 round)
- `methodName(sig)` 抽出来共享 calleeSig 解析

## 关键 pitfall (R312)

1. **CalleeSig 格式多样** — Translator 用 `{var}.{method}/{arity}` (for-each 是 `iterator/0` 裸), 老 checker 用 `{class}#{method}/{arity}`. 修: `methodName()` 接受两种分隔符 (`.` 或 `#`)
2. **MOVE handler dest 类名漏** — `a = list` 时 destClass 初始化为 `{}` (empty), union 跟 srcClass 后 dest.name() 没在 merged set. 修: destClass 初始 = `{dest.name()}`
3. **For-each 不用 `methodSig()` 生成 calleeSig** — `StatementTranslator.translateForEach:278` 硬编码 `"iterator/0"`, 没有 receiver prefix. IteratorConcurrentModificationChecker 接受这种 format
4. **`x.equals(y)` 被 TacTranslator 翻译成 `BINOP EQ`** — `ExpressionTranslator` 看到 EQUALS 转 BinaryOperator.EQ (line 558), 所以 `.equals()` 不是 CALL. DFA 上没法 hook, 这种 case 留给 AST (EqualsAvoidNullCheck / CovariantEqualsCheck)
5. **跟 plumb-style 已有 rule 重复检查** — 找过: `WmiWrongMapIteratorCheck` (map keySet 用错), `IteratorNextExceptionCheck` (Iterator.next 漏 hasNext), 没有 CME/self-ref 重复. 安全

## 数字 final

| 指标 | R311e | R312 | Δ |
|---|---:|---:|---:|
| plumb-dfa checkers | 21 | 23 | +2 |
| plumb-dfa tests | 396 | 410 | +14 |
| plumb-style tests | 883 | 883 | 0 |
| 9 modules total tests | 3441 | 3455 | +14 |
| Tool 引用残留 | 0 | 0 | 0 |
| 4-tool coverage | 不变 | 不变 | 0 |

## 备份

- `D:\work\tmp\r312_done\`:
  - 2 src: `IteratorConcurrentModificationChecker.java`, `CollectionSelfReferenceChecker.java`
  - 2 unit test: `*Test.java`
  - 1 e2e test: `R312JavaEndToEndTest.java`
  - 1 doc: `R312-DFA-NEW-CHECKERS.md`

## R313+ 接下来 (候选)

- R313: 把 R312a/b 接到 `ScanRunner.runAllCheckers` (现在只调老 plumb-dfa 的 checkers). 加 `runIteratorConcurrentModification` / `runCollectionSelfReference` config flags
- R314: 更多 DFA rule 候选 (HashMapCapacity / StringBuilderInLoop / RedundantNullCheck)
- R315: do-while IR 改进 (用户原始重点 #1, C/C++ syscall FP reduction, 需要 DFA 状态机调整)
- R316: Final R295-R312 retrospective R-doc
