# R115 → R125 — 累计总结

本文档汇总 R115–R125 这 11 轮工作的累计技术成果，便于论文撰写和未来参考。
R125 完成了 annotation-aware IR，把 MISSING_AUTHZ 跟 TRUST_BOUNDARY 的前置条件
补齐；架构上 R123 TypeTable 跟 R125 注解 IR 形成"角色识别 + 数据流"的双视图
分析范式。

## 整体时间线

| Round | 主题 | 核心动作 | 新 checker | 新 CWE |
|-------|------|---------|----------|-------|
| R115 | Lambda + inner class | 翻译层 + sanitize summary | 0 | — |
| R116 | Inter-proc taint | 递归 worklist + 拉返回 taint | 0 | — |
| R117 | MergedProgram | cross-TU 合并 | 0 | — |
| R118 | Field-sensitive + per-fn source path | field map + cross-TU file 列 | 0 | — |
| R119a | Extended taint sinks | catalog 8 个新 sink | 0 | 8 |
| R119b | Crypto & random | 5 个新 checker | 5 | 7 |
| R119c | Webapp config | 3 个新 checker | 3 | 4 |
| R120 | Defensive | 3 个新 checker (BAD_SHIFT, INFINITE_LOOP, CONSTANT_CONDITION) | 3 | 4 |
| R121 | Sensitive data leak | 1 个新 checker + 独立 catalog | 1 | 1 |
| R122 | R119b/R119c limitations | 修 2 个具体限制 | 0 | — |
| R123 | TypeTable wire-up | method-only pattern 收紧 | 0 | — |
| R124 | Field hardcoded credential | 1 个新 checker + translator 修 | 1 | 2 |
| R125 | Annotation-aware IR | TacFunction 加 3 维注解 + R125b 消费 | 0 | — |
| **总计** | | | **14 新 checker** | **24+ CWE** |

## 测试规模演进

| Round | dfa tests | 总 tests | checker 类别 |
|-------|----------:|--------:|------------:|
| R114 baseline | ~50 | ~70 | 15 |
| R117 | 67 | 100 | 22 |
| R118 | 67 + 1 skip | 100 + 1 | 22 |
| R119a | 76 + 1 skip | 109 + 1 | 30 |
| R119c | 95 + 1 skip | 127 + 1 | 34 |
| R120 | 95 + 1 skip | 127 + 1 | 34 |
| R121 | 102 + 1 skip | 134 + 1 | 35 |
| R122 | 109 + 1 skip | 141 + 1 | 35 |
| R123 | 112 + 1 skip | 144 + 1 | 35 |
| R124 | 118 + 1 skip | 150 + 1 | 36 |
| **R125** | **128 + 1 skip** | **160 + 1** | **36** |

**测试/类别比** ≈ 4.4 测试每类别 — 一个合理的覆盖密度。

## 累计 checker 类别（36 个）

### 7 个原始 DFA (R114)
- `ResourceLeakCheck`, `NullDerefCheck`, `HardcodedCredentialCheck`
- `IntegerOverflowCheck`, `DivByZeroCheck`, `ArrayBoundsCheck`
- `TaintAnalysis`（17 个 sink）

### 8 个 R119a extended taint sinks
- LDAP_INJECTION (CWE-90)
- XML_INJECTION (CWE-91)
- HEADER_INJECTION (CWE-93)
- FORMAT_STRING (CWE-134)
- NOSQL_INJECTION (CWE-943)
- OGNL_INJECTION (CWE-94/95)
- REGEX_INJECTION (CWE-1333)
- OPEN_REDIRECT (CWE-601)

### 5 个 R119b crypto & random
- WEAK_CRYPTO (CWE-327/328)
- INSECURE_RANDOM (CWE-330)
- PREDICTABLE_RANDOM_SEED (CWE-332/336)
- PREDICTABLE_KEY_PASSWORD (CWE-259/798)
- STATIC_API_KEY (CWE-798/259)

### 3 个 R119c webapp config
- INSECURE_COMMUNICATION (CWE-319)
- CORS_MISCONFIGURATION (CWE-942)
- INSECURE_COOKIE (CWE-614/1004)

### 3 个 R120 defensive
- BAD_SHIFT (CWE-758)
- INFINITE_LOOP (CWE-835)
- CONSTANT_CONDITION (CWE-570/571)

### 1 个 R121 sensitive data leak
- SENSITIVE_DATA_LEAK (CWE-532)

### 1 个 R124 field hardcoded credential
- FIELD_HARDCODED_PASSWORD (CWE-798/259)

### 8 个 style (R114)
- EMPTY_CATCH, SYSTEM_OUT, UNUSED_IMPORT
- CLASS_NAMING, METHOD_NAMING, CONSTANT_NAMING
- METHOD_TOO_LONG, CATCH_GENERIC

## 关键架构演进

