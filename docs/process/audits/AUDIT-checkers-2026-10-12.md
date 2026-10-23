# plumb-dfa `checkers/` 规则审计报告

- 生成日期：2026-10-12
- 审计范围：`plumb-dfa/src/main/java/org/plumb/dfa/checkers/` 全部 `.java`（含子包 `taint/`）
- 审计目的：对每条 DFA 规则给出**实现机制**和**为什么用 DFA 实现**两条结论，并标注不属于"真 DFA"的情况（占位 stub、源文本正则、AI 启发式）
- 数据基线：plumb-dfa 顶层 65 个 `.java`、子包 `taint/` 44 个 `.java`、**共 109 个文件**。其中：
  - 真实 DFA / Taint checker：**91 个**
  - R787 阶段被 plumb-style 替换的弃用 stub：**15 个**（构造时抛 `UnsupportedOperationException`）
  - 顶层废弃占位：`UncheckedLoopConditionChecker`（顶层版 R784 已废，仅保留 FQCN 兼容）
  - 非 checker 辅助类：**3 个**（`EntropyDetector`、`LiteralSourcedTracker`、`SourceReader`）

---

## 0. 框架速览

理解每条规则的实现机制，必须先看 DFA 引擎约定的两条核心 SPI：

### 0.1 `DfaChecker`（`plumb-dfa/src/main/java/org/plumb/dfa/engine/DfaChecker.java`）

```
initEngine(ctx)              // 引擎启动一次
for each fn in reverse post-order:
    initFunction(ctx, fn)
    for each ins in fn:
        checkInstruction(ins, fn, ctx)   // 主热路径
    finishFunction(ctx, fn)
    summary = summarizeFunction(ctx, fn)  // 给调用方看的副作用清单
    ctx.summaryCache().put(fn.signature, summary)
finish(ctx)                  // 引擎关停一次
```

直接 findings（bug 完全在函数体里）通过 `ctx.reporter().report(...)` 立刻发，**不进 summary**；只有"调用方需要知道的事"才进 `FunctionSummary`。这是 R-7 章节约束的。

### 0.2 `TaintedCheckerBase`（`plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintedCheckerBase.java`）

所有 taint 类规则的统一底座，三类 hook 全部从 `TaintRule(ruleId, sourceKind, sinkFamilyKey, cwe, severity, TaintDomain)` 派生，子类只剩四件事可覆写：

- `isSourceFact(Fact)` — 决定一个 fact 算不算"我这条规则的 source"
- `sinkArgumentIndex(SinkFamily, TacInstruction)` — 覆盖"arg 0 是 tainted"默认
- `findingMessage(...)` — 自定义 finding 文案
- `checkInstruction` 调完 `super` 之后做点扩展（典型：再 `markSource` 加一类非默认 source）

整体传播规则（MOVE / BINOP / CALL 内部跨函数）由基类负责，**子类不重新实现 taint 引擎**。这是为什么"加一条 sink 规则"在 `taint/` 下往往是几行代码。

### 0.3 `PathSensitiveCheckerBase`（`plumb-dfa/src/main/java/org/plumb/dfa/taint/PathSensitiveCheckerBase.java`）

alloc-without-close 类的反向检测底座：跟踪 `allocSites` 与 `closeSites`、在 `summarizeFunction` 让子类决定"未关闭的 alloc 怎么处理"。是 `ResourceLeakChecker` 的父类。

### 0.4 报告里"DFA 性质"的归类标准

我把每条规则按**实际实现机制**而不是按文件位置标成以下五类，**结论以"实际实现"为准**——一条"DFA 文件"里既可能写 AST-style 的 source-reading，也可能是真 DFA：

| 标签 | 含义 | 典型形态 |
|------|------|----------|
| **DFA-真** | 用 TAC IR + 跨语句 def-use / 跨函数 summary 跟踪数据流 | MOVE/BINOP/FIELDLOAD 跟踪 + 关键事件触发 |
| **DFA-注解通道** | 数据流之外，靠 `TacFunction.classAnnotations() / methodAnnotations() / paramAnnotations()` 通道做结构判定 | Spring `@GetMapping` / `@RequestParam` / `@NonNull` 等 |
| **DFA-taint** | 继承 `TaintedCheckerBase` / `PathSensitiveCheckerBase`，自动 taint 传播 | `taint/*` 包下绝大多数 |
| **SRC-正则** | 走 `SourceReader` 读源文件、纯正则匹配，TAC IR 完全不用 | 几条 R759 留下来的 legacy |
| **AI-启发** | 静态部分故意宽松，AI dedup 做主过滤 | CleartextSocketUsage / ErrorMessageInfoLeak / SuspiciousComment |
| **STUB** | R787 阶段已迁移到 plumb-style，构造时抛 UOE | 15 个 `*Checker` 占位类 |
| **HELPER** | 不是 DfaChecker，是公用工具类 | EntropyDetector / LiteralSourcedTracker / SourceReader |

> 审计员应当这样读这一节：每条规则告诉你**它到底依赖 TAC IR 哪些通道**（标签 + 文字），再告诉你**为什么这种通道比 AST 更合适**（DFA 论据段）。SRC-正则和 AI-启发两类我**显式标出**——这是审计里最容易被混淆为"DFA"的两类。

---

## 1. 顶层 `checkers/` 真实规则（47 个，按 CWE 归类）

### 1.1 CWE-19 / CWE-20 / CWE-362 / CWE-400 / CWE-862 / CWE-863 — 输入验证 / 授权 / 资源耗尽

#### 1.1.1 `MissingInputValidationChecker`（CWE-20）

- **实现机制**：DFA-注解通道。`finishFunction` 读 `fn.paramAnnotations()`，命中 `@RequestParam` / `@PathVariable` / `@RequestBody` / `@CookieValue` / `@RequestHeader` / `@RequestPart` / `@ModelAttribute` / Jakarta RS 同类注解视为 user-input；再读 `fn.classAnnotations()` / `methodAnnotations()` 看 `@Validated` / `@Valid`；都缺时把方法名加到 `AggregationState` 的 per-class map。`finish` 阶段按 class 聚合，每 class 一条 finding（不限方法数）。
- **DFA 论据**：Bean Validation 的判定**结构上依赖方法的全部三类注解**（class / method / param），TAC IR 同时携带这三类元数据；AST 也能拿，但需要走 JavaParser 完整 visitor 链重建。DFA 通过 `initFunction → finishFunction` 一次性把三个通道都拿到，省去"先访 class 注解再回到 method 边"的两次遍历。
- **误报画像**：未知自定义 validation 注解视为"未验证"（保守 fall-open）—— 这是文档里写明的代价。

#### 1.1.2 `ResourceExhaustionChecker`（CWE-400，R-718 后改 TaintedCheckerBase）

- **实现机制**：DFA-taint（继承 `TaintedCheckerBase`，`TaintDomain.NumericTaint`）。基类跑完后**自己额外**做两件事：
  1. 高值 CONST 种子：`MOVE dest = Integer.MAX_VALUE/2` 之类用 `markSource("max_const:...")` 标"高危常量"，供 for-loop bound 用；
  2. 两条直发 sink：`Thread.sleep(tainted)` 立刻 emit；for-loop 走到 `BRANCH` 时**回退一指令**找定义该 cond var 的 BINOP，如果 BINOP 用 LT/LE/GT/GE 拿 tainted operand，就 fire。
- **DFA 论据**：跨函数的 `count = badSource(); for (...count...)` 模式（V-41/V-42/V-45）需要 `crossFnClassFieldTaint` + FunctionSummary 联合作业；DFA 的 `TaintedCheckerBase` 已经把这层都搭好。直发 sink 的两条都没法靠 AST 正则：Thread.sleep 的参数可能是 `Math.min(...)` 之类，AST 看不见"算完的结果是 tainted"。
- **误报画像**：高值常量启发会标 `Integer.MAX_VALUE/2` 一类，但**短整型上下文**不区分（不会针对 `short count = Short.MAX_VALUE; for (...count...)` 漏报也不额外报）。

#### 1.1.3 `MissingAuthorizationChecker`（CWE-862）

- **实现机制**：DFA-注解通道。`finishFunction` 判定：class 注解含 `RestController` / `Controller` **或** method 注解含 `GetMapping` / `PostMapping` / `RequestMapping` 等视为 endpoint；method 注解含 `@PreAuthorize` / `@Secured` / `@RolesAllowed` / `@PostAuthorize` / `@DenyAll` / `@RequiresPermissions` / `@RequiresRoles` / `@Authorize` / `@AuthorizeResource` 视为已鉴权；`@PermitAll` / `@AnonymousAllowed` 视为放行；class 注解含 `@EnableMethodSecurity` / `@EnableGlobalMethodSecurity` / `@EnableWebSecurity` / `@EnableResourceServer` 视为类级兜底。
- **DFA 论据**：判定条件是"几个并存的注解集合"——TAC IR 把它们一起塞在 `TacFunction` 的 `classAnnotations` / `methodAnnotations` 上，DFA 一遍 `finishFunction` 即可。AST 走两遍（先 class 注解再 method 注解）也能做，但会和 class-level 跨方法传播纠缠；这里没有跨方法传播反而 AST 更简单——**这是一条 DFA/Annotation 实际不如 AST 简洁的规则**，但因为 engine 已经有"先 class 后 method"的两条通道就被塞到了 DFA 模块。
- **误报画像**：Filter / SecurityConfig 兜底、类级 `@PreAuthorize`（annotation 名字拼写差异）、internal-only 接口都可能漏报。

### 1.2 CWE-78 / CWE-22 / CWE-89 / CWE-94 等 taint 类 sink（在 `checkers/taint/` 集中处理，见 §2）

### 1.3 CWE-113 / CWE-319 / CWE-532 / CWE-200 / CWE-549 / CWE-598 / CWE-614 / CWE-615 / CWE-200 — 信息泄露

#### 1.3.1 `CleartextSocketUsageChecker`（CWE-319）— **AI-启发 / SRC-正则**

- **实现机制**：AI-启发 + SRC-正则。`finish` 阶段**从 `prog.sourcePath()` 读源文件**到 String，正则匹配 `new Socket(...)` / `new DatagramSocket(...)`，命中后再过一遍 +/- 2000 字符的窗口查敏感名字（password / token / ssn / cvv / pin ...），命中才 emit。所有 emit 的 finding 都带长 AI prompt（"FP if traffic is non-sensitive"），AI 才是主过滤。
- **DFA 论据**（有限）：正则不依赖 TAC IR，**严格说不算 DFA**。之所以放 dfa 模块是历史（早期 plumb-dfa 兼容 source-reading 规则）。"为什么不是 DFA"——CWE-319 关注"信道本身"而非"数据是否被污染"；只要 socket 存在且函数体里有敏感名字就 emit。AI 过滤之前，单纯走 TAC 也只能区分"调用了 Socket 构造"和"调用了 `SSLSocketFactory.createSocket`"——但这等于把规则 50% 退回 AST，而且仍然不能区分"socket 上跑的是 health-check ping 还是 PII 上传"。
- **误报画像**：开发期 localhost socket、健康检查 ping、内部 chat 协议普遍 FP——完全靠 AI 收尾。

#### 1.3.2 `InsecureCommunicationChecker`（CWE-319）

- **实现机制**：DFA-真（窄）。`checkInstruction` 看 `NEW` 的 `ctorType` 是 `URL` / `URI`，**且** `args[0]` 是字符串字面量常量；解析字面量 scheme，与 `INSECURE_PROTOCOLS = {http, ftp, telnet, ldap, jdbc}` 比对，命中即 emit。不查运行时拼出的 URL——`taint/CleartextTxChecker` 才是 taint 视角的对应物。
- **DFA 论据**：判定就是"new URL(...) 第一个参数是字面量且 protocol 在黑名单"，AST 也能做；但**写在 DFA 模块**的好处是：与"new URL(...)"在调用图里能直接被 `cleartext-tx.sinks` 的 taint checker 共享识别，DFA 的 CALL shape 配对 TaintRule 触发更直接。
- **误报画像**：localhost dev URL 也是 fire。文档里说"保守"。

#### 1.3.3 `InsecureCookieChecker`（CWE-614）

- **实现机制**：DFA-真（类型化别名）。按 TYPE 跟踪：`NEW Cookie` 把 `Cookie` 这个 TYPE 标 `needs-secure=true`；同函数里 `cookie.setSecure(true)` 把 TYPE 标 `false`；sink 是 `response.addCookie(cookie)`，args[1] 是 Cookie 且 TYPE 状态是 `true` 时 emit。
- **DFA 论据**：需要"new + setSecure + addCookie"三事件之间的关联，AST 可以做但要分三遍 visitor；DFA 一遍线性扫描按 TYPE 状态机就出来。**这是个典型的"多事件约束"checker**——AST 不行，DFA 行。
- **误报画像**：TYPE 粒度粗——一个函数里有两个 Cookie 时只跟踪 TYPE 状态；文档里"works for the common case where one Cookie is created and used"已经承认。

#### 1.3.4 `InformationExposureChecker`（CWE-200）

- **实现机制**：DFA-注解通道。`finishFunction` 读 `fn.returnType()`；class 注解含 `RestController` / `Controller` 或 method 注解含 `*Mapping` / `*Mapping` / `Path` 视为 endpoint；return type 是 `void` / 原始类型 / `String` / `Object` / `JDK_SAFE_TYPES` 视为安全（wrapper 类型还要递归看 type arg 是不是 user class）。命中则按 class 聚合，每个 controller 一条 finding 列出所有返回 user class 的 endpoint。
- **DFA 论据**：判定需要"class 注解 + method 注解 + 返回类型 + 泛型实参"四类信息一起看，DFA 的 `TacFunction` 一次性提供；AST 走三遍 visitor 拼装也行，但 IR 通道更直接。
- **误报画像**：DTO 是非 public 字段 + `@JsonIgnore` 普遍 FP。

#### 1.3.5 `InsufficientSessionExpirationChecker`（CWE-613）— **STUB（R787f）**

