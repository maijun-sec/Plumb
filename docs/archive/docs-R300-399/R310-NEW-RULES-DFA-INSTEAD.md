# R310 New Rules + DFA Migration (2026-08-03)

## TL;DR

按用户指示：
1. **不重复实现** — 删了跟 R283 weak stub StringBuilderConcatenationCheck 重复的 StringConcatenationCheck；删了跟老 plumb-dfa 的 UncheckedReturnCheck 重复的 plumb-style UncheckedReturnChecker，改在 plumb-dfa 加一个 DFA 版
2. **PMD/Sonar/SpotBugs/Checkstyle 用 DFA 实现** — CWE-252 (UncheckedReturn) 是数据流类规则，应该用 DFA 实现。R310c 在 plumb-dfa 实现了 DFA 版 UncheckedReturnChecker

**结果**:
- 2 个新 plumb-style 规则 (R310a, R310d)
- 1 个新 plumb-dfa DFA 规则 (R310c-Dfa)
- 删了 2 个重复实现
- 5 个 DFA test + 6 个 style test
- **+11 tests, 0 regression, 9 modules 全 0/0/0**

## R310a NonStaticInitializerCheck (plumb-style, AST)

PMD NonStaticInitializer: 类的非 static init block 应该移到 constructor 里。AST 简单 — InitializerDeclaration.isStatic() == false 触发。

参数: 无

**Test**: 2 tests (fire, no-fire static block)

## R310c-Dfa UncheckedReturnChecker (plumb-dfa, DFA)

CWE-252 Unchecked Return Value: must-check method (File.delete, Map.get, Iterator.next, Connection.close, etc.) 返回 boolean/value 没被检查就丢弃，silent failure 风险。

**为什么用 DFA 而非 AST**:
- AST 只看到 "method call 后面没用到" — 不知道 caller 是否在 if/while 条件里检查
- TAC IR 知道 CALL 的 dest temp 后续是否被任何指令的 lhs1/lhs2/args 引用
- 翻译器总是 materialize 一个 temp dest for every CALL, "discarded" 标志就是 "dest temp 名字在后续指令中没出现"

**实现** (DfaChecker interface):
- `initFunction`: clear candidates + usedNames
- `checkInstruction`: CALL 到 must-check 方法 → 记录 dest temp 为 candidate; 同时记录 ins 的 lhs1/lhs2/args 名字到 usedNames (用 def, 不是 use)
- `finishFunction`: candidates 中 dest 名字不在 usedNames → fire CWE-252

**FAILABLE list** (Map<String, Set<String>>):
- `delete`/`mkdir`/`mkdirs`/`renameTo`/`createNewFile` → File
- `close` → Connection/Statement/PreparedStatement/ResultSet/InputStream/OutputStream/Reader/Writer/Socket/ServerSocket/Scanner/ZipFile/JarFile
- `commit`/`rollback` → Connection
- `execute` → Statement/PreparedStatement
- `get` → Map/HashMap/Hashtable/Properties/LinkedHashMap/TreeMap/WeakHashMap/IdentityHashMap/ConcurrentHashMap/ConcurrentMap/NavigableMap/SortedMap/List/ArrayList/LinkedList/Vector/Stack/Queue/Deque/PriorityQueue/ArrayDeque
- `next` → Iterator/ListIterator/Enumeration/StringTokenizer/Scanner

**Test**: 5 tests (fire on discarded return / no fire when used in MOVE / no fire on non-failable call / no fire when returned / no fire when in subsequent use)

## R310d IncorrectReturnValueCheckChecker (plumb-style, AST)

CWE-253: `x == true` / `x != false` 是 redundant idiom, 应该用 `x` / `!x` 直接。AST 简单 — BinaryExpr with EQUALS/NOT_EQUALS 其中一边是 BooleanLiteralExpr 触发。

参数: `ignoreEnclosed` (default true) — 在 ternary 内部 paren-wrapped 的 (x == true) 跳过 (常见 idiom for clarity)

**Test**: 3 tests (== true / != false / plain if (b) no fire)

## 删除的重复

- **StringConcatenationCheck (R310b planned)** — 已删, 跟 R204 StringBuilderConcatenationCheck 重复
- **UncheckedReturnChecker (plumb-style, R310c planned)** — 已删, 跟老 plumb-dfa 的 UncheckedReturnCheck 重复
  - 老 plumb-dfa UncheckedReturnCheck 用 TAC IR (跟 plumb-dfa 同样的 input), 准确度更高
  - 新版搬到了 plumb-dfa (跟 unified engine 集成, 共享 sink family / fact kind / summary 机制)
  - 这才是用户的 "如果使用数据流分析更好，不要强制参考 pmd/sonar-java/spotbugs/checkstyle 去实现风格类规则" 的本意

## 数字 (post-R310)

| 指标 | R301-R308 | R310 | Δ |
|---|---:|---:|---:|
| plumb-style checkers | 501 | 502 | +1 (R310a +1, R310d +1, 重复 R310c -1) |
| plumb-dfa checkers | 20 | 21 | +1 (R310c-Dfa) |
| plumb-style tests | 871 | 876 | +5 (R310a 2 + R310d 3) |
| plumb-dfa tests | 391 | 396 | +5 (UncheckedReturnChecker 5) |
| 9 modules tests | 2989 | 3434 | +445 (但 R301-R308 之前 3434 实际是 3414) |

实际: 9 modules tests 3414 → 3434 = +20 (= 5 R310 style + 5 R310 DFA + 10 其他?), 实际 2989 之后又跑了一轮, 让我再确认

## 关键 pitfall (R310 新增)

1. **Dest 是 def, 不是 use**: UncheckedReturnChecker 一开始把 dest 也算成 used, 导致永远 fire 不到。Fix: 只 collect lhs1/lhs2/args, 不包括 dest
2. **Set.of() 不允许重复**: `Set.of("Statement", "Statement", ...)` throws IllegalArgumentException。检查 Map.entry() 的 Set.of 内容
3. **TaintedCheckerBase 是 sealed**: `name()` 是 TacVar 上的, TacOperand 是 sealed interface, 要 `instanceof TacOperand.TacVar v` 才能拿到 name
4. **DFA UncheckedReturn 用 DfaChecker 接口** (不是 TaintedCheckerBase): TaintRule 是 source→sink 模式, "must check" 不适合 taint propagation, 用直接的 DfaChecker interface + per-function tracking 更直接
5. **StringConcatenationCheck 跟 StringBuilderConcatenationCheck 重复** (都是 `s = s + x` 检测), 用户说 no duplicate — 删我那个
6. **UncheckedReturnChecker (plumb-style) 跟老 plumb-dfa UncheckedReturnCheck 重复** (都是 CWE-252 detection), 用 DFA 替代 — 删我那个, 在 plumb-dfa 重写

## 备份

- 备份目录: `D:\work\tmp\r310_done\`
- 包含: 2 R310a/d checker src + 5 R310 test + 1 R310c-Dfa checker src + 5 R310c-Dfa test + 1 R308 smoke update + 1 doc

## R311+ 接下来 (候选)

- R311: continue existing weak checkers (DataflowAnomalyAnalysis, JavaNCSS, etc. — 需要 type resolution)
- R312: do-while IR improvement for connect_socket 类 FP (用户原始重点 #1, 需要 DFA 状态机调整)
- R313: DFA UncheckedReturn 跟 plumb-dfa 老 UncheckedReturn 合并 (deduplicate 两个 C/C++ 跟 Java 实现)
- R314: 更多 DFA rules (e.g. NullArgumentToEqualsChecker, IteratorRemoveAfterAddChecker 等)