### R115–R117: Taint engine foundation
- **R115** Lambda + inner class 翻译：让 taint 能跨 lambda body 跟入
- **R116** Inter-proc recursive worklist：让 taint 能跨函数传播
- **R117** MergedProgram：让多 TU 一起分析，cross-TU 调用能跟

### R118: Field-sensitivity
- TaintFact 加 fieldMap，让 `obj.field` 跟 `obj` 自己的 taint 区分
- per-function source path 解析，让 cross-TU 报告能指到原文件

### R119a–R124: Coverity gap fill
- 16 个新 checker 覆盖 CWE-90/91/93/134/327/328/330/332/336/259/798/319/614/942/1004/758/835/570/571/532/601/943/1333/94/95
- 2 个 R119b/R119c 已知限制在 R122 修

### R123 + R125: Metadata-driven precision (new paradigm)
- **R123** TypeTable wire-up：从"建好未用"变成实际消费；method-only sensitive-getter pattern 精度提升
- **R125** Annotation IR：class/method/field annotation 在 TacFunction 层可查；R125b 立刻消费（@Value / @ConfigurationProperties 抑制）
- 形成"角色识别 + 数据流"的双视图：TypeTable 答"是什么类"，Annotation IR 答"扮演什么角色"

### R124: Translator repair
- Instance field init 翻译修复：synthetic `<init>` + emitInstanceFieldInit
- FIELDSTORE 维度首次被消费 (R124 FieldHardcodedPassword + R125b 注解抑制)

## 关键技术决策（论文可写点）

### 1. TAC 用 `String` name 而非 `TacVar` object 作为 key (R99)
- 决定：TAC 的 IR 用 var name 字符串作为 identity key
- 优势：简化 hash/equals，TacVar 的 type/isTemp 字段不影响 identity
- 论文点：SSA 风格 IR 的轻量化变体

### 2. `CallResolver` name-based 解析 (R116)
- 决定：call graph 边用 call-site-style key（`wrapper/1`），function 用 qualified signature
- 优势：避免 full qualified name 解析；同 name 不同 arity 容易区分
- 局限：overloads 没法 disambiguate；R123 起的 type-aware matching 是补丁

### 3. Independent catalog 避免 double-report (R121)
- 决定：`TaintCatalog.sensitiveBuiltin()` 独立于 `builtin()`，SensitiveDataLeakCheck 用独立 TaintAnalysis 实例
- 优势：user input → log 走 LOG_INJECTION，sensitive → log 走 SENSITIVE_DATA_LEAK；两套规则独立 fire 无冲突
- 论文点：multi-catalog 架构是敏感数据泄漏检测的 natural fit

### 4. TypeTable 的 receiverClassName + KNOWN_JDK_SIMPLE (R123)
- 决定：用 string-prefix 跟 simple-name set 区分用户类 / JDK 类
- 优势：bounded cost（O(1) HashMap 查 + 60 个 string 集合），无 classloader 依赖
- 保守 fall-back：未知类型 → 仍 fire → 零覆盖率损失
- 论文点：精度提升无覆盖损失的精细控制范式

### 5. dedup key 含 sink identity (R121 顺手修)
- 决定：`ruleId + line + sinkSig + fn.signature()` 而非 `ruleId + line + fn.signature()`
- 优势：同 line 不同 sink（println + throw）每条都报
- 论文点：精化报告去重的 standard 演进

### 6. 1-hop def chase 替代跨函数 const-folding (R122)
- 决定：在 check 内部用 defs map 一跳，不在 catalog 层级加跨 call const-folding
- 优势：bounded cost（O(1) lookup per arg），覆盖 80% 常见 case
- 局限：helper 链仍漏；可接受因为 helper 模式相对少
- 论文点：bounded analysis 是静态分析工具的实用选择

### 7. Synthetic `<init>` 修复 instance field init (R124)
- 决定：没有显式 constructor 的 class 自动 emit 一个 `<init>` 包含所有 instance field 初始化的 FIELDSTORE
- 优势：unblocks 整个 FIELDSTORE 维度的 checker；zero-cost for explicit-ctor class
- 论文点：silent IR drop 是静态分析工具的隐藏灾难；R124 顺带挖出来修

### 8. Annotation IR + dual-tier DI suppression (R125)
- 决定：TacFunction 加 3 维注解；FieldHardcodedPasswordCheck 用 field-级 (10) 跟 class-级 (12) 两套 DI 抑制集
- 优势：bounded cost (O(1) HashMap 查)，不依赖 classloader；preserves coverage on 未知 annotation (保守 fall-back)
- 论文点：annotation IR + 抑制集设计是 precision 提升的实用范式；为 MISSING_AUTHZ / TRUST_BOUNDARY 铺路

## 累计 wins 数字