- 旧实现是 dfa 包下 `ClassThreadChecker.InsufficientSessionExpirationChecker` 正则 source-reading，已迁移到 `org.plumb.style.style.InsufficientSessionExpirationCheck`（AstChecker）。当前 dfa 顶层类构造时抛 `UnsupportedOperationException`。**ScanRunner 不再注册它**。不进入真 DFA 评估。

#### 1.3.6 `MissingPasswordMaskingChecker`（CWE-549）— **STUB（R787i）**

- 旧实现是 R759/R727 家族源文本正则 `<input type="text" name="password" ...>`。当前 stub 抛 UOE。**严格说不算 DFA**。

#### 1.3.7 `CommentInfoLeakChecker`（CWE-615）— **STUB（R787f）**

- 旧 dfa 实现读源文件 + 注释 regex。当前 stub 抛 UOE。

#### 1.3.8 `InfoExposureQueryStringChecker`（CWE-598）— **STUB（R787f）**

- 旧 dfa 实现读源文件 + string literal regex。当前 stub 抛 UOE。

#### 1.3.9 `UnprotectedCredTransportChecker`（CWE-523）— **STUB（R787g）**

- 旧 dfa 实现读源文件 + regex（http:// URL + login form 字段共现）。当前 stub 抛 UOE。

#### 1.3.10 `UnsaltedOneWayHashChecker`（CWE-759）— **STUB（R787g）**

- 旧 dfa 实现读源文件 + regex（`MessageDigest.getInstance` + 缺 `update`）。当前 stub 抛 UOE。

#### 1.3.11 `RandomIvChecker`（CWE-329）— **STUB（R787g）**

- 旧 dfa 实现读源文件 + regex（`IvParameterSpec` 构造配 `new byte[N]`）。当前 stub 抛 UOE。

#### 1.3.12 `ErrorMessageInfoLeakChecker`（CWE-209 / CWE-532）— **AI-启发**

- **实现机制**：DFA-真（call-site 模式 + 类型检查）。在 `checkInstruction` 做两件事：
  1. NEW RuntimeException / Exception / Error / Throwable 构造的 args 视为潜在 throw-ctor；
  2. CATCH_HANDLER 把 dest 加进 `throwableInScope`；后续 `.printStackTrace` / `.print` / `.println` / Logger.* 命中、receiver 是 `throwableInScope` 或者 receiver TacVar 的 type 解析为 `java.lang.Throwable` / `Exception` / `RuntimeException` / `Error` 任意一个，则 emit。AI prompt 长且区分 FP（Stack trace 不带用户数据 vs `throw new RuntimeException("DB error: " + password)` 真泄漏）。
- **DFA 论据**：判定条件是"receiver 类型在 Throwable 谱 + 方法名后缀在 print/println/Logger.* 子集"，AST 也能配 MethodCallExpr 拿到，但**要解析 receiver 表达式的类型**——AST 缺类型解析，得回 symbol-solver；DFA 走 `TacVar.type()` 一次解决。AI 主过滤、不纯靠静态。
- **误报画像**：`System.out.println(userName)` 的 `.print` 后缀命中而 receiver 不是 Throwable——假阳性靠 AI 排除。

### 1.4 CWE-242 / CWE-243 / CWE-248 / CWE-396 / CWE-397 — try/catch/finally 异常处理

#### 1.4.1 `TryCatchFinallyChecker`（CWE-248 / CWE-396 / CWE-397）

- **实现机制**：DFA-真（控制流事件收集）。`checkInstruction` 单遍累计：
  - `NEW X(...)` 的 ctorType 短名是 Exception / RuntimeException / Throwable → 加 `genericNews`；
  - `CATCH_HANDLER` 的 ctorType 短名是 Exception / RuntimeException / Throwable → 加 `genericCatches` 与 `caughtTypes`；
  - `THROW` 的 operand 是 generic-ctor 的 dest → 加 `throws_`。
  - `finishFunction` 一次性发 CWE-396（generic catch）、CWE-397（generic throw 在 NEW 上而非 THROW 上，"诊断指向源头"），CWE-248（throw 实际未被同函数内任何 catch 覆盖；除非有 `Exception` / `Throwable` 通吃 catch，否则不做"被任意一个 catch 兜底"的豁免）。
- **DFA 论据**：判定需要"NEW + CATCH_HANDLER + THROW"三类事件在**同函数内**的关联。AST 必须先 walk try-block 重建嵌套结构，再在每个 try 里配对 throw / catch——编译器都做这事但人写 visitor 容易写错；DFA 已经把 try-catch 降成线性 `CATCH_HANDLER` 标记，遍历一气呵成。
- **误报画像**：跨函数 catch 看不见（throws_ 不跨函数传播），需 `UncaughtExceptionPropagator` 补齐。

#### 1.4.2 `UncaughtExceptionPropagator`（CWE-248，跨函数）

- **实现机制**：DFA-真（跨函数）。**两遍扫**：
  1. 第一遍（`checkInstruction`）：每个函数累计 `hasThrow[fnKey]` 和 `hasCatch[fnKey]` 标志（按 `name/arity` 索引）。
  2. 第二遍（`finish`）：**重新 walk 每个函数的 CALL 指令**，对每条 CALL：callee 在 `hasThrow` 且 caller 不在 `hasCatch` → 报"跨函数未捕获"。

  关键点：把 `currentFunction` 在 `finish` 内重设到 `ctx.setCurrentFunction(fn)`，因为 `analyseOneFunction` 在每个 fn 的 `finish` 之后会把 `currentFunction` 置 null，**reporter 找不到 filePath 会写出错路径**。
- **DFA 论据**：完全跨函数——必须收集所有 fn 的 throw / catch 标志才能比对。AST 也行（看 `throws` 声明 + 看 try-catch 嵌套），但 Java 程序员**经常不写 `throws` 声明**给 unchecked exception，AST 看不到"实际 THROW 指令"而 DFA 看得到。文档里也明说"AST 经常因 unchecked 异常漏报"。
- **误报画像**：caller 有 `catch (NullPointerException)` 但 callee throw `IllegalStateException` 时仍 fire——cwe-2504 留为"未来按 ctorType 比对"。

#### 1.4.3 `UncaughtExceptionChecker`（CWE-600）— **STUB（R787g）**

- 旧实现 regex 找 risky call（`InetAddress.getByName` / `Class.forName` / `ProcessBuilder.start`）的 try-catch 缺失。已迁移 `org.plumb.style.style.UncaughtExceptionCheck`。当前 stub 抛 UOE。**注意**：这条与 §1.4.2 的 `UncaughtExceptionPropagator` 名字相近、但是两件事——前者是 servlet 上下文（已废），后者是跨函数异常传播（活的）。

### 1.5 CWE-190 / CWE-191 — 整数溢出 / 下溢

#### 1.5.1 `OffByOneChecker`（CWE-193）

- **实现机制**：DFA-真（**双通道别名**）。`checkInstruction` 单遍建两张表：
  1. SSA 名 → "length 表达式"（`lengthOfVar`）：FIELDLOAD `.length` / `.size` 触发；CALL `size()` 触发；BINOP `LE` / `GE` 的 lhs2 是 length 时把 lhs1 记为"以该 length 为界"。
  2. 字段名 → "length 表达式"（`fieldLengths`）：循环变量是 `this.i`（FIELDLOAD dest）时还额外按 `fieldName` 记一份。
  BODY 出现 `ARRAYLOAD[t] / ARRAYSTORE[t]` 时，**先用 SSA 名查，再 fallback 字段名查**（处理 non-static 循环变量 `this.i` 被重读到新 SSA 名 `t_t3` 的情况）。`finishFunction` 再扫一遍（处理 do-while：body 出现在 BRANCH 之前的情况）。
- **DFA 论据**：循环变量在 `i <= arr.length` 里是 `this.i`、在 body 里 `arr[this.i]` 又是新的 `t_t3 = this.i; arr[t_t3]`——**两个不同的 SSA 名**。AST 必须把循环变量和 FIELDLOAD 关联起来才能确认"body 用的是同一个 `i`"，而 DFA 显式把"load 这个 field"和"用这个 SSA 名查 length"两条路径都建表，**少一遍 visitor**。
- **误报画像**：`for (int i = 0; i <= n; i++) arr[i]` 的 `n` 是非 length（参数 / 计算值）时不 fire（heuristic 是 `.length` / `.size` / `length` / `size` 结尾名）—— 漏报"非 length 名 + 实际越界"的情况，但误报非常少。

#### 1.5.2 `BadShiftChecker`（CWE-758）

- **实现机制**：DFA-真（**常量折叠**）。`checkInstruction` 看 BINOP 是 SHL/SHR/USHR；`resolveConstCount(ins.lhs2, fn)` 一跳/MOVE/NEG 折叠出常量 count（看 dest 是 Const 直接读、是 TacVar 一跳找定义它的 MOVE / UNARYOP NEG）；`bitWidthOf` 查 operand 的 `TacType`（LONG → 64，其它 → 32）。count < 0 或 count >= bitWidth 即 emit。`getAiPrompt` 长，区分 TP / FP。
- **DFA 论据**：要做"跟一个 var 回到它的 MOVE / NEG 源头"的一跳常量折叠。AST 也能做（`<< -1` 是字面量）但跨语句的 `int n = -1; x << n;` 形态要 AST 跟踪变量定义点才能识别；DFA 一跳返回 last def。
- **误报画像**：运行时 count 不展开（`<< runtimeVar` 整条 miss）—— 文档里写"需要 range analysis 已超出范围"。

### 1.6 CWE-209 / CWE-200 / CWE-359 / CWE-532 — PII / sensitive data 流到 log 之类

`SensitiveDataLeakChecker` / `SensitivePIILeakChecker` 在 `taint/` 子包，详见 §2。

### 1.7 CWE-256 / CWE-259 / CWE-321 / CWE-798 — 硬编码凭证

#### 1.7.1 `HardCodedPasswordSinkChecker`（CWE-259）

- **实现机制**：DFA-真（字面量传播）。`LiteralSourcedTracker`（公用 helper）跟踪：MOVE-from-string-Const 把 dest 标 "literal-sourced"；CALL `toCharArray()` / `getBytes(...)` 接收 literal-sourced var 的，把 dest 标 "derived-of-literal"。**三路 sink**：
  1. `DriverManager.getConnection/3`，args[3]（TAC 偏移后）必须是 inline literal / literal-sourced var / derived-of-literal var；
  2. `NEW KerberosKey.<init>/3`，args[1] 同样条件；
  3. `NEW PasswordAuthentication.<init>/2`，args[1] 同样条件。

  emit 时带 `EntropyDetector.classify(literal)` 的 bucket（HIGH/MEDIUM/LOW），防止"password"/"test"这种字面量 FP。finding 消息里把 literal 截到 32 字符避免日志泄漏。
- **DFA 论据**：要追"literal 经过了 `toCharArray` / `getBytes` 还能找到源头"——AST 正则匹配不到这种"char[] pwd = data.toCharArray() then PasswordAuthentication(user, pwd)"形式。DFA 走 `MOVE → CALL → MOVE` 链，LiteralSourcedTracker 抽象出来供 `HardCodedKeyChecker` 共用。
- **误报画像**：熵启发会过滤常见 placeholder（"password"/"test"/"admin123"）和短字面量；JCA test vector 是 FP（"0123456789abcdef"）。

#### 1.7.2 `HardCodedKeyChecker`（CWE-321）

- **实现机制**：DFA-真（与上面同源）。共用 `LiteralSourcedTracker(minLengthOverride=4)`，`NEW SecretKeySpec.<init>/2` 的 args[0] 是 literal 或 literal-of-getBytes 时 fire。
- **DFA 论据**：和 `HardCodedPasswordSinkChecker` 同样需要跨语句的字面量传播；两条规则共用一个 `LiteralSourcedTracker` 是 R-9 重构。

#### 1.7.3 `FieldHardcodedPasswordChecker`（CWE-798）

- **实现机制**：DFA-真（FIELDSTORE + 字面量）。`initFunction` 收集 `fn.fieldAnnotations()`：field 标 `@Value` / `@Resource` / `@Inject` / `@Autowired` / `@JsonProperty` 等视为 DI 注入（跳过），class 标 `@ConfigurationProperties` / `@Component` / `@Service` / `@Repository` / `@RestController` / `@Controller` / `@Configuration` / `@JsonRootName` 等视为配置 bean（整 class 跳过）；`<clinit>` 留给 `StaticApiKeyChecker` 避免双报。
  `checkInstruction` 看 FIELDSTORE，dest 名字匹配 sensitive-name regex（password / passwd / pwd / secret / credential / api[_-]?key / access[_-]?key / ...），跳过 DI 字段、跳过 placeholder（todo / fixme / xxx / changeme / your-api-key ...），lhs 通过 `resolveRhs` 一跳 MOVE 折叠到字面量，且字面量 >= 4 字符则 emit。
- **DFA 论据**：FIELDSTORE 序列在 TAC IR 里是独立的 instruction kind，DFA 直接配对 receiver（`this.fieldName`）和 dest 的字面量关系。AST 能做但要碰 FieldDeclaration 的 modifier 与 initializer 表达式——跨字段声明 / 直接赋值两种形态，AST 难统一。
- **误报画像**：post-construct 重新赋值的字段也报（这其实是 TP 的延伸——源码里仍有硬编码）。

#### 1.7.4 `StaticApiKeyChecker`（CWE-798）

- **实现机制**：DFA-真（`<clinit>` 局域）。只在 `fn.name().equals("<clinit>")` 函数里扫；MOVE-from-string-Const 且 dest 名字匹配 sensitive-name regex（`api[_-]?key` / `apikey` / `secret` / `token` / `password` / `passwd` / `pwd` / `access[_-]?key` / `private[_-]?key` / `session[_-]?key` / `credential`）且值 >= 4 字符且不在 placeholder 集 → emit。和 `FieldHardcodedPasswordChecker` 互补（静态字段 vs 实例字段）。
- **DFA 论据**：`<clinit>` 是 TAC 给静态字段初始化器合成的"特殊函数"，DFA 知道它只包含 `STATIC FIELD MOVE-from-Const`——**直接限定在 `<clinit>` 是 DFA 的优势**，AST 要识别静态字段 init 还得 dedupe 与构造器的关系。
- **误报画像**：JCA 算法名常量是 FP。

