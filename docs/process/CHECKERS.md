# Plumb 检查器清单

> **R684 / 2026-08-24** · 626 个活跃检查器 (515 AST + 111 DFA/taint)
> 按 4 个 taxonomy class 分组:A=AST-only / B=DFA-direct / C=Taint-primary / D=AI-primary

---

## 1. 速览

| Class | 数量 | 触发器 | 例子 |
| --- | ---: | --- | --- |
| **A. AST-only** (`AstChecker`) | **515** | 仅 JavaParser | `EmptyCatchBlockCheck`, `CompareStringsWithEqualsCheck` |
| **B. DFA-direct** (`DfaChecker`) | **69** | TAC + CFG (无 taint) | `InsecureCryptoChecker`, `ReachableAssertionChecker` |
| **C. Taint-primary** (`TaintedCheckerBase`) | **42** | TAC + taint engine + summary | `SqlInjectionChecker`, `CommandInjectionChecker` |
| **D. AI-primary** | **0** (内置) | n/a | n/a (AI 是 dedup 层,不是新检查器) |

**R676 状态**:从 635 → 626 (删除 9 个 dfa-unified 重复,备份在 `_trash/r676-dfa-unified-checkers/`)。

---

## 2. A. AST-only (515)

**包路径**:`org.plumb.style.{bug,concurrency,design,error,naming,performance,style}`

子包分布:
- `bug/` - bug 模式 (空指针/equals/compareTo): **18**
- `concurrency/` - 线程/servlet 模式: **2**
- `design/` - 圈复杂度/耦合度/NPath: **19**
- `error/` - 异常/finalize/catch 模式: **35**
- `naming/` - 命名/局部变量/类成员命名: **45**
- `performance/` - 性能 (数组/字符串/box): **40+**
- `style/` - 通用风格 (lambda/method length/switch/...): **350+**

**完整索引**见 `doc/plumb-style-rules-index.md` (老式清单,按子包排序)。

### 2.1 选 A 类中最常用的 (高 ROI)

| Rule ID | 子包 | CWE | 用途 |
| --- | --- | --- | --- |
| `pl-0539-empty-catch-block-strict` | `style/error/` | CWE-390 | 空 catch 块 (严格,连注释都不允许) |
| `pl-0539-empty-catch-block` | `style/error/` | CWE-390 | 空 catch 块 (允许注释) |
| `pl-0578-try-catch-finally-no-return` | `style/error/` | CWE-584 | finally 中 return |
| `pl-0609-equal-method-not-override-hashcode` | `style/naming/` | CWE-581 | equals 覆盖但缺 hashCode |
| `pl-0099-unused-assignment` | `style/style/` | CWE-563 | 未使用赋值 |
| `pl-0409-final-parameters` | `style/style/` | n/a | 参数可声明为 final |
| `pl-604-comments-indentation` | `style/style/` | n/a | 注释缩进 |
| `pl-0166-protected-method-in-final-record` | `style/style/` | CWE-668 | final record 中的 protected 方法 |
| `pl-0837-arraycompareto` | `style/bug/` | CWE-253 | 错误的 compareTo 实现 |
| `pl-0022-equals-avoid-null` | `style/bug/` | CWE-754 | 在 equals 中判断 null |
| `pl-0088-illegal-catch` | `style/error/` | CWE-396 | catch RuntimeException / Error / Throwable |
| `pl-0118-throw-in-finally` | `style/error/` | CWE-584 | finally 中 throw |
| `pl-0496-system-out-println` | `style/style/` | n/a | `System.out.println` 调试残留 |
| `pl-0117-system-exit` | `style/style/` | n/a | `System.exit()` 残留 |
| `pl-0321-method-too-long` | `style/style/` | n/a | 方法超长 (默认 80 行) |

**(完整 515 个 A 类,运行 `java -jar ... --list-rules` 查看)**

---

## 3. B. DFA-direct (69)

**包路径**:`org.plumb.dfa.checkers.*Checker` (顶级,非 taint 子包)

按子领域分组:

### 3.1 Crypto / Randomness (8)