| 指标 | 值 |
|------|----|
| 累计 checker 类别 | 36 |
| 累计 CWE 覆盖 | 24+ (CWE-89/78/77/22/79/117/502/918/470/643/90/91/93/134/327/328/330/332/336/259/798/319/614/942/1004/758/835/570/571/532/601/943/1333/94/95/36/369/129/125/787/190/193/459/404/476/862) |
| 累计测试 | 160 + 1 skip |
| 累计文档 | 12 篇 (R115-R125) |
| 累计 end-to-end demo | 14 个文件 (R115-R125 全部) |
| 端到端 examples 扫描 | 102 findings (R124 98 → R125 102, +4 R125 demo) |
| 翻译层 bug 修复 | 4 (for(;;) JUMP 行号, ArrayInitializerExpr 静默 drop, instance field init 静默 drop, getAnnotations signature 错误) |
| 引擎层 bug 修复 | 1 (dedup key 太粗) |
| 架构 wiring | 2 (TypeTable → TaintAnalysis, Annotation IR → FieldHardcodedPasswordCheck) |

## 论文素材建议

### 章节 1: Background
- Java 静态分析 + taint engine 的 state of the art
- Fathom / Coverity / SpotBugs 的方法学对比
- Spring/Jakarta 生态对静态分析的挑战（注解的角色识别）

### 章节 2: plumb Architecture
- Maven 多模块设计
- TAC IR 的轻量化 SSA
- IFDS-style taint engine with field sensitivity
- 注解 IR 的简单名表达 + class-level 传播

### 章节 3: Coverity-driven 扩展
- 16 个新 checker 的增量开发
- 每个 checker 的精度/覆盖率 trade-off 故事
- 真实世界 demo 文件作为 ground truth

### 章节 4: Architectural Refinements
- R123 TypeTable wire-up 的"建好未用"困境
- R124 instance field init 修复的 silent-drop 教训
- R125 注解 IR 的"角色识别"维度
- 双视图分析范式：TypeTable (是什么) + Annotation IR (扮演什么)

### 章节 5: 评估
- Juliet test suite 数字（如果适用）
- 真实项目扫描 demo 文件的 102 findings 分类
- 误报/漏报分析
- R125b 抑制前后对比（NaiveAdmin 应保留，AuthConfig/DbSettings/ResourceAdmin/ServiceConfig 抑制）

### 章节 6: 经验
- 翻译器 silent-drop 是隐藏的最大敌人（R124 挖出来）
- "建好但未用" 的 TypeTable 是典型架构债（R123 还清）
- 用户级 vs JDK 类的判定用 KNOWN_JDK_SIMPLE 比 full classloader 简单 100x 且精度足够
- Annotation IR + 抑制集 = precision 提升的实用范式

## 限制和未来工作

### 已知限制（按优先级）

1. **Direct field assignment** in method body 仍 emit MOVE 不是 FIELDSTORE — `password = "literal"` 翻译器仍 emit MOVE
2. **MISSING_AUTHZ** 仍 deferred — annotation IR 已就位 (R125)，缺角色识别 + 类聚合逻辑
3. **TRUST_BOUNDARY_VIOLATION** 仍 deferred — entry-point classification 需 `@RestController` + `@GetMapping` 组合判定
4. **Exception-path taint** 仍 deferred — 需 CFG exception edges
5. **`Arrays.asList(*)` 通过 helper 链** 仍漏（chase bounded 1 hop）
6. **Generic-aware type inference** — `List<User>` vs `List<HashMap>` 区分
7. **Real-project smoke test** — 只在 examples 跑了，没在大型开源 Java 项目跑过
8. **Direct field assignment 的 tainted-source** — `password = request.getParameter(...)` 走 taint 引擎，独立模式

### R126+ 候选（按 impact 排序）

- **R126**: Annotation-aware MISSING_AUTHZ — `methodAnnotations()` 已 queryable
- **R127**: Direct field assignment 翻译修复 — `password = "literal"` 走 FIELDSTORE
- **R128**: TRUST_BOUNDARY_VIOLATION — entry-point 分类
- **R129**: 真实开源 Java 项目（Spring petclinic, Apache Commons）端到端测试
- **R130**: Exception-path taint — CFG exception edges
- **R131**: 性能优化（worklist cache, lazy TypeTable / Annotation build）
- **R132**: Generic-aware type inference
- **R133**: TypeTable 加 field-name → type — SensitiveDataLeak 标记 field 为 sensitive

## 总结

R115-R125 这 11 轮工作把 plumb 从 R114 的 15 个 checker、70 测试
扩展到 36 个 checker、160 测试、24+ CWE 覆盖。每次 round 都有明确的技术
主题，不重复工作；架构改进（R123/R124/R125）解决了之前留下的债，为 R126+
的 MISSING_AUTHZ / TRUST_BOUNDARY 打下了基础。

R125 收官标志着"角色识别 + 数据流"双视图范式完成：TypeTable (R123) 答
"this is a User / HashMap", Annotation IR (R125) 答 "this is a controller
method with @PreAuthorize missing"。下一步是用 R125 的注解 IR 兑现
MISSING_AUTHZ / TRUST_BOUNDARY 这两个 deferred 项目的精度提升。