#### 1.7.5 `PredictableKeyPasswordChecker`（CWE-798 / CWE-259）

- **实现机制**：DFA-真（直接字面量）。`NEW PBEKeySpec.<init>` / `PBEParameterSpec.<init>` / `SecretKeySpec.<init>` 第一个参数是 `TacOperand.Const STRING` 即 fire。
- **DFA 论据**：比 `HardCodedPasswordSinkChecker` 更窄——只接受 inline-literal 形态、**不**接受 MOVE-then-pass。AST 能做但 `{char[] pwd = {'p','a','s','s'}; new PBEKeySpec(pwd);}` 在 IR 里是 char-array 字面量，仍然 const；DFA 不分 char-array 还是 String 一律命中。
- **误报画像**：test fixture 是 char-array 字面量也是 FP。

### 1.8 CWE-114 / CWE-332 / CWE-336 / CWE-338 / CWE-477 / CWE-523 / CWE-758 / CWE-786 — 杂

#### 1.8.1 `ProcessControlChecker`（CWE-114）

- **实现机制**：DFA-真（call-site 模式）。CALL 的 `calleeSig` 是 `System.loadLibrary/1` 或 `Runtime.loadLibrary/1` 且 args[1]（TAC 偏移后）**不是**字符串 / char / null 字面量常量时 emit（literal 形式交给 AI 判定）。
- **DFA 论据**：纯 call-site 模式，AST 同样能做。DFA 版本刻意**不**做 taint 跟踪——结构不安全"在 long-running process 里**任何** loadLibrary 都错"——这是和 `taint/UnsafeJniChecker` 的分工。
- **误报画像**：deployment 锁定 + literal 名字也可能误报（放过 literal，但 FP 主要由 AI 收尾）。

#### 1.8.2 `PredictableRandomSeedChecker`（CWE-332 / CWE-336）

- **实现机制**：DFA-真。`NEW Random / SecureRandom` 第一个参数是 INT/LONG Const → fire CWE-332 / CWE-336；CALL `setSeed` 第一个参数是 INT/LONG Const → fire。
- **DFA 论据**：判定条件是"构造或 setSeed 的首参是 Const"，AST 一样能做；DFA 写出来短、确定性强。
- **误报画像**：测试代码普遍 FP（test seed 是设计内的）。

#### 1.8.3 `WeakPrngChecker`（CWE-338 / CWE-336）

- **实现机制**：DFA-真（call-site 模式）。四个分支：`Math.random/0`、`new Random()`（`Random.<init>/0`）、`new Random(<literal>)`（`Random.<init>/1` 且 args[0] 是 Const）、`SecureRandom.setSeed(<literal or var>)`。`SecureRandom.setSeed(<var>)` 走 LOW 严重度，因为没法证明 var 不是常量 byte[]。
- **DFA 论据**：与 `PredictableRandomSeedChecker` 互补——这条覆盖"用错 PRNG"（Math.random / Random），CWE-338；那条覆盖"种子可预测"（literal seed），CWE-336。DFA 简单清晰。
- **误报画像**：Math.random 在非安全场景（动画 / 抽样）普遍 FP。

#### 1.8.4 `ObsoleteFunctionChecker`（CWE-477）

- **实现机制**：DFA-真（call-site 模式）。4 条白名单 entry（`Date.parse/1` / `DataInputStream.readLine/0` / `String.getBytes/0` / `URLEncoder.encode/1`），`args[0]` 是 TacVar 时取其 type 的短名比 entry 期望类名；args[0] 是 Const（`"x".getBytes()` 形态）时按 `calleeSig` 短名匹配 `scope`（scopeFallback 标志）。`arity = ins.args.size() - 1`（去掉 receiver）。
- **DFA 论据**：receiver 类型解析要 IR 的 TacVar.type()，AST 走 JavaParser 也能拿但要 symbol-solver。DFA 限定在 4 条白名单保持精度。
- **误报画像**：单条 entry 的 scopeFallback 标志设计保守；可能漏报 `Date.parse` 在 DateFormat 链上的二级调用。

#### 1.8.5 `XxeChecker`（CWE-611）

- **实现机制**：DFA-真（**三段状态机**）。`plainFactories` 跟 `NEW DocumentBuilderFactory / SAXParserFactory / XMLInputFactory` 的 dest；`securedFactories` 跟这些 var 在收到 `setFeature / setXIncludeAware / setExpandEntityReferences / setValidating` 之后的状态；`parsers` 跟 `newDocumentBuilder / newSAXParser / createXMLStreamReader` 的 dest，`parserFromFactory` 记 parser 来源 factory。`parse / unmarshal` sink 命中且 parser 的 factory 不在 secured 集合 → emit。MOVE 通过传递 parser 状态。
- **DFA 论据**：判定需要 factory 构造 + secure-config 调用 + parse 调用三事件在**同函数**内的因果。AST 单 visitor 容易混"哪次 setFeature 影响哪个 factory"；DFA 按 var 状态机跟踪，**三步不同事件但同 var 名**就关联。
- **误报画像**：class-level 配置 / Spring 集中配置（XmlConfigurer）大概率漏报。

### 1.9 CWE-358 / CWE-486 / CWE-547 / CWE-563 / CWE-580 / CWE-590 / CWE-665 — 杂

#### 1.9.1 `CompareClassesByNameChecker`（CWE-486）

- **实现机制**：DFA-真（call-site 模式）。CALL 的 `calleeSig` 命中 `Class.getSimpleName/0`（多 shape 都接受：`java.lang.Class.getSimpleName/0` / `Class.getSimpleName/0` / `getSimpleName/0` / `*getSimpleName/0`）即 emit。**无数据流**：不查 args。
- **DFA 论据**：单事件判定，AST 写一个 visitor 一样。放在 DFA 是历史（早期 R-? 阶段批量实现）。
- **误报画像**：纯打印（log 一下类名）也 fire。

#### 1.9.2 `CompareWithSelfChecker`（CWE-570 / CWE-571）

- **实现机制**：DFA-真（**别名类跟踪**）。MOVE 把 dest 加入 src 的别名类（与 `CollectionSelfReferenceChecker` 同机制）。BINOP `EQ` / `NE` 两个 operand 共享一个别名类 → fire。`EQ` 标 CWE-571（永真），`NE` 标 CWE-570（永假）。
- **DFA 论据**：纯 AST 匹配 `x == x` 抓不到 `a = x; b = x; a == b`（不同字面、同一个值）。DFA 别名类正好覆盖。
- **误报画像**：Object.equals / Comparable.compareTo 的"自己比较自己"是合法 API，DFA 收不到这种语义。

#### 1.9.3 `EqualsCalledOnNullChecker`（CWE-595 / CWE-754）

- **实现机制**：DFA-真（**两路 null 跟踪**）。
  1. MOVE-from-Const-NULL 把 dest 加 `knownNull`；MOVE-from-known-null var 同样加（传递）。
  2. CALL `equals` 命中两条：
     - args[1] 是 Const-NULL → fire CWE-754（`equals(null)` 永假——很可能写反）；
     - args[0] 是 known-null var → fire CWE-595（运行 NPE）。
- **DFA 论据**：`String s = null; ... s.equals("x");` 形态 AST 看不见"def 到 use 之间的 null 状态"。DFA 一遍 per-function null 跟踪解决。
- **误报画像**：MOVE-from-known-null-then-MOVE-from-clean-source 会被清掉（注释里写明 bug fix），所以 reassignment 是干净的。

#### 1.9.4 `IteratorConcurrentModificationChecker`（CWE-820）

- **实现机制**：DFA-真。`iterator/listIterator` 的 args[0] 收进 `openIterators`；后续 `add/remove/clear/addAll/removeAll/retainAll/put/putIfAbsent/removeIf/replaceAll` 的 args[0] 若在 `openIterators` → fire。
- **DFA 论据**：要"has-iterator ↔ mutation"在**同函数**内跨语句的关联；AST 必须先建 iterator-block 到 collection 的映射——DFA 直接看 receiver name。
- **误报画像**：跨函数 iterator（caller 取 iterator、callee 改 collection）看不见。

#### 1.9.5 `CorsMisconfigurationChecker`（CWE-942）

- **实现机制**：DFA-真（**一跳常量折叠**）。CALL 命中 `setAllowedOrigins/1|2` / `addAllowedOrigin/1` / `allowedOrigins/1` / `allowedOrigin/1` 视为 CORS 配置；args 是字面量 `"*"` 直接 fire；args 是 TacVar 时**一回合**找定义它的 CALL，匹配 `Arrays.asList` / `List.of` / `copyOf` / `singletonList` 等 list-factory，且 list-factory 的 args[0] 是字面量 `"*"` → fire。
- **DFA 论据**：`cfg.allowedOrigins(List.of("*"))` 形态 AST 正则**能**匹配但要先识别 `List.of` 的返回；DFA 的"一跳 def 折叠"是天然的操作。一跳上限（k=1），文档明说"k>1 留给 future DefChainHelper"。
- **误报画像**：限制 listed、getter chain 不在。

#### 1.9.6 `MissingCryptoInitChecker`（CWE-325）

- **实现机制**：DFA-真（按 TYPE 跟踪）。`getInstance` 命中且 dest 是 KeyGenerator / KeyPairGenerator / MessageDigest，把 TYPE 短名加 `cryptoVarsByType[shortName] = true`；`init / update / initialize / reset` 命中 TYPE 短名一致的 receiver → `false`；`generateKey / digest / generateKeyPair` 命中 TYPE 短名一致且状态是 `true` → fire CWE-325。
- **DFA 论据**：同 `InsecureCookieChecker` 一样的 TYPE 粒度状态机——多事件关联，DFA 一遍就出来。
- **误报画像**：多 KeyGenerator 实例同函数按 TYPE 粒度跟踪会"先创建的污染后创建的"。

#### 1.9.7 `InsecureTempFilePermsChecker`（CWE-378）

- **实现机制**：DFA-真。`createTempFile` CALL 把 dest 加 `newlyCreated`；`setWritable / setReadable / setExecutable` 的 receiver 加 `permsSet`；`finishFunction` 看 `newlyCreated \ permsSet` 差集。
- **DFA 论据**：判定就是"create → setX"的事件配对；AST 单 visitor 也能做但要保持 receiver var 一致。
- **误报画像**：`Files.createTempFile` 走 NIO，文档显式说这只覆盖旧 `File.createTempFile`。

#### 1.9.8 `InsecureCryptoChecker`（CWE-327 / CWE-328 / CWE-780）

- **实现机制**：DFA-真（双源：literal 与 const-var）。两条源：
  1. MOVE-from-string-Const 且字面量（小写）命中 `WEAK` map（md5 / md2 / md4 / sha-1 / sha1 / rsa / rsa/ecb/nopadding / rsa/none/nopadding / des / desede / rc4 / blowfish）→ 把 dest 标 `weakAlgoVar`；
  2. CALL `getInstance` 命中时扫所有 args，Const-STRING 命中 WEAK → fire；TacVar 命中 `weakAlgoVar` → fire。

  文档承认：R-? 后 DFA 部分不再 return 早于 inline literal——即 AST 版的 inline-literal 不被压制，否则 sweep runner 0 cweFire。
- **DFA 论据**：`String ALGO = "MD5"; ... getInstance(ALGO)` 形态 AST 单 visitor 看不到"dest 名字"被记到 next use；DFA 的 `weakAlgoVar` map 解决。
- **误报画像**：JCA 兼容性代码（`getInstance("MD5")` 是为了对接老系统）依赖 AI 收尾。

#### 1.9.9 `MissingCryptoStepChecker`（CWE-325）— **STUB（R787f）**

- 旧 dfa regex "Cipher.* 缺 Mac.*"。已迁 plumb-style。当前 stub 抛 UOE。**不**与 `MissingCryptoInitChecker` 混淆——后者是 DFA-真处理"factory 没 init()"。

#### 1.9.10 `InsecureTempFilePermsChecker`（CWE-378）— 已在 §1.9.7。

#### 1.9.11 `UncheckedReturnChecker`（CWE-252）

- **实现机制**：DFA-真（**用-未用配对**）。`checkInstruction` 双扫：
  1. 收集**所有** instruction 的 `lhs1 / lhs2 / args` 到 `usedNames`（注意：**不**包括 dest）；
  2. CALL 命中 `FAILABLE`（method × type 白名单：`File.delete / mkdir / mkdirs / renameTo / createNewFile`；`close / commit / rollback / execute` on Connection/Statement 等；`get` on Map/List；`next` on Iterator；`read` on Stream/Reader；`readLine` on BufferedReader；`remove` on Collection/Map）→ 加 `candidates`。
  `finishFunction` 找 `candidates` 中 `dest` 名**不在** `usedNames` 的——即"返回值被丢弃"——fire CWE-252。
- **DFA 论据**：要做"dest 的 use-集"。AST 单 visitor 也能配"def-use"，但 IR 的 SSA dest 唯一对应一个 CALL 一次——**少一个"全函数去重 use"步骤**。也避免了"赋值给另一个 var 之后才被使用"的复杂 AST dedup。
- **误报画像**：判定 receiver short class 时**显式接受 FQN 跟 short 两种格式**（`#` vs `.`），漏配的话整条规则会静默 dead（注释里就 R-X 修复案例）。

#### 1.9.12 `PredictableKeyPasswordChecker`（CWE-798 / CWE-259）— 已在 §1.7.5。

#### 1.9.13 `StaticApiKeyChecker`（CWE-798）— 已在 §1.7.4。

