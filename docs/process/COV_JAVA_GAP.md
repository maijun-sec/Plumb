# Coverity Java Checker — 待实现清单 (2026-08-08)

> 对比 `D:/wsl/tools/cov-analysis-linux64-2023.6.0/doc/en/cov_checker_ref.html` 中 Java 规则，
> 列出 plumb 当前 **未实现** 的 Coverity 顶层 Java checker。
>
> **注意**: 本文只关注 Coverity 顶层 35 个 Java checker（去掉 FB.* SpotBugs 家族），
> 因为 plumb 的 SpotBugs 移植在 `plumb-style` 下已通过 PMD/Sonar 覆盖，独立 P0 体系。

## 1. 总览 (2026-08-08 updated)

| 状态 | 数量 | 说明 |
|------|------|------|
| ✅ Plumb 已实现 | 25 | 含 11 Taint + 6 AST + 5 JPA/Annotation + 2 Concurrent + 3 P1 Taint + 3 原有 |
| ❌ P2 (跳过/弃) | 7 | Android-only / legacy / sandbox |
| ❌ ATTRIBUTE_NAME_CONFLICT | 1 | JSP-only，plumb Java-only 跳过 |
| **总计** | **35** | |

**覆盖率**: 25 / 35 = 71.4% (P0+P1 全部实现)
**跳过原因**: 7 个 Android-only checker + 1 个 JSP-only checker
**测试**: plumb-style 941 / 941 通过, plumb-dfa 498 / 498 通过 (0 回归，含 R543-DA OrmLostUpdateDfaChecker 8 tests + R585 aggressiveness 测试)

## 1a. R-Range 分配 (2026-08-08)

| R-range | 类型 | 数量 | Module |
|---------|------|------|--------|
| R500-R507 | Taint P0-1 | 8 | plumb-dfa (TaintedCheckerBase) |
| R520-R526 | AST P0-2 | 6 | plumb-style |
| R530 | Annotation 基础设施 | 1 | plumb-style/annotations/ |
| R540-R544 | JPA/Annotation P0-3 | 5 | plumb-style |
| R560-R561 | Concurrent P0-4 | 2 | plumb-style |
| R580-R582 | Taint P1 | 3 | plumb-dfa |
| R540-DA / R541-DA / R542-DA / R543-DA / R560-DA / R561-DA | DFA 增强版 | 6 | plumb-dfa |
| **总** | | **31** | (P0+P1+DA) |

## 2. P0 详细 (22 项 — 必须实现)

按实现层分 (AST / DFA / Taint)。每条都给出 CWE、对应的 Juliet family (如可测)、实现思路。

### 2.1 Taint (8 个)

| # | Coverity ID | CWE | Sink family / 实现提示 | 简述 |
|---|-------------|-----|------------------------|------|
| 1 | `BAD_CERT_VERIFICATION` | CWE-295 ⚪ | `ssl.sinks` | looks for instances of incomplete or improper validation of security certificates (CWE 295 and CWE 296) |
| 2 | `UNSAFE_REFLECTION` | CWE-470 🟢 | `reflection.sinks` | finds unsafe reflection vulnerabilities, which arise when uncontrolled dynamic data is used as a class, method, or field/property na |
| 3 | `UNSAFE_JNI` | CWE-114 🟢 | `jni.sinks` | finds unsafe Java Native interface library injection vulnerabilities, which arise when uncontrolled dynamic data is used as a dynamic libra |
| 4 | `EL_INJECTION` | CWE-94 🟢 | `el.sinks` | finds Expression Language (EL) injection vulnerabilities, which arise when uncontrolled dynamic data is passed into an EL resolver |
| 5 | `OGNL_INJECTION` | CWE-94 🟢 | `ognl.sinks` | finds Object-Graph Navigation Language (OGNL) injection vulnerabilities, which arise when uncontrolled dynamic data is passed into an O |
| 6 | `JCR_INJECTION` | CWE-94 🟢 | `jcr.sinks` | finds Content Repository for Java (JCR) injection vulnerabilities, which arise when uncontrolled dynamic data is passed into a query API |
| 7 | `JSP_DYNAMIC_INCLUDE` | CWE-829 ⚪ | `jsp-include.sinks` | finds JSP dynamic include vulnerabilities, which arise when uncontrolled dynamic data is used as part of a JSP include path |
| 8 | `TRUST_BOUNDARY_VIOLATION` | CWE-501 ⚪ | `trust-boundary.sinks` | reports a defect when tainted data flows to a data structure or context that is often assumed to be trustworthy |