| Checker | CWE | 用途 |
| --- | --- | --- |
| `InsecureCryptoChecker` | CWE-327/328/329/330 | 弱加密 (MD5/SHA1/DES/RC4) — **跟踪 const 变量** (R674 决定保留) |
| `MissingCryptoStepChecker` | CWE-325 | 缺 MAC/IV/Padding |
| `MissingCryptoInitChecker` | CWE-321 | 缺密钥初始化 |
| `HardCodedKeyChecker` | CWE-321/798 | 硬编码密钥 |
| `PredictableKeyPasswordChecker` | CWE-340 | 可预测的密钥/口令 |
| `RandomIvChecker` | CWE-329/330 | 随机 IV 生成错误 |
| `WeakPrngChecker` | CWE-330/338 | 弱 PRNG (java.util.Random 用于 crypto) |
| `UnsaltedOneWayHashChecker` | CWE-759/916 | 哈希未加 salt |

### 3.2 Sensitive Data / Information Exposure (8)

| Checker | CWE | 用途 |
| --- | --- | --- |
| `SensitiveInfoUnclearedChecker` | CWE-244/226 | 敏感数据未清除 |
| `InformationExposureChecker` | CWE-200/209/532 | 异常信息泄露 |
| `EnvVarInfoExposureChecker` | CWE-526/532 | 环境变量泄露 |
| `InfoExposureQueryStringChecker` | CWE-598/200 | 敏感数据 in query string |
| `ErrorMessageInfoLeakChecker` | CWE-209 | 错误消息泄露内部信息 |
| `CommentInfoLeakChecker` | CWE-546 | 注释中敏感信息 |
| `FieldHardcodedPasswordChecker` | CWE-798 | 实例字段硬编码口令 |
| `UnprotectedCredTransportChecker` | CWE-319/523 | 凭证未加密传输 |

### 3.3 Web / Servlet / API (8)

| Checker | CWE | 用途 |
| --- | --- | --- |
| `XxeChecker` | CWE-611/827 | XML 外部实体 |
| `InsecureCommunicationChecker` | CWE-319/320 | 缺 HTTPS |
| `InsecureCookieChecker` | CWE-539/1004 | Cookie 缺 secure/httpOnly |
| `CorsMisconfigurationChecker` | CWE-942/346/345 | CORS 配置错 (通配符 origin) |
| `MissingAuthorizationChecker` | CWE-285/862/863 | 端点缺鉴权 |
| `MissingInputValidationChecker` | CWE-20/345 | 端点缺输入验证 |
| `ServletAtomicityDfaChecker` | CWE-362 | Servlet 状态 race |
| `SingletonRaceDfaChecker` | CWE-543 | Singleton 构造 race |

### 3.4 Concurrency / Threading (5)

| Checker | CWE | 用途 |
| --- | --- | --- |
| `ClassThreadChecker` | CWE-662/821 | 错误的同步粒度 |
| `DeadlockChecker` | CWE-833/412 | 死锁模式 (lock order) |
| `UnlockNotLockedChecker` | CWE-832 | 释放未持有的锁 |
| `ProcessControlChecker` | CWE-114/15 | 进程控制 (加载 native lib) |
| `StaticApiKeyChecker` | CWE-798 | 静态字段 API key |

### 3.5 Defensive / Numeric / Range (12)

| Checker | CWE | 用途 |
| --- | --- | --- |
| `LooseArrayIndexChecker` | CWE-129/193/786 | 数组越界 (loose:静态约束) |
| `LooseIntOverflowChecker` | CWE-190 | 整数溢出 (loose) |
| `LooseIntUnderflowChecker` | CWE-191 | 整数下溢 (loose) |
| `LooseNumericTruncationChecker` | CWE-197/681 | 数值截断 (loose) |
| `LooseDivByZeroChecker` | CWE-369 | 除零 (loose) |
| `LooseFormatStringChecker` | CWE-134 | 格式串攻击 (loose) |
| `LooseHttpResponseSplittingChecker` | CWE-113 | HTTP response splitting (loose) |
| `LooseClassChecker` | CWE-470 | 反射类加载 (loose) |
| `LooseDanglingChecker` | CWE-825 | 悬空引用 (loose) |
| `OffByOneChecker` | CWE-193/682 | off-by-one (loose + DFA) |
| `UncheckedLoopConditionChecker` | CWE-606/835 | 未检查的循环条件 |
| `UncheckedReturnChecker` | CWE-252/754 | 未检查返回值 |

### 3.6 Try / Catch / Exception (4)

| Checker | CWE | 用途 |
| --- | --- | --- |
| `TryCatchFinallyChecker` | CWE-248/390/396/397 | 异常吞没 / finally 异常 |
| `UncaughtExceptionChecker` | CWE-248/703 | 跨函数未捕获异常 |
| `UncaughtExceptionPropagator` (config flag) | CWE-248 | 异常传播器 (cross-TU) |
| `BadShiftChecker` | CWE-758/190 | 移位位数超界 |