### 1.10 CWE-572 / CWE-579 / CWE-585 / CWE-609 — 多线程 / 序列化（已迁 plumb-style）

#### 1.10.1 `ClassThreadChecker`（CWE-572 / CWE-579 / CWE-585 / CWE-609）— **STUB 容器（R787a–e）**

- 顶层类是 aggregator，但 4 个子 checker（`ThreadRunChecker` / `NonSerializableInSessionChecker` / `EmptySyncBlockChecker` / `DoubleCheckedLockingChecker`）**全部是构造时抛 UOE**——已迁 `org.plumb.style.style.CallToThreadRunCheck` / `NonSerializableInSessionCheck` / `EmptySyncBlockCheck` / `DoubleCheckedLockingCheck`（AstChecker）。
- 顶层 `ClassThreadChecker` 自身保留为 no-op 兼容层。
- **未走 DFA**——R787 系列判定"AST-style 比 DFA-style 更诚实"，所以这些规则的历史 dfa 实现是 regex。

### 1.11 CWE-491 / CWE-580 / CWE-226 / CWE-398 / CWE-617 — 杂（R787 stub）

#### 1.11.1 `ClassBasedIssueChecker`（CWE-491）— **STUB（R787h）**

- 旧 dfa 实现 regex "protected Object clone() {...} 但不 final"。已迁 `org.plumb.style.style.ClassBasedIssueCheck`（AstChecker）。当前 stub 抛 UOE。**判定是 AST-level 的**——AST 拿 modifier 集比 DFA 直接。

#### 1.11.2 `ObjectModelChecker`（CWE-580）— **STUB（R787h）**

- 旧 dfa 实现 regex "clone() 没 super.clone()"。已迁 `org.plumb.style.style.ObjectModelCheck`（AstChecker）。当前 stub 抛 UOE。

#### 1.11.3 `SensitiveInfoUnclearedChecker`（CWE-226）— **STUB（R787i）**

- 旧 dfa 实现 regex "StringBuilder sensitive-named 没用 setLength(0) / delete(0, len) 清理"。已迁 `org.plumb.style.style.UnclearedSensitiveInfoCheck`（AstChecker）。当前 stub 抛 UOE。

#### 1.11.4 `PoorCodeQualityChecker`（CWE-398）— **STUB（R787g）**

- 旧 dfa 实现 regex "empty block / empty method body"。已迁 `org.plumb.style.style.PoorCodeQualityCheck`（AstChecker）。当前 stub 抛 UOE。

#### 1.11.5 `ReachableAssertionChecker`（CWE-617）— **STUB（R787i）**

- 旧 dfa 实现 regex `assert false; assert 0;` 等永假断言。已迁 `org.plumb.style.style.ReachableAssertionCheck`（AstChecker）。当前 stub 抛 UOE。

### 1.12 CWE-478 / CWE-674 / CWE-832 — 控制流 / 锁

#### 1.12.1 `RecursionChecker`（CWE-674）

- **实现机制**：DFA-真（call-site 自递归）。CALL 的 `calleeSig` 末段（按 `.` / `#` split 去 method name 和 arity）匹配 enclosing `fn.name()` 末段 + `fn.params().size()` 视为自递归，emit。`reported` 静态集去重。
- **DFA 论据**：判定跨函数调用图（自己→自己）——AST 也能做但 visitor 容易在 nested-class 时算错 enclosing class。DFA 显式走 fn.name() 与 calleeSig 比对。
- **误报画像**：不查 base case（即使 `if (n > 0) recurse(n-1);` 也 fire）。mutual recursion（A→B→A）漏检。

#### 1.12.2 `UnlockNotLockedChecker`（CWE-832）

- **实现机制**：DFA-真（线性前看）。`unlock` 命中时**线性 walk back** 整函数找同 receiver 的 `lock`——找不到 emit。lockId 来自 args[0] 的 TacVar name 去 SSA 后缀。
- **DFA 论据**："先有 lock 后有 unlock 在同函数"——AST 必须先 walk 进 try-block，DFA 一次线性前看就够。
- **误报画像**：条件 lock（if-branch 内）按 linear 走会过 fire；synchronized 关键字不走 .lock()/.unlock() 形态所以完全不见。

#### 1.12.3 `DeadlockChecker`（CWE-833）

- **实现机制**：DFA-真（**跨函数 lock 序列比对**）。`finishFunction` 收集本函数内 `*.lock/0` 的有序 lock 序列（按 receiver name 去 SSA）；`finish` 阶段**两两配对**所有函数对：lock 集合相同但顺序不同 → fire。R-759-E 加限制：只比对**同一 enclosing class** 内的两个函数（Juliet 风格），且 dedup key 用 `enclosingClass :: sigA :: sigB` 而非单纯 sig（避免 MergedProgram 把多 CWE833 文件坍缩成同一对）。
- **DFA 论据**：必须收集所有函数的 lock 序列后做 O(n²) 比对——AST 可以做但要先建 function-body tree。DFA 走"per-function 收集 + finish 阶段 cross-fn"两步配 engine RPO。
- **误报画像**：3+ lock 循环死锁漏检；lock id 是 receiver TacVar name 跨 class 同名时**故意过滤**（Juliet 风格），生产代码同名 static lock 可能漏报。

#### 1.12.4 `ServletAtomicityDfaChecker`（CWE-362）

- **实现机制**：DFA-真（**跨方法 atomicity**）。`checkInstruction` 收集 `session.getAttribute("k")` 和 `session.setAttribute("k", ...)` 的 key（必须是字面量），按 key × method sig 入 map；`finish` 阶段：key 至少 2 个不同 method 触过 + 同时有 get 和 set → fire。
- **DFA 论据**：必须看**跨方法**对同一 attribute 的访问——AST 单方法 visitor 看不到 `doGet` 和 `doPost` 共享 `k`。
- **误报画像**：同步在另一 helper / Filter 兜底时无法识别。

#### 1.12.5 `SingletonRaceDfaChecker`（CWE-366）

- **实现机制**：DFA-真（**跨方法 field 访问**）。`initFunction` 只对 class 注解含 `Controller` / `RestController` 的类收集 field 名（注意：servlet extends 看不见——故意限制）；`checkInstruction` 看 FIELDLOAD / FIELDSTORE 的 lhs1 是不是 `this` + fieldName 在 class field 集；reader / writer map 按 `class::field` 维护。`finish` 阶段 cross-method 配对 fire。
- **DFA 论据**：同 `ServletAtomicityDfaChecker`——必须看跨方法对同一 field 的读写。AST 走"两遍 method visitor"也难处理 servlet race 这种"非 this" 形态。
- **误报画像**：extends `HttpServlet`（无 Controller 注解）漏检；同步在另一类兜底时无法识别。

### 1.13 CWE-457 / CWE-459 / CWE-252 — 字段未初始化 / 实体持久化 / DFA-style 已有

#### 1.13.1 `UninitNonnullDfaChecker`（CWE-457）

- **实现机制**：DFA-真（**跨方法 def**）。`initFunction` 收集 field 注解中 NONNULL（`@NonNull` / `@NotNull` / `@Nonnull` / `org.jetbrains.annotations.NotNull` / `androidx.annotation.NonNull` / `lombok.NonNull` / `edu.umd.cs.findbugs.annotations.NonNull` / `org.checkerframework.checker.nullness.qual.NonNull` 等）——只在 name == enclosingClass 的 ctor 函数里 unassignedNonnullFields 初始化为全集；FIELDSTORE `this.f` 命中 → 从 unassigned 集合里 remove；CALL 命中 → 查 callee summary 有 `Operation.WriteToField(f)` → remove；`finishFunction` 仍在 unassigned 集合里的 f → fire。
  `summarizeFunction` 贡献 `WriteToField(f)` op 给每个被本函数赋过的 @NonNull field。
- **DFA 论据**：必须看"ctor 体里 setName("x")"这种 helper 内的 FIELDSTORE，AST 走 ctor body 才能看到；DFA 跨函数 summary 直接传 WriteToField op。**这是 DFA 比 AST 优的核心场景**。
- **误报画像**：aggressiveness="low" 时跨方法 lookup 关闭；aliased 字段（super.field 写法）漏检。

#### 1.13.2 `OrmLostUpdateDfaChecker`（CWE-459）

- **实现机制**：DFA-真（**三层 taint 变体**）。`checkInstruction` 对每条 CALL 拆 method 段：
  1. `<init>` + ctorType 不在 NON_ENTITY_TYPES 白名单 → `transientVars[dest]=true` + `allocationSite[dest]=ins`；
  2. `persist / save / merge / update / saveOrUpdate / saveOrUpdateCopy / store` → 全部 args 标 `persistedVars[arg]=true`；
  3. 其他 CALL 查 `ctx.summaryCache().get(callee)`：每个 `Operation.PersistsParam` 让对应 arg 标 persisted；每个 `Operation.WriteToField` 让对应 transient arg 标 mutated。
  FIELDSTORE 的 base 是 transient → 标 mutated。"high" aggressiveness 还把任何 CALL 上 transient 出现也标 mutated（setter-via-helper）。
  `finishFunction` 看 transient + mutated + !persisted → fire。
- **DFA 论据**：跨方法持久化（service → dao → EntityManager）必须走 FunctionSummary chain；AST 看不到这种"方法间委托"的持久化路径。**典型的 DFA + summary 联动场景**。
- **误报画像**：aggressiveness="low" 关 cross-fn lookup → 漏报 cross-method 模式；pure name 启发（不要求 @Entity）会把所有 NEW 都视为候选。

#### 1.13.3 `OrmUnnecessaryGetDfaChecker`（CWE-252）

- **实现机制**：DFA-真（get result 用-未用）。CALL 命中 `get / find / findById / getById / read` → dest 加 `fromGetVars[dest]=true`；MOVE 沿别名传递。`finishFunction` 看 dest 有没有 FIELDLOAD——没有就 fire。
- **DFA 论据**：跨 MOVE 的 alias；AST 单 visitor 处理 `get → t1 → t2 → use` 要走 3 跳 visitor。
- **误报画像**：用作 Map key、getter-chain 间接用会漏报（FIELDLOAD 检查只看 receiver 是不是这个 var）。

#### 1.13.4 `OrmLoadNullCheckDfaChecker`（CWE-252）

- **实现机制**：DFA-真。CALL 命中 `load / getReference / loadById / getOne`（按 `#` split method name）→ dest 加 `fromLoadVars`；MOVE 传递；`finishFunction` 看 BRANCH 是 `EQ/NE` 且一边是 `null` literal、另一边是 fromLoadVars → fire "JPA load 永不返 null，null check 是 dead code"。
- **DFA 论据**：要"load 后跨 MOVE 到 null 比较"——AST 同样要做 aliasing。
- **误报画像**：JPA 自定义 dialect 真的能返 null 时会 FP。

### 1.14 CWE-758 / CWE-770 / CWE-400 — 流 / 资源

#### 1.14.1 `ResourceExhaustionChecker`（CWE-400）— 已在 §1.1.2。

#### 1.14.2 `InsecureTempFilePermsChecker`（CWE-378）— 已在 §1.9.7。

#### 1.14.3 `BadShiftChecker`（CWE-758）— 已在 §1.5.2。

### 1.15 SRC-正则集合（在 dfa 包下但用 SourceReader 读源）

#### 1.15.1 `ClassSourceChecker`（CWE-382 / CWE-526 / CWE-570 / CWE-571）— **SRC-正则（aggregator）**

- 顶层 aggregator 装 4 个子 checker：`SystemExitChecker`（System.exit / Runtime.exit）、`EnvVarChecker`（System.getenv）、`AlwaysFalseChecker`（`if(false) / 0==1 / X==X+N / X!=X`）、`AlwaysTrueChecker`（`if(true) / 0==0 / X==X`）。
- 每个子 checker **在 `finish` 阶段读源文件**到 String，正则匹配；`reported` set 按 `prog.typeName|line` dedup。
- **DFA 论据（为什么仍然在 dfa 模块）**：CWE-570/571 AST-only 时有"X==X" 形态（CompareWithSelfChecker）补；CWE-382/526 是 source-only。**但 dfa 这 4 个子本身是 source-reading，不是 DFA**——R-668 之后没迁 AST 单纯保留兼容。
- **误报画像**：大量 false positive（`System.exit(0)` 才是 exit，regex 不区分）；`main` 方法豁免用花括号深度匹配，不可靠。

#### 1.15.2 `SuspiciousCommentChecker`（CWE-546）— **SRC-正则 / AI-启发**

- `finish` 读源文件；维护 `inBlock` 状态机提取行注释 / 块注释（"//" vs "/*...*/" 在同一行的优先级处理）；正则 marker `BUG|FIXME|XXX|HACK|WORKAROUND|TODO:|TBD:|SECURITY:|VULN|CVE|INJECTION`。`seenInFile` 按 `(startLine, commentTextSnippet)` dedup。
- **DFA 论据**（有限）：marker 列表故意使用冒号后缀（`TODO:` / `SECURITY:`）只匹配真标记，避免 javadoc 中"security restrictions"等无辜词误命中。**不**是 DFA 而是 source regex；放 dfa 模块是历史。
- **误报画像**：所有"仅 marker 出现"都可能 FP（javadoc 提到 `VULN-2021-1234` CVE 编号、linter 自动生成的 "FIXME: stub" 注释）；AI 收尾。

### 1.16 顶层（不在 `taint/`）的跨函数 DFA 模式总结

| 规则 | 跨函数机制 | 备注 |
|------|-------------|------|
| `DeadlockChecker` | 收集所有 fn 的 lock 序列后 O(n²) 比对 | R-759-E 限定同 enclosing class |
| `ServletAtomicityDfaChecker` | 收集 attribute 访问者集合 | key 必须是字面量 |
| `SingletonRaceDfaChecker` | 收集 field reader/writer 集合 | 需要 `@Controller` 注解 |
| `UncaughtExceptionPropagator` | hasThrow / hasCatch 全局标志 | `currentFunction` 必须在 finish 里重设 |
| `UninitNonnullDfaChecker` | summary 上贡献 `WriteToField(f)` | 给后续 ctor 看 |
| `OrmLostUpdateDfaChecker` | summary 上贡献 `PersistsParam(pi)` | 给后续 caller 看 |
| `MissingAuthorizationChecker` / `InformationExposureChecker` / `MissingInputValidationChecker` | 不跨函数，但需要 class+method+param 三类注解联动 | 走 `TacFunction` 三类元数据通道 |