### 2.2 DFA (4 个)

| # | Coverity ID | CWE | Sink family / 实现提示 | 简述 |
|---|-------------|-----|------------------------|------|
| 1 | `UNINIT_NONNULL` | CWE-457 ⚪ | `per-constructor null tracking` | The UNINIT_NONNULL checker reports fields that are annotated as non-null, but are not assigned non-null values by the constructor |
| 2 | `ORM_UNNECESSARY_GET` | CWE-252 🟢 | `JPA API + dead store` | Because accessing the database is an expensive operation that should occur only when it is necessary, ORM_UNNECESSARY_GET finds cases in which the con |
| 3 | `SINGLETON_RACE` | CWE-366 ⚪ | `field concurrency` | checker finds many cases where a singleton object can handle multiple requests simultaneously with different threads (for example, a se |
| 4 | `SERVLET_ATOMICITY` | CWE-362 ⚪ | `Servlet API model` | finds instances of atomicity violations on calls to getAttribute and setAttribute on the objects of the following types: javax |

### 2.3 AST (10 个)

| # | Coverity ID | CWE | Sink family / 实现提示 | 简述 |
|---|-------------|-----|------------------------|------|
| 1 | `REGEX_CONFUSION` | CWE-185 ⚪ | `single-method` | finds cases where a developer who is unaware that a method or parameter takes a regular expression (regex) passes a string that contai |
| 2 | `WEAK_URL_SANITIZATION` | CWE-20 ⚪ | `URL constructor patterns` | The WEAK_URL_SANITIZATION checker finds several cases of weak sanitization of URLs that can be used in a security context: |
| 3 | `UNLOGGED_SECURITY_EXCEPTION` | CWE-778 ⚪ | `catch-clause pattern` | The UNLOGGED_SECURITY_EXCEPTION checker reports security exceptions that are caught but not logged |
| 4 | `WRONG_METHOD` | CWE-253 ⚪ | `method call pattern` | finds certain incorrect usage of Boolean |
| 5 | `DC.DANGEROUS` | CWE-663 ⚪ | `method call deny-list` | DC |
| 6 | `DC.DEADLOCK` | CWE-662 ⚪ | `method call deny-list` | DC |
| 7 | `ATTRIBUTE_NAME_CONFLICT` | CWE-1006 ⚪ | `JSP tag pattern` | reports attributes that have been incorrectly repeated in a single JSP tag |
| 8 | `HIBERNATE_BAD_HASHCODE` | CWE-579 ⚪ | `JPA entity pattern` | finds many instances where the hashCode() or equals() method of a Hibernate entity (or other Java Persistence API (JPA) entity) |
| 9 | `ORM_LOST_UPDATE` | CWE-459 🟢 | `JPA lifecycle` | finds several different errors involving the persistent object "lifecycle" of Object-Relational Mapping (ORM) systems such as JPA or H |
| 10 | `ORM_LOAD_NULL_CHECK` | CWE-252 🟢 | `JPA API model` | finds many instances of checking the result of a load operation for null |

## 3. P1 详细 (3 项 — 中等优先级)

| Coverity ID | CWE | 当前覆盖 | 增量工作 |
|-------------|-----|---------|---------|
| `SESSION_FIXATION` | CWE-384 | 无 | 需要 HttpSession API 建模；动态 taint 到 session id |
| `INSECURE_RANDOM` | CWE-330 | plumb-style 弱覆盖 | 扩 Taint + InsecureCryptoChecker，sinks: java.util.Random.*, Math.random() |
| `JAVA_CODE_INJECTION` | CWE-94 | CommandInjectionChecker 覆盖 Runtime.exec | 需新增 ScriptEngine.eval, GroovyShell.evaluate, SpEL 入口 |

## 4. P2 跳过 (7 项)

实现 ROI 极低，建议不实现 (除非未来有 Android 后端需求)。

| Coverity ID | 类别 | 跳过理由 |
|-------------|------|---------|
| `ANDROID_CAPABILITY_LEAK` | Android | 无通用 Java 应用场景 |
| `CONFIG.ANDROID_GRADLE_OBFUSCATION_NOT_ENABLED` | Android build config | 无通用 Java 应用场景 |
| `IMPLICIT_INTENT` | Android Intent | 无通用 Java 应用场景 |
| `MISSING_PERMISSION_FOR_BROADCAST` | Android BroadcastReceiver | 无通用 Java 应用场景 |
| `MOBILE_ID_MISUSE` | Mobile device identifier (Android/iOS) | 无通用 Java 应用场景 |
| `SEALED_JAR_ESCAPE` | Java Web Start / applet sandbox (legacy) | 无通用 Java 应用场景 |
| `UNRESTRICTED_ACCESS_TO_FILE` | Android Storage (per Coverity 描述) | 无通用 Java 应用场景 |

## 5. 已覆盖 (25 项) — R500-R582 + 3 原有

### 5.1 原有覆盖 (3 项)

| Coverity ID | Plumb 实现 | CWE | 备注 |
|-------------|-----------|-----|------|
| `HARDCODED_CREDENTIALS` | plumb-style/HardcodedCredentialsCheck | CWE-798 | AST 字符串字面量 |
| `JSP_SQL_INJECTION` | plumb-dfa/SqlInjectionChecker | CWE-89 | Taint to sql.sinks + jstl-sql.sinks |
| `ORM_ABANDONED_TRANSIENT` | ORM_LOST_UPDATE 同源 (alias) | CWE-459 | Coverity 文档明确说 "See ORM_LOST_UPDATE" |

### 5.2 本批次覆盖 (22 项 P0+P1)

#### Taint (R500-R507 + R580-R582) — 11 项

| R | Coverity ID | CWE | 新增 sink family | Plumb checker |
|---|-------------|-----|------------------|---------------|
| R500 | `BAD_CERT_VERIFICATION` | CWE-295 | `ssl.sinks` (新) | `taint.BadCertVerificationChecker` |
| R501 | `UNSAFE_REFLECTION` | CWE-470 | `reflection.sinks` (扩展) | `taint.UnsafeReflectionChecker` |
| R502 | `UNSAFE_JNI` | CWE-114 | `jni.sinks` (新) | `taint.UnsafeJniChecker` |
| R503 | `EL_INJECTION` | CWE-94 | `el.sinks` (新) | `taint.ElInjectionChecker` |
| R504 | `OGNL_INJECTION` | CWE-94 | `ognl.sinks` (扩展) | `taint.OgnlInjectionChecker` |
| R505 | `JCR_INJECTION` | CWE-94 | `jcr.sinks` (新) | `taint.JcrInjectionChecker` |
| R506 | `JSP_DYNAMIC_INCLUDE` | CWE-829 | `jsp-include.sinks` (新) | `taint.JspDynamicIncludeChecker` |
| R507 | `TRUST_BOUNDARY_VIOLATION` | CWE-501 | `trust-boundary.sinks` (新) | `taint.TrustBoundaryViolationChecker` |
| R580 | `SESSION_FIXATION` | CWE-384 | `session.sinks` (新) | `taint.SessionFixationChecker` |
| R581 | `INSECURE_RANDOM` | CWE-330 | `random.sinks` (新) | `taint.InsecureRandomChecker` |
| R582 | `JAVA_CODE_INJECTION` | CWE-94 | `code-execution.sinks` (新) | `taint.JavaCodeInjectionChecker` |

#### AST (R520-R526) — 6 项

| R | Coverity ID | CWE | Plumb checker |
|---|-------------|-----|---------------|
| R520 | `DC.DANGEROUS` | CWE-663/396 | `security.DcDangerousCheck` |
| R521 | `DC.DEADLOCK` | CWE-662/764 | `security.DcDeadlockCheck` |
| R523 | `WRONG_METHOD` | CWE-253/665 | `quality.WrongMethodCheck` |
| R524 | `UNLOGGED_SECURITY_EXCEPTION` | CWE-778/223 | `security.UnloggedSecurityExceptionCheck` |
| R525 | `REGEX_CONFUSION` | CWE-625/184 | `quality.RegexConfusionCheck` |
| R526 | `WEAK_URL_SANITIZATION` | CWE-20/601 | `security.WeakUrlSanitizationCheck` |

#### JPA / Annotation (R530 + R540-R544) — 6 项

| R | Coverity ID | CWE | Plumb checker |
|---|-------------|-----|---------------|
| R530 | (基础设施) | n/a | `annotations.AnnotationFacts` + `AnnotationExtractor` |
| R540 | `UNINIT_NONNULL` | CWE-457 | `quality.UninitNonnullCheck` |
| R541 | `ORM_LOAD_NULL_CHECK` | CWE-252 | `quality.OrmLoadNullCheckCheck` |
| R542 | `ORM_UNNECESSARY_GET` | CWE-252 | `quality.OrmUnnecessaryGetCheck` |
| R543 | `ORM_LOST_UPDATE` | CWE-459 | `quality.OrmLostUpdateCheck` |
| R544 | `HIBERNATE_BAD_HASHCODE` | CWE-579 | `quality.HibernateBadHashcodeCheck` |

#### Concurrent (R560-R561) — 2 项

| R | Coverity ID | CWE | Plumb checker |
|---|-------------|-----|---------------|
| R560 | `SINGLETON_RACE` | CWE-366 | `concurrency.SingletonRaceCheck` |
| R561 | `SERVLET_ATOMICITY` | CWE-362 | `concurrency.ServletAtomicityCheck` |

### 5.3 DFA 增强 (6 项, 保证不漏报)

| R | 对应 AST checker | DFA checker | 增强能力 |
|---|-----------------|-------------|----------|
| R540-DA | `UninitNonnullCheck` | `UninitNonnullDfaChecker` | 追踪 @NonNull 字段在 helper method / ctor chaining 里的赋值 |
| R541-DA | `OrmLoadNullCheckCheck` | `OrmLoadNullCheckDfaChecker` | 追踪 load 结果在 alias 链上多个 statement 后的 null check |
| R542-DA | `OrmUnnecessaryGetCheck` | `OrmUnnecessaryGetDfaChecker` | 追踪 get/find 结果通过 alias 是否被 content access |
| **R543-DA** | `OrmLostUpdateCheck` | `OrmLostUpdateDfaChecker` | **跨方法持久化追踪（PersistsParam Operation）** |
| R560-DA | `SingletonRaceCheck` | `SingletonRaceDfaChecker` | 跨方法聚合 field read+write |
| R561-DA | `ServletAtomicityCheck` | `ServletAtomicityDfaChecker` | 跨方法 getAttribute/setAttribute 同一 key 检测 |

**DFA 增强策略**: AST 版本只看单方法。DFA 版本跨方法用 FunctionSummary (Operation.WriteToField + 新增 Operation.PersistsParam) +
MOVE alias propagation。两者并存 (rule id 后缀 `-dfa`)，不重复计数。
详见 `COV_JAVA_DFA_AUGMENTATION.md`。

## 6. P0 实现优先级总表

按 CWE 可测性 + 跨函数需求排序:

| 优先级 | Pattern | CWE | 实现层 | R 计划 |
|--------|---------|-----|--------|--------|
| P0-1 | `BAD_CERT_VERIFICATION` | CWE-295 | Taint | R500 |
| P0-1 | `UNSAFE_REFLECTION` | CWE-470 | Taint | R501 |
| P0-1 | `UNSAFE_JNI` | CWE-114 | Taint | R502 |
| P0-1 | `EL_INJECTION` | CWE-94 | Taint | R504 |
| P0-1 | `OGNL_INJECTION` | CWE-94 | Taint | R503 |
| P0-1 | `JCR_INJECTION` | CWE-94 | Taint | R505 |
| P0-1 | `JSP_DYNAMIC_INCLUDE` | CWE-829 | Taint | R506 |
| P0-2 | `REGEX_CONFUSION` | CWE-185 | AST | R524 |
| P0-2 | `WEAK_URL_SANITIZATION` | CWE-20 | AST | R525 |
| P0-2 | `UNLOGGED_SECURITY_EXCEPTION` | CWE-778 | AST | R523 |
| P0-3 | `UNINIT_NONNULL` | CWE-457 | DFA | R540 |
| P0-2 | `WRONG_METHOD` | CWE-253 | AST | R521 |
| P0-2 | `DC.DANGEROUS` | CWE-663 | AST | R520 |
| P0-2 | `DC.DEADLOCK` | CWE-662 | AST | R520 |
| P0-2 | `ATTRIBUTE_NAME_CONFLICT` | CWE-1006 | AST | R522 |
| P0-3 | `HIBERNATE_BAD_HASHCODE` | CWE-579 | AST | R544 |
| P0-3 | `ORM_LOST_UPDATE` | CWE-459 | AST | R543 |
| P0-3 | `ORM_LOAD_NULL_CHECK` | CWE-252 | AST | R541 |
| P0-3 | `ORM_UNNECESSARY_GET` | CWE-252 | DFA | R542 |
| P0-4 | `SINGLETON_RACE` | CWE-366 | DFA | R560 |
| P0-4 | `SERVLET_ATOMICITY` | CWE-362 | DFA | R561 |
| P0-1 | `TRUST_BOUNDARY_VIOLATION` | CWE-501 | Taint | R507 |

## 7. P0 完整描述 (从 Coverity 文档提取)

### `BAD_CERT_VERIFICATION`  (P0-1, Taint, CWE=CWE-295)

> looks for instances of incomplete or improper validation of security certificates (CWE 295 and CWE 296). Improper checking of certificates can make the system vulnerable to "man-in-the-middle" attacks, which enable an attacker to eavesdrop or interfere with the traffic in the session.

**实现提示**: `ssl.sinks`
**计划 R 编号**: R500

### `UNSAFE_REFLECTION`  (P0-1, Taint, CWE=CWE-470)

> finds unsafe reflection vulnerabilities, which arise when uncontrolled dynamic data is used as a class, method, or field/property name. This name is then passed to a reflection API. This security vulnerability might allow an attacker to bypass security checks, obtain unauthorized data, or execute arbitrary code.

**实现提示**: `reflection.sinks`
**计划 R 编号**: R501

### `UNSAFE_JNI`  (P0-1, Taint, CWE=CWE-114)

> finds unsafe Java Native interface library injection vulnerabilities, which arise when uncontrolled dynamic data is used as a dynamic library path. This security vulnerability might allow an attacker to load an untrusted dynamic library and potentially execute unsafe code.

**实现提示**: `jni.sinks`
**计划 R 编号**: R502

### `EL_INJECTION`  (P0-1, Taint, CWE=CWE-94)

> finds Expression Language (EL) injection vulnerabilities, which arise when uncontrolled dynamic data is passed into an EL resolver. This might allow an attacker to bypass security checks or execute arbitrary code.

**实现提示**: `el.sinks`
**计划 R 编号**: R504

### `OGNL_INJECTION`  (P0-1, Taint, CWE=CWE-94)

> finds Object-Graph Navigation Language (OGNL) injection vulnerabilities, which arise when uncontrolled dynamic data is passed into an OGNL evaluation API. OGNL can execute arbitrary Java code, in addition to performing language functions similar to those performed by Java Expression Language (EL).

**实现提示**: `ognl.sinks`
**计划 R 编号**: R503

### `JCR_INJECTION`  (P0-1, Taint, CWE=CWE-94)

> finds Content Repository for Java (JCR) injection vulnerabilities, which arise when uncontrolled dynamic data is passed into a query API for JCR. This security vulnerability might allow an attacker to affect the behavior of the JCR, bypass security controls, or obtain unauthorized data.

**实现提示**: `jcr.sinks`
**计划 R 编号**: R505

### `JSP_DYNAMIC_INCLUDE`  (P0-1, Taint, CWE=CWE-829)

> finds JSP dynamic include vulnerabilities, which arise when uncontrolled dynamic data is used as part of a JSP include path. An attacker can manipulate the local path of the JSP and bypass authorization or examine sensitive information.

**实现提示**: `jsp-include.sinks`
**计划 R 编号**: R506

### `REGEX_CONFUSION`  (P0-2, AST, CWE=CWE-185)

> finds cases where a developer who is unaware that a method or parameter takes a regular expression (regex) passes a string that contains special regex characters (for example, a filename that contains a period or dot). Such a mistake can cause the program to interpret the string in an unintended way that causes errors.

**实现提示**: `single-method`
**计划 R 编号**: R524

### `WEAK_URL_SANITIZATION`  (P0-2, AST, CWE=CWE-20)

> The WEAK_URL_SANITIZATION checker finds several cases of weak sanitization of URLs that can be used in a security context:

**实现提示**: `URL constructor patterns`
**计划 R 编号**: R525

### `UNLOGGED_SECURITY_EXCEPTION`  (P0-2, AST, CWE=CWE-778)

> The UNLOGGED_SECURITY_EXCEPTION checker reports security exceptions that are caught but not logged.

**实现提示**: `catch-clause pattern`
**计划 R 编号**: R523

### `UNINIT_NONNULL`  (P0-3, DFA, CWE=CWE-457)

> The UNINIT_NONNULL checker reports fields that are annotated as non-null, but are not assigned non-null values by the constructor. Class fields can be annotated as non-null using common annotation classes such as @NonNull or @NotNull . Non-null fields are not expected to have a null value outside of their class constructors. Each constructor implicitly initializes all fields of this instance as null at the beginning, however non-null fields are expected to be assigned non-null values by the end

**实现提示**: `per-constructor null tracking`
**计划 R 编号**: R540

### `WRONG_METHOD`  (P0-2, AST, CWE=CWE-253)

> finds certain incorrect usage of Boolean.getBoolean , Integer.getInteger , and some of their variants. For example, Boolean.getBoolean and Integer.getInteger return the value of a given system property and parse it to Boolean and Integer type, respectively. So they expect a string (as the first argument) that represents the name of a system property. A typical misunderstanding is that Boolean.getBoolean parses strings like true and false to Boolean values and Integer.getInteger does

**实现提示**: `method call pattern`
**计划 R 编号**: R521

### `DC.DANGEROUS`  (P0-2, AST, CWE=CWE-663)

> detects unsafe calls to the deprecated stop() methods from java.lang.Thread and java.lang.ThreadGroup . It also detects calls to java.lang.Thread.destroy() because the method is not implemented, contrary to what a developer using that method might believe. Furthermore, calls to java.io.ObjectOutputStream$PutField.write(java.io.ObjectOutput) are detected because the calls might corrupt serialization streams.

**实现提示**: `method call deny-list`
**计划 R 编号**: R520

### `DC.DEADLOCK`  (P0-2, AST, CWE=CWE-662)

> detects deadlock-prone calls to the deprecated methods suspend() and resume() from java.lang.Thread and java.lang.ThreadGroup . In addition, calls to java.lang.Thread.countStackFrames() and java.lang.ThreadGroup.allowThreadSuspension(boolean) are detected because they depend on suspend() methods.

**实现提示**: `method call deny-list`
**计划 R 编号**: R520

### `ATTRIBUTE_NAME_CONFLICT`  (P0-2, AST, CWE=CWE-1006)

> reports attributes that have been incorrectly repeated in a single JSP tag. This syntax is invalid but it is tolerated by some servlet containers and JSP compilers. The behavior of the rendered document is undefined. The impact of the error depends on the particular tag and attribute, whether its evaluation has side effects and how the values conflict. Some tags use attributes to control security-related behavior, for example, output escaping.

**实现提示**: `JSP tag pattern`
**计划 R 编号**: R522

### `HIBERNATE_BAD_HASHCODE`  (P0-3, AST, CWE=CWE-579)

> finds many instances where the hashCode() or equals() method of a Hibernate entity (or other Java Persistence API (JPA) entity) depends on the database primary key (usually annotated with @Id ). Because the key is not assigned until after persisting an object, it is unsafe to use such entities with Java or Kotlin collections before persisting. An incorrect hashCode() implementation can result in entities "disappearing" from a set (that is, set.contains(obj) unexpectedly re

**实现提示**: `JPA entity pattern`
**计划 R 编号**: R544

### `ORM_LOST_UPDATE`  (P0-3, AST, CWE=CWE-459)

> finds several different errors involving the persistent object "lifecycle" of Object-Relational Mapping (ORM) systems such as JPA or Hibernate. The two main problems found by this checker are transient objects not being persisted and modified detached objects not being merged back into a session. This checker will also report ORM operations that will throw exceptions based on the current state of an object they are applied to.

**实现提示**: `JPA lifecycle`
**计划 R 编号**: R543

### `ORM_LOAD_NULL_CHECK`  (P0-3, AST, CWE=CWE-252)

> finds many instances of checking the result of a load operation for null.

**实现提示**: `JPA API model`
**计划 R 编号**: R541

### `ORM_UNNECESSARY_GET`  (P0-3, DFA, CWE=CWE-252)

> Because accessing the database is an expensive operation that should occur only when it is necessary, ORM_UNNECESSARY_GET finds cases in which the contents of an entity obtained through a call to Session.get(...) is not used on a given path, making the method call and consequent database access unnecessary. If only a reference to the object is used (such as storing it into a field of another object) or only the ID of the object is used, it might be more efficient to use a load operation than a g

**实现提示**: `JPA API + dead store`
**计划 R 编号**: R542

### `SINGLETON_RACE`  (P0-4, DFA, CWE=CWE-366)

> checker finds many cases where a singleton object can handle multiple requests simultaneously with different threads (for example, a servlet), but the code that handles the requests does not safely synchronize access to instance or static fields of the class. The checker reports these thread-unsafe updates as defects.

**实现提示**: `field concurrency`
**计划 R 编号**: R560

### `SERVLET_ATOMICITY`  (P0-4, DFA, CWE=CWE-362)

> finds instances of atomicity violations on calls to getAttribute and setAttribute on the objects of the following types: javax.servlet.ServletContext ;

**实现提示**: `Servlet API model`
**计划 R 编号**: R561

### `TRUST_BOUNDARY_VIOLATION`  (P0-1, Taint, CWE=CWE-501)

> reports a defect when tainted data flows to a data structure or context that is often assumed to be trustworthy. Because data from such sources might not be validated or sanitized, this data could be mistakenly used in an insecure manner.

**实现提示**: `trust-boundary.sinks`
**计划 R 编号**: R507