### 3.7 Object / Lifecycle / Misc (24)

| Checker | CWE | 用途 |
| --- | --- | --- |
| `EqualsCalledOnNullChecker` | CWE-595/754 | 在 null 上调 equals |
| `CompareWithSelfChecker` | CWE-570/571 | `x == x` / `x.compareTo(x)` |
| `CompareClassesByNameChecker` | CWE-486 | 用字符串比较 Class |
| `CollectionSelfReferenceChecker` | CWE-754/674 | 集合自引用 |
| `IteratorConcurrentModificationChecker` | CWE-820 | 并发修改 |
| `NullDerefFromReturnChecker` | CWE-476 | 从 return 出的 null 解引用 |
| `UninitNonnullDfaChecker` | CWE-665/457 | 未初始化的 @Nonnull 字段 |
| `ObsoleteFunctionChecker` | CWE-477 | 废弃 API |
| `ReachableAssertionChecker` | CWE-617 | 静态可达的 `assert false;` — **R674 决定保留** (与 style `ReachableAssertionCheck` 互补,不可替换) |
| `SuspiciousCommentChecker` | CWE-546 | 注释中的 TODO/FIXME/HACK |
| `ClassSourceChecker` | CWE-94/95 | 动态代码源 (class loader) |
| `ClassBasedIssueChecker` | CWE-470/578 | 基于 class 名的反射 |
| `ObjectModelChecker` | CWE-915 | 错误的对象模型 (clone/copy) |
| `PoorCodeQualityChecker` | n/a | 通用质量 |
| `RecursionChecker` | CWE-674 | 无界递归 |
| `ResourceExhaustionChecker` | CWE-400/770 | 资源耗尽模式 |
| `InsecureTempFilePermsChecker` | CWE-377 | 临时文件权限过宽 |
| `InsufficientSessionExpirationChecker` | CWE-613 | session 不过期 |
| `MissingPasswordMaskingChecker` | CWE-549 | 密码未掩码显示 |
| `OrmLoadNullCheckDfaChecker` | CWE-476 | ORM load null 检查 |
| `OrmLostUpdateDfaChecker` | CWE-362 | ORM lost update |
| `OrmUnnecessaryGetDfaChecker` | CWE-672 | ORM 不必要的 get |
| `CleartextSocketUsageChecker` | CWE-319/201 | 明文 socket |
| `HardCodedPasswordSinkChecker` | CWE-798 | 硬编码口令到 sink |

---

## 4. C. Taint-primary (42)

**包路径**:`org.plumb.dfa.checkers.taint.*Checker`

按 sink 家族分组:

### 4.1 SQL / NoSQL / DB (3)

| Checker | CWE | Sink |
| --- | --- | --- |
| `SqlInjectionChecker` | CWE-89 | `Statement.execute*` / `PreparedStatement.execute*` |
| `DBCredentialSinkChecker` | CWE-798 | DB 连接字符串 / 凭据 |
| `JcrInjectionChecker` | CWE-94 | JCR 查询注入 |
| `JspDynamicIncludeChecker` | CWE-98 | JSP 动态包含 |

### 4.2 Command / Code (5)

| Checker | CWE | Sink |
| --- | --- | --- |
| `CommandInjectionChecker` | CWE-78/77 | `Runtime.exec` / `ProcessBuilder` |
| `JavaCodeInjectionChecker` | CWE-94 | `ClassLoader.defineClass` / `ScriptEngine.eval` |
| `ElInjectionChecker` | CWE-94 | JSP EL `${}` |
| `OgnlInjectionChecker` | CWE-94 | OGNL 表达式 |
| `UnsafeJniChecker` | CWE-111 | JNI native 调用 |
| `UnsafeReflectionChecker` | CWE-470/578 | `Method.invoke` with tainted class/method |

### 4.3 Web / HTTP / XSS (4)

| Checker | CWE | Sink |
| --- | --- | --- |
| `XssInjectionChecker` | CWE-79 | `HttpServletResponse.getWriter().print*` |
| `HttpResponseSplittingChecker` | CWE-113 | response header / redirect URL |
| `XPathInjectionChecker` | CWE-643 | XPath 表达式 |
| `LdapInjectionChecker` | CWE-90 | LDAP filter |
| `OpenRedirectChecker` | CWE-601 | `response.sendRedirect` |
| `TrustBoundaryViolationChecker` | CWE-501 | 跨越 trust boundary |

### 4.4 Path / File (1)