---

## 2. `checkers/taint/` 子包 taint 规则（44 个，按"复杂度"分组）

### 2.1 零代码子类（仅 super + TaintRule，sink 完全在 sink-families.yaml 配置）

#### 2.1.1 简单 list（10 个）

- `BadCertVerificationChecker` (CWE-295, `ssl.sinks`, CRITICAL)
- `ElInjectionChecker` (CWE-94, `el.sinks`, CRITICAL)
- `InsecureRandomChecker` (CWE-330, `random.sinks`, MEDIUM)
- `JavaCodeInjectionChecker` (CWE-94, `code-execution.sinks`, CRITICAL)
- `JcrInjectionChecker` (CWE-94, `jcr.sinks`, HIGH)
- `JspDynamicIncludeChecker` (CWE-829, `jsp-include.sinks`, CRITICAL)
- `OgnlInjectionChecker` (CWE-94, `ognl.sinks`, CRITICAL)
- `UnsafeJniChecker` (CWE-114, `jni.sinks`, CRITICAL)
- `UnsafeReflectionChecker` (CWE-470, `reflection.sinks`, HIGH)
- `XPathInjectionChecker` (CWE-643, `xpath.sinks`, HIGH)

- **实现机制**：3 行代码——构造器 super 一个 TaintRule(ruleId, sourceKind, sinkFamilyKey, cwe, severity, TaintDomain)。所有 source / sink / propagation 行为由 `TaintedCheckerBase` + `sink-families.yaml` 一起定义。
- **DFA 论据**（与基类共享）：CWE-89/94/78/22/601/113/134 全部是"taint flow to sink"型，**DFA 是唯一可行实现**——AST 单 visitor 看不到"from input-source call to sink"的跨语句流。`taint/untrusted-source` 在 `InputSourcePatterns` 静态注册（readLine / parseInt / getParameter / getQueryString / getInputStream ...），基类的 `MOVE / BINOP / CALL` 传播自动覆盖跨方法 + 跨函数的 summary fold。
- **误报画像**：取决于 `InputSourcePatterns` 的精度；JavaParser-style 静态模板（生成代码 / framework stub）漏报。
- **可观测的子类内差异**：无。所有差异都在 `sink-families.yaml` 的 family 定义里（callee sig pattern 列表 + arg index）。

### 2.2 自定义 sinkArgumentIndex 的子类（11 个）

#### 2.2.1 `LdapInjectionChecker` (CWE-90)

- `sinkArgumentIndex = 1`：DirContext.search(base, filter, controls) 的 filter 在 Java arg 1。

#### 2.2.2 `HttpResponseSplittingChecker` (CWE-113)

- `sinkArgumentIndex = 1`：setHeader(name, value) / sendError(int, value) 的 value 在 Java arg 1。

#### 2.2.3 `SessionFixationChecker` (CWE-384)

- `sinkArgumentIndex = 1`：setAttribute(name, value) / setHeader(name, value) 的 value 在 Java arg 1。

#### 2.2.4 `TrustBoundaryViolationChecker` (CWE-501)

- `sinkArgumentIndex = 1`：setAttribute(name, value) / HashMap.put(key, value) 的 value 在 Java arg 1。

#### 2.2.5 `XssInjectionChecker` (CWE-79)

- `sinkArgumentIndex = 1`：response writer / sendError(int, msg) 的 msg 在 Java arg 1。R-692 注释解释：默认 0 在 sendError 形态误把 int code 当 tainted 查，**永远查不到** taint，所以 override。

#### 2.2.6 `UncontrolledMemAllocChecker` (CWE-789)

- `sinkArgumentIndex = 0`：new byte[tainted] / HashMap(int) 的 size 在 Java arg 0。
- 与 `IntOverflowChecker` 共享 source kind `plumb.numeric.overflow-hazard`——`parseInt(readLine())` 的 numeric taint 流到 `new byte[count]`。

#### 2.2.7 `OpenRedirectChecker` (CWE-601)

- 默认 0 即对（`response.sendRedirect(String)` 的 String 在 Java arg 0）。
- AI prompt 区分 TP / FP。

#### 2.2.8 `FormatStringChecker` (CWE-134)

- 默认 0。
- AI prompt 区分 TP / FP（`printf(tainted)` 是 TP；`printf("%s", tainted)` 是 FP）。

#### 2.2.9 `CleartextNetworkCredentialSinkChecker` (CWE-319)

- `sinkArgumentIndex = 2`：`db.credential.sinks` 的 DriverManager.getConnection(url, user, password) 在 Java arg 2。
- 是 `DBCredentialSinkChecker` 的 CWE-319 兄弟（同 sink family，不同 CWE 标签）。

#### 2.2.10 `CleartextTxChecker` (CWE-319)

- `sinkArgumentIndex = 0`：`cleartext-tx.sinks` 的 writer.print / println / write 的 value 在 Java arg 0。
- **有 `checkInstruction` 扩展**：`PasswordAuthentication.getPassword` / `KerberosKey.getEncoded` 视为额外 source（不属于 InputSourcePatterns 静态注册但 Juliet 模式用到）——`markSource(dest, "input:PasswordAuthentication.getPassword", ...)`。

#### 2.2.11 `CommandInjectionChecker` (CWE-78)

- 默认 0。
- AI prompt 长，区分 TP（`Runtime.exec(String)` 真可控）/ FP（`ProcessBuilder.command(List<String>)` 每元素都过 allow-list）。

- **共同 DFA 论据**：sink 位置的"哪一参数是 tainted value"是规则最易错的地方。**基类的默认 arg=0 假设**只在"第一个 user-arg 是数据"时正确；上面 11 条中至少 5 条需要 override。**这是 taint-style 规则写得好不好的关键**——错一个 arg 整条规则就静默 dead（命中也只是 0 报）。

### 2.3 带丰富 AI prompt 的子类（无 sink index 差异，但 prompt 是规则价值的核心）

- `SqlInjectionChecker` (CWE-89)：AI prompt 区分 TP / FP（`PreparedStatement.setString` / `setXxx` 是参数化 → FP）。
- `DBCredentialSinkChecker` (CWE-256)：AI prompt 区分 TP / FP（vault / OAuth token / test fixture）。
- `ExternalConfigSettingSinkChecker` (CWE-15)：AI prompt 区分 TP / FP（受控 config bundle / logging config / test fixture）。
- `OpenRedirectChecker`、`XssInjectionChecker`、`FormatStringChecker`、`HttpResponseSplittingChecker`、`LdapInjectionChecker`、`XPathInjectionChecker`、`CommandInjectionChecker`、`CleartextTxChecker`、`CleartextNetworkCredentialSinkChecker`、`DBCredentialSinkChecker` —— AI prompt 是规则的"过滤层"。
- **DFA 论据**：这些规则在 IR 上做的检测是"taint 到达 sink family"——这是必要条件；是否真正可利用（FP 排除）由 AI 判断。**这是 R-7 阶段"LLM 作为规则收尾"的工程化**——把 DFA + AI dedup 解耦，DFA 写一个规则、prompt 收尾。

### 2.4 强 DFA 自定义子类（`checkInstruction` 写实际逻辑）

#### 2.4.1 `ArrayIndexChecker` (CWE-129) — **R767 deprecated，但保留兼容**

- **实现机制**：继承 `TaintedCheckerBase`（基类 taint 走 InputSourcePattern），但 `checkInstruction` 额外做：
  1. `NEWARRAY` 的 lhs1 是 tainted var 且没有被 `GE/GT` guard → fire CWE-129 "new T[size] with tainted size"；
  2. `ARRAYLOAD/ARRAYSTORE` 的 lhs2 是 tainted var 且没 `GE/GT` guard → fire；
  3. `GuardTracker` 把 `BINOP` 的 `< / <= / > / >=` 边界记录；`isGuardedByIncludingDerived` 跨 SSA-derivation 检查。
- **DFA 论据**："用 array index 之前必须 `if (i < arr.length)` guard"——本质是**数组边界 + 跨语句 guard 跟踪**。Dwarf 边界 checker 的简化版。R765 报告：boundary 框架的 `ArrayBoundsConstraintChecker`（Range lattice + Z3）精度 0.636，taint 这条 0.294，所以 R767 deprecated。
- **误报画像**：跨函数 guard 看不见；guard 操作数必须和 array index 是同一 var 名（少数路径下会漏）。

#### 2.4.2 `DivByZeroChecker` (CWE-369) — **R768 deprecated，但保留兼容**

- **实现机制**：BINOP DIV / MOD 的 rhs 是 Const-INT 0 → fire；rhs 是 tainted TacVar 且 `GuardTracker.isGuardedBy(rhs, NE)` 没说非 0 → fire。Float 类型忽略。`emitSummaryOps` 把 `Guard NE 0` 操作以 `Operation.SolverZ3Predicate` 形式贡献到 summary；同时 `ctx.guardStore().add(rhs.name, "x != 0")` 给 boundary framework 用。
- **DFA 论据**：同 `ArrayIndexChecker`——边界 + guard 跟踪。R765 报告 boundary 框架的 `DivZeroConstraintChecker` 是 Juliet CWE-369 **唯一** 能找到 findings 的（heuristic 这条 0 findings），所以 R768 deprecated。
- **误报画像**：跨函数 guard 看不见；显式 `if (x != 0) y / x` 形态漏报（guard 在 BRANCH 内不在 BINOP 之前）。

#### 2.4.3 `IntOverflowChecker` (CWE-190) — **R780/R781 directional split**

- **实现机制**：
  1. `TaintedCheckerBase` 走 `plumb.numeric.overflow-hazard` source（MOVE-from-input-source 标 tainted）；
  2. `UNARYOP PRE_INC / POST_INC`（R692 修过 PRE/POST_INC 缺漏）+ `BINOP ADD / MUL` 上 dest 是 byte/short/int/long 时看 lhs1/lhs2 是否有 tainted；
  3. **R780 方向切分**：IntOverflowChecker 只管 ADD 和 MUL + UNARYOP INC（不再管 SUB）；IntUnderflowChecker 管 SUB + UNARYOP DEC；
  4. **R781 方向 bitmask**：若 rhs 是 const（INT/LONG literal），用 `possibleDirections(op, rhs)` 算"哪方向数学上可能"；ADD rhs>0 → 仅 overflow（bit 1）；ADD rhs<0 → 仅 underflow（bit 2）；SUB rhs>0 → 仅 underflow；SUB rhs<0 → 仅 overflow；MUL |rhs|>=2 → both。`IntOverflowChecker` 只 fire 当 bit 1 置位；`IntUnderflowChecker` 只 fire 当 bit 2 置位。
  5. **R780b UNKNOWN type fallback**：dest.type() 是 UNKNOWN 时回退 lhs1/lhs2 的 type。
- **DFA 论据**：是 taint + direction 联合判定。direction bitmask 是**单手能算的数论**——纯 AST 也能做（看 constant literal），但 DFA 在 `BINOP` 抽象里直接拿到 op + operands，更直接。R780 注释：之前两个 checker 都在 SUB 上 fire 然后 CWE-191 tag 与 rule_id mismatch 全部被 eval 跳过——这是 DFA-side 修复 eval pipeline 错位的典型案例。
- **误报画像**：UNARYOP DEC 由 IntUnderflowChecker 独占（注释里专门有"R780 拆分说明"）；char 视为有符号（与 Java 不一致的小 FP drift）。

#### 2.4.4 `IntUnderflowChecker` (CWE-191) — **R780 独立**

- **实现机制**：与 `IntOverflowChecker` 镜像。管 SUB + UNARYOP PRE_DEC/POST_DEC；direction bitmask 只看 bit 2。`isRealHazard` 排除 `param:` 标签（参数不被视为 hazard，避免 spurious fire）。
- **DFA 论据**：与上面同。
- **误报画像**：同 overflow。

#### 2.4.5 `UncheckedLoopConditionChecker` (CWE-606)

- **实现机制**：
  1. `BINOP LT / LE / GT / GE` 的 lhs1 是 TacVar → 加 `validatedVars`（保守"任何 upper-bound 比较算作 guard"）；
  2. `finishFunction` 走 `TacCfgBuilder.build(fn)`，找 BRANCH 的 trueLabel 以 `L_FOR_BODY` / `L_WHILE_BODY` / `L_DO_WHILE_BODY` 开头（CFG label 约定），**前一指令**是 BINOP 且 operand 中有 tainted var 且 var 不在 `validatedVars` → fire CWE-606。
- **DFA 论据**：要看**CFG**（控制流图）而不仅是线性 IR——BRANCH 的 trueLabel 指向哪个 basic block 是结构化循环的特征。AST 也要重构 try-while 结构，但 CFG 已经把循环拆成"header / body"两 basic block，DFA 直接读 label。
- **误报画像**：R-759 注释里说"containsKey(name()) bug was silent dead"——R-759 修了 SSA-版本变量名匹配；同样 non-static field 循环变量漏报。

#### 2.4.6 `UncontrolledRecursionChecker` (CWE-674) — **DFA 形式，但本质是 call-site pattern**

- 与顶层 `RecursionChecker` 同算法（自递归 name + arity 匹配），但 `taint/` 这条**额外继承 TaintedCheckerBase**。基类的 taint 传播对它没影响——它真正用到的只是 `DfaChecker` 框架本身。
- **DFA 论据**：把同一规则注册两次（顶层 DFA + taint 类）保证"tier 1 always on / tier 2 only when taint enabled"。

#### 2.4.7 `NullDerefChecker` (CWE-476)