| Checker | CWE | Sink |
| --- | --- | --- |
| `PathTraversalChecker` | CWE-22/23 | `new File` / `Paths.get` / `FileInputStream` |
| `ExternalConfigSettingSinkChecker` | CWE-15 | 外部配置写入 (Spring/Struts) |

### 4.5 Crypto / Credential (3)

| Checker | CWE | Sink |
| --- | --- | --- |
| `InsecureRandomChecker` | CWE-330/338 | `Random` / `Math.random` 用于安全上下文 |
| `CleartextNetworkCredentialSinkChecker` | CWE-319/201 | 网络明文凭据传输 |
| `BadCertVerificationChecker` | CWE-295/296 | 弱证书验证 |

### 4.6 Numeric / Range (5)

| Checker | CWE | Sink |
| --- | --- | --- |
| `DivByZeroChecker` | CWE-369 | `/ %` 操作的零除数 |
| `IntOverflowChecker` | CWE-190 | 整数溢出 (`+ - * <<` with tainted) |
| `IntUnderflowChecker` | CWE-191 | 整数下溢 (unary `-`) |
| `NumericTruncationChecker` | CWE-197/681 | 数值截断 (`int x = (int) long_var`) |
| `UncontrolledMemAllocChecker` | CWE-789/770 | 内存分配大小可控 (新数组长度) |

### 4.7 Null / Exception (3)

| Checker | CWE | Sink |
| --- | --- | --- |
| `NullDerefChecker` | CWE-476/601 | 在可能 null 的值上 method call / deref |
| `ResourceLeakChecker` | CWE-404/459/770 | 资源未关闭 (`InputStream` / `Connection`) |
| `UncontrolledRecursionChecker` | CWE-674 | 递归深度受控 |
| `SensitiveDataLeakChecker` | CWE-200/359 | 敏感数据 sink (logger / response) |
| `SensitivePIILeakChecker` | CWE-359/532 | PII sink |
| `SessionFixationChecker` | CWE-384 | session ID 未更换 |
| `FormatStringChecker` | CWE-134 | `String.format` 格式串受控 |

### 4.8 Loop / Unused (8)

| Checker | CWE | Sink |
| --- | --- | --- |
| `UncheckedLoopConditionChecker` | CWE-606/835 | 循环条件受控 |
| `AssignmentInConditionalChecker` | CWE-481/783 | 条件中赋值 (`if (x = y)`) |
| `LiteralBooleanConditionChecker` | CWE-570/571/561 | 字面 boolean 条件 (`while (true)` / `if (1 == 1)`) |
| `UnusedAssignmentChecker` | CWE-563 | 未使用赋值 |
| `UnusedFieldChecker` | CWE-563 | 未使用字段 |
| `UnusedParameterChecker` | CWE-1173 | 未使用参数 |
| `UnusedVariableChecker` | CWE-563 | 未使用局部变量 (R620 实现的 source-reading 版本;**与 B 类的 `UnusedVariableChecker` (TaintedCheckerBase) 同名但不同**) |
| `ArrayIndexChecker` | CWE-129/193/786 | 数组下标越界 (taint 上下文) |
| `UseAfterFreeChecker` | CWE-416 | free 后使用 (R83 修复) |

---

## 5. D. AI-primary (0)

Plumb 当前**没有内置 AI-primary 检查器**。

AI 用法是:
- `--ai-dedup` (R670-R672.1):把 A/B/C 检查器的输出送到 LLM,获得 `ai_verdict` (TP/FP/UNKNOWN)
- `--ai-scan` (R672.1):LLM-as-scanner 模式,ReAct + 15 tools;**默认关闭**,需要大量 token

R685 计划:实现"单 prompt 模式"(无 ReAct),把 AI coverage 从 25.9% 提升到 80%+。

---

## 6. 启用 / 禁用

### 6.1 在 `conf/default-config.yaml` 中

```yaml
checks:
  taint: true                # C 类 (42 个 taint checkers) 的 master switch
  style: true                # A 类 (515 个) master switch
  divByZero: { enabled: true, aiDedup: true }    # C 类 sub-family
  bigDecimalDoubleConstructor: { enabled: true, aiDedup: true }   # B 类
  defaultEncoding: { enabled: true, aiDedup: true }                # B 类
  # ... 39 个 family
```

### 6.2 在 CLI 中

```bash
# 只跑特定规则
java -jar plumb-...-all.jar -i src/ --rule bu-0101-taint-sink-sql
java -jar plumb-...-all.jar -i src/ --rule pl-0539-empty-catch-block

# 列出所有规则 (含 rule ID)
java -jar plumb-...-all.jar --list-rules
```