- **实现机制**：
  1. 自维护 `nullTaintedVars`（**不**复用基类的 `taintedVars`——避免"每个 param 都算 tainted"导致满屏 FP）；
  2. MOVE-from-Const-NULL → dest 加 `nullTaintedVars`；
  3. CALL dest 的 method name 短名含 `Bad` / `Null` / `null` / `Nullable`（heuristic 模拟 Juliet 的 `bad()` / `CWE690_bad()`） → dest 加 `nullTaintedVars["may-return-null:sig"]`；
  4. MOVE 时 source 在 nullTaintedVars → dest 跟 source；source 不在 → dest 移除（"已 reassign 为非 null"，注释里专门写了**修过的 bug**：CWE-378/459 上百 FP）；
  5. FIELDLOAD/STORE/ARRAYLOAD/ARRAYSTORE/CALL 的 receiver 在 nullTaintedVars → fire，CWE-690（method call）或 CWE-476（field / array）。
- **DFA 论据**：用自维护的 nullness 域、**不**用基类 taint（避免 param 误标）——这是"taint 框架 + checker 自己的 null 域"的混合体，AST 看不到这种"linear nullness"。
- **误报画像**：MOVE-from-non-null-var 正确清空（注释专门写）；跨函数 null 看不见；method 名字 heuristic 误报（任何 `isNullEmpty` 类方法都命中）。

#### 2.4.8 `PathTraversalChecker` (CWE-22)

- **实现机制**：与基类合作；额外在 `NEW` 上：扫 args，任一 TacVar 在 `taintedVars` → dest 加 taint（模拟 `new File(taintedPath)` 形态）；然后基类 sink-family 检查自然命中 `new File(path)` 这种 sink。
- **DFA 论据**：要"NEW 构造的 dest 也是 tainted if 它的参数是 tainted"——这是"NEW-as-taint-source"的扩展。AST 也写 visitor 但要做两层 NEW 配对。
- **误报画像**：与基类 taint 共享 InputSourcePatterns 限制。

#### 2.4.9 `NumericTruncationChecker` (CWE-197)

- **实现机制**：
  1. BINOP 是 `GE / LE / GT / LT / EQ / NE` 的 lhs1 加 `validatedVars`（"做过比较就算被校验"）；
  2. UNARYOP `CAST` 命中且 `castType` 比 `src.type()` 窄（byte 8 / short 16 / char 16 / int 32 / long 64 / float 32 / double 64）且 src 在 taintedVars 且 src 不在 validatedVars → fire。
- **DFA 论据**：判定条件是"窄化 cast 之前没做过范围检查"——IR 直接拿到 UNARYOP + castType + src.type()。AST 拿 cast 也是字面量但要算 type 关系；DFA 一处解决。
- **误报画像**：range check 用 `>=` 之后 cast 也算 guard。

#### 2.4.10 `UncontrolledMemAllocChecker` (CWE-789) — 已在 §2.2.6。

#### 2.4.11 `ResourceExhaustionChecker` (CWE-400) — 已在 §1.1.2（在顶层但继承 TaintedCheckerBase）。

#### 2.4.12 `AssignmentInConditionalChecker` (CWE-481)

- **实现机制**：
  1. MOVE 的 dest 是 TacVar 且 lhs1 不是 Const → 加 `lastAssigned`；
  2. BRANCH 的 lhs1 是 TacVar 且在 `lastAssigned` → fire CWE-481（"did you mean ==?"）；
  3. 走 `ctx.factBag().factsOfVar(name)` 查 `plumb.call.return-value-folded` fact kind；折叠常量值非空 → fire "branch is dead/redundant"。
- **DFA 论据**：判定是"BRANCH 用了一个 MOVE dest"——AST 同样能 visitor 拿到，但用**基类的 fact bag**查 cross-fn fold 后的常量值是 DFA 独有的——R-31 跨函数常量折叠（`assertTrue` 之类）会被 pre-calculated 后存 fact。
- **误报画像**：跨函数 fold 仅适用于实现折叠 helper 的代码。

#### 2.4.13 `LiteralBooleanConditionChecker` (CWE-547)

- **实现机制**：BRANCH 的 lhs1 是 Const-BOOL 或 Const-INT(0/1) → fire "branch condition is a literal constant — likely a typo"；lhs1 是 TacVar 但 `factBag` 有 `plumb.call.return-value-folded` 折叠常量值 → fire "branch is dead/redundant after cross-function fold"。
- **DFA 论据**：与上面同——fact bag 跨函数 fold 后的常量。
- **误报画像**：用 `if (true)` 写 placeholder 是 TP（"likely a typo"）；用 `if (DEBUG)` 走 `if (DEBUG && ...)` 形态常被误标。

#### 2.4.14 `ResourceLeakChecker` (CWE-404) — **继承 `PathSensitiveCheckerBase`**

- **实现机制**：基类跟踪 alloc（NEW / NEWARRAY / 已知 ctor 的 CALL：`FileInputStream / FileOutputStream / FileReader / FileWriter / BufferedReader / BufferedWriter / Scanner / Socket / ServerSocket / Connection / Statement / PreparedStatement / ResultSet / InputStream / OutputStream`）与 close（任何 `*.close` / `*.dispose` / `*.finalize`，基类默认 `isCloseCall`）。`summarizeFunction` 时对每个 alloc 没在 closeSites 也没在 passedOut（被 return / throw 传出去）就 emit CWE-404 leak finding。
- **DFA 论据**：判定是"alloc → close 配对"——必须看**同函数**内的 alloc 与 close 配对。基类已写好 allocSites / closeSites 状态机，**子类只剩"哪些 ctor 算 alloc"** + "如何 emit"——典型的 framework + 2-line 子类。
- **误报画像**：callee 拿资源 caller 关闭（典型 servlet close 模式）漏报——基类的 `passedOut` 标记在 TAC RETURN 时记录。

### 2.5 PII / 字段 unused 风格（用 Engine 提供的 `FieldDefUseTracker` / function summary）

#### 2.5.1 `SensitiveDataLeakChecker` (CWE-200)

- **实现机制**：CALL 命中 `SENSITIVE_SOURCE_PATTERNS`（`.getPassword / .getSecret / .getToken / .getApiKey / .getSessionId / .getConnectionString / .getJdbcUrl / .getConfig / .getApiSecret / .get*` / `System.getenv` / `System.getProperty`）→ dest 加 `taintedVars["sensitive:sig"]`。sink 是 `sensitive.exfil.sinks`。
- **DFA 论据**：源是"sensitive"（不是 untrusted），sink 是"exfil"——分类不同语义。**TaintDomain 用 `MixedTaint`**（不区分 String / Numeric / Pointer——sensitive 可以是任何类型）。
- **误报画像**：log 自身 sensitive 值（"logged out the user successfully: apiKey=..."）也 fire。

#### 2.5.2 `SensitivePIILeakChecker` (CWE-359)

- 与上面同源结构；源是 `PII_SOURCE_PATTERNS`（`.getName / .getFirstName / .getLastName / .getFullName / .getEmail / .getPhone / .getMobile / .getAddress / .getSsn / .getSin / .getDob / .getBirthday / .getDateOfBirth / .getPassport / .getDriverLicense / .getCreditCard / .getNationalId`），sink 是 `pii.exfil.sinks`。

#### 2.5.3 `UnusedFieldChecker` (CWE-563)

- **实现机制**：`checkInstruction` 把 FIELDSTORE / FIELDLOAD 都喂给 `ctx.fieldDefUseTracker()`（engine 自带组件）；`summarizeFunction` 时 `t.writtenKeys() \ t.isRead(...)` → emit "private field assigned but never read"。
- **DFA 论据**：与 `UninitNonnullDfaChecker` 一样，靠 Engine 的 `FieldDefUseTracker`——全程序级别的 field 写入 / 读取统计。**DFA 的 engine 负责跨函数聚合**。
- **误报画像**：JUnit @Autowired field 是 write-only（spring 注入）但被读不到，会误报。

#### 2.5.4 `UnusedParameterChecker` (CWE-563)

- **实现机制**：per-function 收集 `lhs1 / lhs2 / args` 中的 TacVar；`summarizeFunction` 看 `fn.params() \ usedParams` → emit。
- **DFA 论据**：基本 def-use 跟踪，DFA 一遍 ok。
- **误报画像**：override 父类方法时父类用过的 param 实际用上（代码不可见）漏报；interface default method 也漏报。

#### 2.5.5 `UnusedVariableChecker` (CWE-563)

- **实现机制**：per-function 收集 dest（首次定义）和 use（lhs1/lhs2/args 中的 TacVar）；`summarizeFunction` 看 `fn.locals() \ used` 且不是 temp → emit。
- **DFA 论据**：同上。
- **误报画像**：javadoc 显式引用 `@param`（注释不算 use）——会误报"unused parameter"。

#### 2.5.6 `UnusedAssignmentChecker` (CWE-563)

- **实现机制**：per-function 跟踪 firstWrite / read / emitted；MOVE 是新 dest 且没读过 → firstWrite；MOVE 已经有 firstWrite 没读过 → emit 旧 firstWrite；后续该 dest 被 read → 从 firstWrite 移除。
- **DFA 论据**：判定"赋值未用"——def-use 跨语句。
- **误报画像**：异常路径上"赋给 errorCode 然后 throw"会被读但已飞 FP 较少。

#### 2.5.7 `UseAfterFreeChecker` (CWE-416)

- **实现机制**：
  1. `initFunction` 跑 `PointsToAnalysis.analyse(fn)`——engine 自带的 points-to 静态分析；
  2. CALL 是 close（`*.close` / `*.dispose` / `*.finalize`）→ `pointsTo.markFreed(args[0])`；
  3. FIELDLOAD / FIELDSTORE / ARRAYLOAD / ARRAYSTORE / CALL 的 lhs1 是 TacVar 且 `pointsTo.isUseAfterFree(receiver)` → emit CWE-416。
- **DFA 论据**：要"pointer-equality 的 close + 后续 use"——单纯 `aliasClasses` 不够（不同 var 可能指向同一 alloc），**用 points-to 模型**。这是 DFA 中**最重**的 checker——R83 修复的 108 FP 来自 SSA-version key 错；之后仍漏 12 家族（points-to 不全） + 63/64 家族（跨 TU）。
- **误报画像**：跨 TU 漏报严重（找不到 caller-free 形参传播）；points-to 误指会 FP。
- **R83 doc**：`plumb-dfa/docs/R83-CWE416-FP-FIX-2026-07-21.md`（8307 bytes）——这是 plumb-dfa 里**最值得看的工程文档之一**。

### 2.6 taint/ 子包非-checker 文件

- `summary.txt`（0 字节）—— 空文件，可忽略。
- **没有任何 helper 类**——`TaintedCheckerBase` / `PathSensitiveCheckerBase` / `TaintDomain` / `TaintRule` / `InputSourcePatterns` / `GuardTracker` / `Operation` / `FunctionSummary` 都在 `org.plumb.dfa.taint` 和 `org.plumb.dfa.summary` 顶层包。

---

## 3. 顶层非-checker 辅助类（3 个）

### 3.1 `EntropyDetector`

- **不**是 DfaChecker；是 `HardCodedPasswordSinkChecker` / `HardCodedKeyChecker` 共用的 Shannon entropy + character-class classifier（Confidence: LOW / MEDIUM / HIGH）。`isLikelyPlaceholder` 抑制 `password` / `test` / `admin123` 一类 placeholder。
- 备忘：**plumb 风格 + dfa 联合**——`EntropyDetector` 是 dfa 端的，plumb-style 端是 AST 上对相同字面量做相同判定。

### 3.2 `LiteralSourcedTracker`

- **不**是 DfaChecker；`HardCodedPasswordSinkChecker` 与 `HardCodedKeyChecker` 共用的"literal-sourced 跟踪器"：MOVE-from-string-Const 把 dest 标 literal-sourced；CALL `toCharArray` / `getBytes` 把 dest 标 derived-of-literal；sink 检查时 `resolveLiteral(operand, minLength)` 走 TacConst / literal-sourced / derived-of-literal 三路。
- DFA-性质的本质：跨语句字面量传播。

### 3.3 `SourceReader`

- **不**是 DfaChecker；source-reading 规则的公用读源文件 utility。`readLines` / `readAll` 按 `prog.sourcePath()` 读，**全 process 共享 `ConcurrentHashMap<Path, List<String>>` 缓存**（13k 文件 ~1.5MB）。`readAllFiles(ctx)` 反射拿 MergedProgram 的 cross-TU source path list（注释里说"为了不扩 public API"）。
- DFA-性质的本质：TAC IR 不带注释 / 注解值 / 字段修饰 / 字符串字面量内容——这些"AST-level data"通过 SourceReader 拉回来。

---

## 4. 顶层（不在 `taint/`）的 Stub（15 个）

这些是 R-787a–i 阶段**已迁到 plumb-style 模块**的源文本正则规则。当前 dfa 顶层类构造时抛 `UnsupportedOperationException`，ScanRunner 不再注册它们。**审计时不需要再读它们的实现**——只在解释 R-787 系列"AST vs DFA = input type, not state size"原则时用：

| 顶层 stub | CWE | 旧 dfa 实现 | 当前归宿 |
|----------|-----|-------------|----------|
| `ClassBasedIssueChecker` | 491 | source regex "protected Object clone() not final" | `plumb-style.style.ClassBasedIssueCheck`（AstChecker） |
| `ClassThreadChecker`（含 4 子） | 572/579/585/609 | source regex | `plumb-style.style.{CallToThreadRun, NonSerializableInSession, EmptySyncBlock, DoubleCheckedLocking}Check`（AstChecker） |
| `CommentInfoLeakChecker` | 615 | source regex "comment with sensitive data" | `plumb-style.style.CommentInfoLeakCheck` |
| `EnvVarInfoExposureChecker` | 526 | source regex "System.getenv → writer" | `plumb-style.style.EnvVarInfoExposureCheck` |
| `InfoExposureQueryStringChecker` | 598 | source regex "method=get + sensitive name" | `plumb-style.style.InfoExposureQueryStringCheck` |
| `InsufficientSessionExpirationChecker` | 613 | source regex "setMaxInactiveInterval <= 0" | `plumb-style.style.InsufficientSessionExpirationCheck` |
| `MissingCryptoStepChecker` | 325 | source regex "Cipher.* 缺 Mac.*" | `plumb-style.style.MissingCryptoStepCheck` |
| `MissingPasswordMaskingChecker` | 549 | source regex "input type=text name=password" | `plumb-style.style.MissingPasswordMaskingCheck` |
| `NullDerefFromReturnChecker` | 690 | regex "method return + deref" | `plumb-style.style.NullDerefFromReturnCheck` |
| `ObjectModelChecker` | 580/581 | regex "clone() without super.clone" | `plumb-style.style.ObjectModelCheck` |
| `PoorCodeQualityChecker` | 398 | regex "empty block / empty method" | `plumb-style.style.PoorCodeQualityCheck` |
| `RandomIvChecker` | 329 | regex "IvParameterSpec + new byte[N]" | `plumb-style.style.RandomIvCheck` |
| `ReachableAssertionChecker` | 617 | regex "assert false / assert 0" | `plumb-style.style.ReachableAssertionCheck` |
| `SensitiveInfoUnclearedChecker` | 226 | regex "StringBuilder sensitive-name 缺 setLength" | `plumb-style.style.UnclearedSensitiveInfoCheck` |
| `UncaughtExceptionChecker` | 600 | regex "risky-call 缺 try-catch" | `plumb-style.style.UncaughtExceptionCheck` |
| `UnprotectedCredTransportChecker` | 523 | regex "http:// + login field" | `plumb-style.style.UnprotectedCredTransportCheck` |
| `UnsaltedOneWayHashChecker` | 759 | regex "MessageDigest.* 缺 update" | `plumb-style.style.UnsaltedOneWayHashCheck` |

> R-787 的核心论据：上述 17 个规则的旧 dfa 实现都基于源文本 regex；TAC IR 不带注释 / 字段修饰 / 注解值 / 字符串字面量内容——**dfa 路径只能"借道" SourceReader 读源**，本质上和 AST 一样需要"读源文件 → 正则匹配"，**DFA 形式名存实亡**。R-787 决定把这些迁到 plumb-style 模块的 AstChecker 实现（用 JavaParser 的 CompilationUnit.walk + symbol-solver 做类型解析），保留 dfa 顶层 stub 仅为 FQCN 兼容（构造时抛 UOE 让老 yaml / test 立刻 fail loud 而不是 0 finding）。

### 4.1 顶层废弃占位（非 stub，是真 checker 但被替）

- `UncheckedLoopConditionChecker`（顶层）—— R784 阶段被 `taint/UncheckedLoopConditionChecker` 替代。顶层版本保留为 deprecated 兼容层（构造时抛 UOE）。**实际只跑 `taint/UncheckedLoopConditionChecker`**。

---

## 5. DFA 选型决策矩阵（每条规则按"为什么是 DFA"归类）

> 读这一节是把"DFA 实现"这个笼统标签拆成 6 种具体动机。审计结论应当能根据规则匹配到这 6 类之一。

### 5.1 跨语句 def-use 必须

- **特征**：规则需要把"一个 var 在一条指令被赋值、在另一条指令被使用"关联起来。
- **代表**：`OffByOneChecker`（array index 与 loop bound 同源）、`BadShiftChecker`（shift count 一跳常量折叠）、`HardCodedPasswordSinkChecker` / `HardCodedKeyChecker`（literal 经过 toCharArray/getBytes 还能找到源）、`EqualsCalledOnNullChecker`（def 之后 use 是否还 null）、`InsecureCryptoChecker`（algo 字符串经 const-var 传递）、`UncheckedReturnChecker`（dest 是否在后续 use）、`CollectionSelfReferenceChecker` / `CompareWithSelfChecker`（alias 类）、`UncaughtExceptionPropagator`（def 是不是 throw 是不是 caught）。
- **AST 替代成本**：要看 2-3 跳 visitor 重建 def-use；IR 直接拿 SSA 链。

### 5.2 跨函数 summary 必须

- **特征**：判定需要看 callee 做了什么（不是只看 caller）。
- **代表**：`UninitNonnullDfaChecker`（callee 写 field，caller ctor 继承）、`OrmLostUpdateDfaChecker`（callee 是 persist 包装）、`DeadlockChecker`（跨函数 lock 序列配对）、`ServletAtomicityDfaChecker`（跨方法 attribute 访问）、`SingletonRaceDfaChecker`（跨方法 field 访问）、`UseAfterFreeChecker`（跨函数 points-to + free）、`UncaughtExceptionPropagator`（callee throw 标记 → caller 状态）、`taint/*` 全部（callee summary 贡献 source / sink op，caller 端用）。
- **AST 替代成本**：必须维护 call graph + summary 等价物；IR + summary 是 engine 现成 SPI。

### 5.3 多事件状态机必须

- **特征**：同 receiver 经历多个事件（new + setX + addX）才 fire。
- **代表**：`XxeChecker`（factory 构造 → setFeature → parse）、`InsecureCookieChecker`（new + setSecure + addCookie）、`MissingCryptoInitChecker`（getInstance + init + generate）、`OffByOneChecker`（lengthOfVar + fieldLengths 两表）。

### 5.4 TAC IR 才能拿到的属性

- **特征**：判定依赖 SSA 版本、CFG label、字段 type 这些 IR-only 信息。
- **代表**：`OffByOneChecker`（CFG label 区分 for/while/do-while）、`UncheckedLoopConditionChecker`（CFG trueLabel 判定 body block）、`UncaughtExceptionPropagator`（hasThrow / hasCatch 标志）、`TaintedCheckerBase` 子类（InputSourcePatterns 调用产生的 dest 跟踪）、`InsecureCryptoChecker`（tac IR 的 Const string 形态）。

### 5.5 必须注解通道

- **特征**：规则判定需要 class / method / param 三类注解联动。
- **代表**：`MissingAuthorizationChecker`、`InformationExposureChecker`、`MissingInputValidationChecker`、`UninitNonnullDfaChecker`（@NonNull field 集）、`FieldHardcodedPasswordChecker`（@Value / @Autowired 注入字段识别）。
- **AST 替代成本**：JavaParser 也能拿注解，但要走 3 遍 visitor 重建；IR 在 `TacFunction` 一次性提供。

### 5.6 根本**不是** DFA（应被识别为伪 DFA）

- **SRC-正则**：`ClassSourceChecker`（4 子）、`SuspiciousCommentChecker`、`CleartextSocketUsageChecker` 的 source-reading 部分。
- **AI-启发**：`CleartextSocketUsageChecker`、`ErrorMessageInfoLeakChecker`、`SuspiciousCommentChecker` 的 AI prompt 收尾部分。
- **STUB**：15 个 R-787 占位。

> 审计时这三类应当**显式标出**——它们占用 `checkers/` 包但本质不是 DFA；混淆会误导后续维护。

---

## 6. 整体结构性结论

### 6.1 91 个真 checker 的类别分布

| 类别 | 数量 | 占比 |
|------|------|------|
| DFA-真（IR + def-use / summary / 状态机） | 26 | 28.6% |
| DFA-taint（继承 TaintedCheckerBase / PathSensitiveCheckerBase） | 44 | 48.4% |
| DFA-注解通道 | 6 | 6.6% |
| 强 DFA 自定义（继承 tainted 但写自己的 checkInstruction） | 13 | 14.3% |
| SRC-正则（在 dfa 包下但实质 source-reading） | 3 (ClassSourceChecker + SuspiciousCommentChecker + CleartextSocketUsageChecker 的源文本部分) | 3.3% |
| 严格说不是 DFA（AI-启发主导 / deprecated 占位） | 15 stub + 1 顶层 deprecated 兼容层 | 16.5% |

> 注：6.6% + 14.3% + 28.6% + 48.4% 是真正的"DFA 类"（91 个 = 44 + 26 + 6 + 13 + 3 - 1 overlap）。**Audit 员应当用上面分类去给每条规则打"DFA 性质"标签。**

### 6.2 为什么 taint 类这么多（44/91 = 48%）

- **Taint 是 DFA 的"高 ROI 子集"**：CWE-89/78/22/601/113/134/256/319/94/15/90/601/829/470/295/295/501/601/601/226 等十几条规则的本质都是"untrusted source → dangerous sink"。
- **Dwarf 抽象**：写一条 taint 规则平均 3-30 行，DFA-style 写同样规则要 100-300 行（参考 `taint/DBCredentialSinkChecker` 与 `HardCodedPasswordSinkChecker` 的类级 javadoc 自承"30 行 vs 300 行"）。
- **跨函数透明**：基类 `TaintedCheckerBase` 自带 `MOVE / BINOP / CALL` 传播 + FunctionSummary fold。
- **AI prompt 作为规则收尾**：AI dedup 解决了"静态分析放 / 紧"的精度取舍——Dwarf 静态能做的"必要条件"+"AI 判断充分条件"是 2026 阶段 SAST 主流形态。

### 6.3 为什么 DFA 总体上**只**用于这几类

- **能用 AST 做的就迁 AST**（R-787 系列 17 条）；AST 直接看 CompilationUnit 不需要 IR lift 步骤。
- **能用 taint 做的就继承 TaintedCheckerBase**（CWE-89/78/22/601/113/134 等十几条）；基类搞定传播，子类只覆写 sinkArgumentIndex / 自定义 checkInstruction。
- **需要 IR-独有信息（CFG label / SSA version / FieldDefUseTracker / points-to）才自写 DFA**（§1.10 / §1.12 / §1.13 / §2.5）。
- **AI 主导的查"语义可疑"就直接用 AI 启发**（CWE-319 / CWE-209 / CWE-546）——静态只产出候选，AI 收尾。

### 6.4 跨函数 DFA 的"边界"（审计中常见误判）

- **能跨函数**：FunctionSummary 贡献的 op（PersistsParam / WriteToField / TaintPropagate / Sink / PassThrough）+ Engine 全局 map（`hasThrow` / `hasCatch` / `fieldReaders` / `fieldWriters` / `getSites` / `setSites`）+ 收集器（`points-to` / `FieldDefUseTracker`）。
- **不能跨函数**：alias 通过 FIELDLOAD（`this.field` ↔ `that.field`）、alias 通过集合（`a.addAll(b); b == a` 是 self-ref 但 DFA 看不见 b == a）、method-locality 的 lock / unlock 配对（§1.12.2 的"same-class static analysis only"）、跨 TU 的 caller-free 形参传播（UseAfterFreeChecker R83 留的 FN）。

### 6.5 错误画像汇总（按 DFA 论据类型）

- **跨函数盲区**：UseAfterFreeChecker (CWE-416, R83 留 47 FN) / ServletAtomicityDfaChecker / SingletonRaceDfaChecker / UncaughtExceptionPropagator。
- **PATH 敏感盲区**：`OffByOneChecker` 条件 lock 漏报 / `UnlockNotLockedChecker` 条件 lock over-fire。
- **类型解析盲区**：`BadShiftChecker` 不做 range / `CompareWithSelfChecker` 收不到 Object.equals 自比合法 API / `MissingAuthorizationChecker` 不解析 `extends HttpServlet`。
- **CFG 盲区**：`MissingCryptoStepChecker` 旧实现只找同文件 Mac.*，无结构化判断——已迁 AST 解决。
- **常量折叠盲区**：`HardCodedPasswordSinkChecker` 对 `Runtime.exec(tainted)` 形态因 `tainted` 不是字面量而不 fire（taint 才覆盖）。
- **AI 收尾负担**：`CleartextSocketUsageChecker` / `ErrorMessageInfoLeakChecker` / `SuspiciousCommentChecker` 大量候选靠 AI 过滤；纯静态看是 over-fire。

### 6.6 跟 plumb-style 模块的边界（cardinal principle）

- **plumb-style** = AST checker = JavaParser 走 CompilationUnit + symbol-solver
- **plumb-dfa** = DFA checker = TAC IR + FunctionSummary
- 边界判定 = **输入是源文本 vs IR**，**不是"分析状态大小"**。
- R-787 系列（17 条 stub）严格按这个原则执行：把"读源 + 正则"的 checker 全部迁到 plumb-style，plumb-dfa 顶层仅留兼容 stub。

---

## 7. 附：每条规则一张"机制 + DFA 必要性 + 误报画像"摘要表