### 6.3 R675 命名前缀 (历史兼容)

**R675 之前**的 rule ID 形如 `cwe-89-taint-sink-sql-injection`,**R675 之后**变为 `bu-0101-taint-sink-sql`。R675 删除了 `Cwe` 前缀(规则名 + 内部类名同步)。

**当前 prefix 约定**:
- `bu-NNNN-...` - taint 引擎 (C 类)
- `pl-NNNN-...` - plumb-style (A 类) 或 plumb-dfa direct (B 类)
- `cwe-NNN-...` - (历史) 来自早期 R* round,部分仍存在

---

## 7. CWE 覆盖统计 (R683 Juliet)

按 CWE 家族 (Juliet v1.3):

| CWE | Plumb 检查器 | R/P (raw) | R/P (AI dedup) |
| --- | --- | --- | --- |
| CWE-22 (Path Traversal) | `PathTraversalChecker` | 100% / 27% | 99.5% / 95% |
| CWE-78 (Command Injection) | `CommandInjectionChecker` | 100% / 28% | 99.7% / 96% |
| CWE-79 (XSS) | `XssInjectionChecker` | 100% / 31% | 99.6% / 95% |
| CWE-89 (SQL Injection) | `SqlInjectionChecker` | 100% / 26% | 99.8% / 97% |
| CWE-190 (Integer Overflow) | `IntOverflowChecker` + `LooseIntOverflowChecker` | 100% / 24% | (R685 待评估) |
| CWE-191 (Integer Underflow) | `IntUnderflowChecker` + `LooseIntUnderflowChecker` | 95% / 18% | (R222b 待修) |
| CWE-252 (Unchecked Return) | `UncheckedReturnChecker` | 50% / 12% | (R222b 待修) |
| CWE-253 (Incorrect Check) | (R222b 已知漏) | 30% / 8% | (R222b 待修) |
| CWE-416 (Use After Free) | `UseAfterFreeChecker` | 100% / 100% (post-R83) | n/a |
| CWE-476 (Null Deref) | `NullDerefChecker` + `NullDerefFromReturnChecker` | 100% / 35% | 99.9% / 92% |
| CWE-501 (Trust Boundary) | `TrustBoundaryViolationChecker` | 100% / 29% | 99.4% / 91% |
| CWE-611 (XXE) | `XxeChecker` | 100% / 32% | 99.6% / 95% |
| CWE-798 (Hardcoded Cred) | `HardCodedKeyChecker` + `FieldHardcodedPasswordChecker` | 100% / 26% | 99.7% / 95% |
| CWE-835 (Infinite Loop) | `UncheckedLoopConditionChecker` + `LiteralBooleanConditionChecker` | 100% / 30% | 99.5% / 89% |
| ... (全部 112 CWE) | ... | R=100% (overall) / P=30% | (R683 评估的 9 CWE: 平均 25.9% covered, 99.96% in-scope accuracy) |

**R658 baseline (raw, no AI)**:Recall 95.0% / Precision 73.2% (weighted by CWE, non-style rules only).
**R672.1 with v11 prompt**:100% FP-precision / 88% filter / 0% bug loss (50-case commons-lang3 sample).

---

## 8. 维护

| 任务 | R-round | 说明 |
| --- | --- | --- |
| 加新 plumb-style check | R* | 在 `plumb-style/.../<category>/<Name>Check.java` 写一个 `extends AstChecker`,plugin 自动注册 |
| 加新 plumb-dfa check (no taint) | R* | 在 `plumb-dfa/.../checkers/<Name>Checker.java` extends `DfaChecker`,在 `CheckerConfig` 加开关 |
| 加新 taint check | R* | 在 `plumb-dfa/.../checkers/taint/<Name>Checker.java` extends `TaintedCheckerBase`,在 `sink-families.yaml` 加 sink pattern |
| 删除重复 / 合并 | R674+ | R676 是最近一轮 (9 个 dfa-unified 重复 → 删除) |
| 命名约定 | R675 | **规则名 + 内部类名都不带 `Cwe` 前缀** |

**禁止**:
- 在 plumb-style 写 DFA 逻辑 (风格检查应该是纯 AST)
- 在 plumb-dfa 写纯 AST 规则 (那是 plumb-style 的工作)
- 在 plumb-ai 写静态检查逻辑 (那是 plumb-dfa 的工作)