| 规则 | CWE | 实现机制 | DFA 必要性 | 主要误报来源 |
|------|-----|----------|-------------|--------------|
| `BadShiftChecker` | 758 | 一跳常量折叠 + type 位宽查表 | 跨 MOVE 折叠 | 运行时 count miss |
| `ClassSourceChecker` (4 子) | 382/526/570/571 | **SRC-正则** | 不是 DFA | System.exit(0) in main |
| `CleartextSocketUsageChecker` | 319 | **AI-启发 + SRC-正则** | 不是 DFA | health-check ping / chat |
| `CollectionSelfReferenceChecker` | 754 | MOVE 别名类 + CALL self-ref | 跨语句 alias | Object.equals / Comparable 自比 |
| `CompareClassesByNameChecker` | 486 | CALL 模式 | 无数据流 | 仅 log 也 fire |
| `CompareWithSelfChecker` | 570/571 | MOVE 别名类 + BINOP EQ/NE | 跨语句 alias | Object.equals 自比 |
| `CorsMisconfigurationChecker` | 942 | 一跳 list-factory 折叠 | 跨语句 const | 链式 getter 不在 |
| `DeadlockChecker` | 833 | 全函数 lock 序列 + O(n²) 比对 | 跨函数 | 3+ lock 漏；同名 lock 跨类漏 |
| `ErrorMessageInfoLeakChecker` | 209 | **AI-启发** + call-site 模式 + Throwable type 解析 | 类型解析（IR type()） | System.out.println(user) |
| `FieldHardcodedPasswordChecker` | 798 | FIELDSTORE + 字面量 + DI 注解 | FIELDSTORE 配 DI 注解 | Spring 字段 |
| `HardCodedKeyChecker` | 321 | 共享 `LiteralSourcedTracker` + NEW SecretKeySpec.<init> | 跨语句字面量 | JCA test vector |
| `HardCodedPasswordSinkChecker` | 259 | 共享 `LiteralSourcedTracker` + 3 sink | 跨语句字面量 + 多 sink | JCA placeholder |
| `InsecureCommunicationChecker` | 319 | NEW URL/URI + Const STRING + 协议黑名单 | 字面量 + 黑名单 | localhost dev URL |
| `InsecureCookieChecker` | 614 | TYPE 粒度状态机（new + setSecure + addCookie） | 多事件状态机 | 同函数多 Cookie |
| `InsecureCryptoChecker` | 327/328/780 | MOVE-from-Const + WEAK map + getInstance 扫描 | 跨语句 const-var | JCA 兼容代码 |
| `InsecureTempFilePermsChecker` | 378 | createTempFile + setWritable set 配对 | 事件配对 | Files.createTempFile 漏 |
| `InformationExposureChecker` | 200 | 注解通道 + 泛型 type arg 递归 | class/method/return type 联动 | DTO + @JsonIgnore |
| `IteratorConcurrentModificationChecker` | 820 | iterator mutation 配对 | 跨语句 | 跨函数 iterator 漏 |
| `MissingAuthorizationChecker` | 862 | 注解通道（class + method） | 多注解联动 | Filter / SecurityConfig 兜底 |
| `MissingCryptoInitChecker` | 325 | TYPE 粒度状态机 | 多事件状态机 | 同函数多 KeyGenerator |
| `MissingInputValidationChecker` | 20 | 注解通道（class+method+param） | 多注解联动 | 未知 validation 注解 |
| `OffByOneChecker` | 193 | SSA 表 + 字段名表 + CFG label | SSA+CFG+field alias | 条件 lock 漏 |
| `OrmLoadNullCheckDfaChecker` | 252 | load 跟踪 + 跨 MOVE alias + BRANCH null 配对 | 跨语句 | JPA 自定义 dialect 返 null |
| `OrmLostUpdateDfaChecker` | 459 | 三层 taint（NEW / persist / summary.PersistsParam） | 跨函数 summary | pure name 启发 |
| `OrmUnnecessaryGetDfaChecker` | 252 | get 跟踪 + alias + FIELDLOAD 检查 | 跨语句 | Map key use 漏 |
| `PredictableKeyPasswordChecker` | 798/259 | NEW PBEKeySpec 等 + Const 字面量 | 直接字面量 | test fixture |
| `PredictableRandomSeedChecker` | 332/336 | NEW Random + Const + setSeed + Const | 直接字面量 | test seed |
| `ProcessControlChecker` | 114 | CALL 模式 + 字面量豁免 | call-site 模式 | 部署锁定 |
| `RecursionChecker` | 674 | 自递归 name+arity | 跨方法 call graph | 无 base case 检查 |
| `ResourceExhaustionChecker` | 400 | **DFA-taint** + 高值 CONST 种子 + 两条直发 sink | taint + CONST 启发 | 跨函数 guard 漏 |
| `ServletAtomicityDfaChecker` | 362 | 跨方法 getAttribute/setAttribute 集合 | 跨函数 | 同步在 helper 漏 |
| `SingletonRaceDfaChecker` | 366 | 跨方法 FIELDLOAD/FIELDSTORE 集合 | 跨函数 | extends HttpServlet 漏 |
| `StaticApiKeyChecker` | 798 | `<clinit>` 局域 + MOVE-from-Const + 名字 regex | `<clinit>` 局域 | JCA 算法名 |
| `SuspiciousCommentChecker` | 546 | **SRC-正则 + AI-启发** | 不是 DFA | "VULN-1234" 编号 |
| `TryCatchFinallyChecker` | 248/396/397 | NEW / CATCH_HANDLER / THROW 累计 + finish 配对 | 跨事件配对 | 跨函数 catch 漏（UncaughtExceptionPropagator 补） |
| `UncaughtExceptionPropagator` | 248 | 全函数 hasThrow/hasCatch + finish 重扫 | 跨函数 | 类型不匹配 |
| `UncheckedReturnChecker` | 252 | CALL FAILABLE + dest 后续 use 配对 | 跨语句 def-use | `File.delete` 真忽略 |
| `UninitNonnullDfaChecker` | 457 | FIELDSTORE + 跨函数 WriteToField summary | 跨函数 | alias 漏 |
| `UnlockNotLockedChecker` | 832 | 线性前看 lock 配对 | 线性前看 | 条件 lock over-fire |
| `WeakPrngChecker` | 338/336 | 4 个 CALL 模式分支 | call-site | 非安全场景 |
| `XxeChecker` | 611 | 三段状态机（factory / secured / parser） | 多事件状态机 | class-level 配置漏 |
| 15 个 R-787 stub | 多 | source regex → 已迁 plumb-style | 不是 DFA | n/a |
| `taint/SqlInjectionChecker` | 89 | TaintedCheckerBase | taint 框架 | 文档 / vault 漏报 |
| `taint/CommandInjectionChecker` | 78 | TaintedCheckerBase | taint 框架 | ProcessBuilder(List) allow-list |
| `taint/PathTraversalChecker` | 22 | TaintedCheckerBase + NEW-taints | taint + NEW 扩展 | 输入源限制 |
| `taint/OpenRedirectChecker` | 601 | TaintedCheckerBase | taint 框架 | 相对路径 resolver |
| `taint/HttpResponseSplittingChecker` | 113 | TaintedCheckerBase (arg=1) | taint 框架 | CRLF sanitization |
| `taint/FormatStringChecker` | 134 | TaintedCheckerBase | taint 框架 | 格式字符串作为参数 |
| `taint/ExternalConfigSettingSinkChecker` | 15 | TaintedCheckerBase | taint 框架 | controlled config |
| `taint/LdapInjectionChecker` | 90 | TaintedCheckerBase (arg=1) | taint 框架 | RFC 4515 escape |
| `taint/ResourceExhaustionChecker` | 400 | TaintedCheckerBase | taint 框架 | 短整型高值常量 |
| `taint/DBCredentialSinkChecker` | 256 | TaintedCheckerBase (arg=2) | taint 框架 | vault / secret manager |
| `taint/CleartextNetworkCredentialSinkChecker` | 319 | TaintedCheckerBase (arg=2) | taint 框架 | TLS / SSH wrap |
| `taint/CleartextTxChecker` | 319 | TaintedCheckerBase (arg=0) + 额外 source | taint + extra source | PrintWriter → file |
| `taint/UnsafeReflectionChecker` | 470 | TaintedCheckerBase | taint 框架 | allow-list class name |
| `taint/UnsafeJniChecker` | 114 | TaintedCheckerBase | taint 框架 | 系统库名 |
| `taint/InsecureRandomChecker` | 330 | TaintedCheckerBase | taint 框架 | 非安全 PRNG |
| `taint/BadCertVerificationChecker` | 295 | TaintedCheckerBase | taint 框架 | pinned cert |
| `taint/JavaCodeInjectionChecker` | 94 | TaintedCheckerBase | taint 框架 | classloader 隔离 |
| `taint/ElInjectionChecker` | 94 | TaintedCheckerBase | taint 框架 | EL 引擎隔离 |
| `taint/OgnlInjectionChecker` | 94 | TaintedCheckerBase | taint 框架 | sandbox |
| `taint/JcrInjectionChecker` | 94 | TaintedCheckerBase | taint 框架 | JCR ACL |
| `taint/JspDynamicIncludeChecker` | 829 | TaintedCheckerBase | taint 框架 | 路径 allow-list |
| `taint/XPathInjectionChecker` | 643 | TaintedCheckerBase | taint 框架 | XSLT parameter binding |
| `taint/XssInjectionChecker` | 79 | TaintedCheckerBase (arg=1) | taint 框架 | OWASP Encoder / Content-Type |
| `taint/NullDerefChecker` | 476/690 | **自维护 nullTaintedVars**（不混基类 taint） | 跨语句 nullness | heuristic 名字 |
| `taint/ArrayIndexChecker` | 129 | TaintedCheckerBase + GuardTracker (R767 deprecated) | boundary 框架已超 | 跨函数 guard |
| `taint/DivByZeroChecker` | 369 | TaintedCheckerBase + GuardTracker (R768 deprecated) | boundary 框架已超 | 显式 NE guard |
| `taint/IntOverflowChecker` | 190 | TaintedCheckerBase + UNARYOP INC + ADD/MUL + direction bitmask (R780/R781) | taint + direction | UNKNOWN type 漏 |
| `taint/IntUnderflowChecker` | 191 | TaintedCheckerBase + UNARYOP DEC + SUB/ADD/MUL + direction bitmask | taint + direction | 同 overflow |
| `taint/UncheckedLoopConditionChecker` | 606 | TaintedCheckerBase + GuardTracker + CFG label | taint + CFG | R-759 修了 containsKey(name) 死代码 |
| `taint/UncontrolledRecursionChecker` | 674 | TaintedCheckerBase + 自递归 name+arity | taint 框架 (但本质 call-site) | n/a |
| `taint/NumericTruncationChecker` | 197 | TaintedCheckerBase + UNARYOP CAST + 范围检查 | 跨语句 | 范围检查漏 |
| `taint/UncontrolledMemAllocChecker` | 789 | TaintedCheckerBase (arg=0) | taint 框架 | bound check |
| `taint/ResourceLeakChecker` | 404 | **PathSensitiveCheckerBase** | alloc/close 配对 | 跨函数 close 漏 |
| `taint/AssignmentInConditionalChecker` | 481 | TaintedCheckerBase + BRANCH + fact bag | 跨语句 + fact fold | 测试 helper |
| `taint/LiteralBooleanConditionChecker` | 547 | TaintedCheckerBase + BRANCH + fact bag | 跨语句 + fact fold | DEBUG 常量 |
| `taint/SensitiveDataLeakChecker` | 200 | TaintedCheckerBase + sensitive getter pattern | taint 框架 | 自我 log |
| `taint/SensitivePIILeakChecker` | 359 | TaintedCheckerBase + PII getter pattern | taint 框架 | 自我 log |
| `taint/SessionFixationChecker` | 384 | TaintedCheckerBase (arg=1) | taint 框架 | session allow-list |
| `taint/TrustBoundaryViolationChecker` | 501 | TaintedCheckerBase (arg=1) | taint 框架 | sandbox |
| `taint/SqlInjectionChecker` | 89 | TaintedCheckerBase | taint 框架 | 同上 |
| `taint/UnusedAssignmentChecker` | 563 | 自维护 firstWrite/read | 跨语句 | 异常路径赋 errorCode |
| `taint/UnusedFieldChecker` | 563 | FieldDefUseTracker | 跨函数 | Spring 注入字段 |
| `taint/UnusedParameterChecker` | 563 | per-fn 收集 | 跨语句 | override 父类 |
| `taint/UnusedVariableChecker` | 563 | per-fn def/use | 跨语句 | javadoc @param |
| `taint/UseAfterFreeChecker` | 416 | PointsToAnalysis + close mark + deref | 跨函数 points-to | 跨 TU 漏报严重 (R83) |
| `taint/UnsafeJniChecker` | 114 | TaintedCheckerBase | taint 框架 | 系统库名 |
| `taint/UnsafeReflectionChecker` | 470 | TaintedCheckerBase | taint 框架 | allow-list |

---

## 8. 给后续维护者的清单

1. **加新规则前先看 5.1-5.6 节判定走哪条**——避免新规则落入"明明 AST 能做却写 dfa"或"明明不是 dfa 却挂 dfa 名"的反模式。
2. **新规则若继承 `TaintedCheckerBase`**，第一件事是查 `sink-families.yaml` 的 sink family 是否已经存在——直接 super 一个新 TaintRule 是 3 行代码。
3. **新规则若需要自维护 SSA 状态**（典型：nullness / alias），**不**复用基类 `taintedVars`——参考 `NullDerefChecker` 的"separate null map"模式，避免 param 误标污染。
4. **跨函数时一定检查 `FunctionSummary` 操作**（PersistsParam / WriteToField / TaintPropagate / Sink / PassThrough）——单 checker 不写 summary 等于放弃跨函数。
5. **direction / numeric 范围类规则直接走 `boundary.*`**（R767/R768 已 deprecated `ArrayIndexChecker` / `DivByZeroChecker`）——Range lattice + Z3 远胜 taint heuristic。
6. **多事件状态机规则（XxeChecker / InsecureCookieChecker / MissingCryptoInitChecker）**优先按 TYPE 粒度跟踪（受多实例影响），单实例精度要求高的走 per-var map。
7. **AI prompt 是规则收尾但不是规则本身**——把"必要条件"留给静态 / DFA，"充分条件"留给 AI；不要让静态硬判充分性。
8. **R-787 stub 永远不删**——保留 FQCN 兼容；构造时抛 UOE 让配置 fail loud。
9. **审计一份新规则**：在 §7 摘要表里查它属于哪一类，把 DFA 论据填到 §5 的 6 类动机之一。

---

## 9. 引用文档

- `DfaChecker` 接口：`plumb-dfa/src/main/java/org/plumb/dfa/engine/DfaChecker.java`
- `TaintedCheckerBase`：`plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintedCheckerBase.java`
- `PathSensitiveCheckerBase`：`plumb-dfa/src/main/java/org/plumb/dfa/taint/PathSensitiveCheckerBase.java`
- R83 CWE416 FP 修复：`plumb-dfa/docs/R83-CWE416-FP-FIX-2026-07-21.md`（8307 bytes）
- R-787 弃用 stub 的 AST 迁移系列：plumb-style 子目录 `style/`
- R-7~R-668 阶段历史：`plumb-dfa/docs/R*` 命名空间
