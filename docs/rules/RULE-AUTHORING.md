# 规则开发：AI prompt / CWE 标签 / 外部工具 ID / 加新规则

本文档是 `RULES.md` 的 §10-§13 拆分版本。规则主清单见 `RULES.md`；Style 规则操作（590 条 / 启用禁用 / 改参数）见 `RULE-STYLE-OP.md`。

## 1. AI augmentation 与每条规则的 prompt

R-? Step 7 起，每条 `DfaChecker` / `AstChecker` 自己持有 `getAiPrompt(Finding) -> String`：

- 返回 `null` = 该规则**不走** AI dedup
- 返回非 null = LLM 收到这条规则的 prompt + finding 详情 + dataflow trace + 源码窗口，输出 TP / FP / UNKNOWN

prompt 默认由 checker 提供。手写 override 见：
- 18 个 `DfaChecker` + `TaintedCheckerBase`（auto-generated from `TaintRule` 元数据）
- `plumb-style/src/main/java/org/plumb/style/security/HardcodedCredentialsCheck.java`
- `plumb-style/src/main/java/org/plumb/style/error/EmptyCatchBlockCheck.java`

用户的 `--rule-prompts-dir` 仍可 override（`<rule-name>.md` 文件覆盖对应 checker 的 inline `getAiPrompt` 返回值）。

**三级覆盖链**：
1. `TaintedCheckerBase` 自动从 `TaintRule` 元数据生成（ruleId / cwe / severity / source / sink / domain）
2. DfaChecker / AstChecker 手写 inline `getAiPrompt`（少数高价值规则）
3. `--rule-prompts-dir` 用户 `.md` 覆盖（最优先）

详见 `docs/usage/CLI.md` §2.2。

## 2. CWE 标签与 mapping 一致性

`juliet-coverage/data/plumb_rule_to_cwe.json`（**367** 条）是 rule → CWE 的映射，用于：
- Juliet 评估的 cwe_matched eval 路径
- NDJSON 输出里 `cwe` 字段填充

**维护原则**：这个文件是**人工维护**的，仓库里没有自动 rebuild 脚本。key 有两种
形态都能被 `ScanRunner.isRuleIdAllowed` 匹配：
- 规则短名（大写下划线），例如 `ABSENT_CODEBLOCK_IN_CASE`
- emit-id 字符串，例如 `ast-style-empty-catch-style`

改 emit-id 字符串时**必须**同步更新这个 mapping —— 漏改会让 Juliet 的
`cwe_matched` 评估路径静默跳过对应 finding。

## 3. 与外部工具的等价 ID

每条 `RuleDef.aliases` 字段携带 PMD / SpotBugs / SonarQube / Coverity 的对应 ID。详细映射见 `docs/plumb-style-rules.md` 的每条规则详细条目。

举例：

| Plumb | PMD | SpotBugs | SonarQube | Coverity |
| --- | --- | --- | --- | --- |
| `STYLE_EMPTY_CATCH` | `EmptyCatchBlock` | `REC_CATCH_EXCEPTION` | `java:S2142` | `EXCPT_RETHROWN` |
| `WEAK_CRYPTO` | `WeakCryptographicAlgorithm` | `WEAK_MESSAGE_DIGEST` | `java:S3271` | `WEAK_CRYPTO_ALGORITHM` |
| `STATIC_API_KEY` | `HardcodedCredential` | `HARD_CODE_KEY` | `java:S6411` | `HARDCODED_CREDENTIAL` |

完整表由 `scripts/gen_rules_doc.py` 生成在 `docs/plumb-style-rules.md`。

**全方位对比**（实现机制 / CWE 覆盖 / Juliet prec / 误报率 / 性能 / AI 集成 / Plumb 定位）见 [`RULE-TOOL-COMPARISON.md`](./RULE-TOOL-COMPARISON.md)。

## 4. 添加新规则的步骤

### 13.0 Pattern 选型决策（Style 规则前置步骤）

**写新 Style checker 之前先选 Pattern**。AstDispatcher 对 43 种节点各 `visit(X, Void)` 一次，单 checker 内部多次 `findAll` / `walk` 会导致每文件被 walk 多次。Pattern 选型决策表（R-815）：

```
[新 AST checker 写之前]
   ↓
[1. 我要处理什么节点？]
   ↓
   ├── 多种类型无关节点？(e.g. "BinaryExpr 上的 if/else 分支")
   │     ↓
   │   1.a 每个类型一个 visit(X, ctx) 重载 → Pattern D
   │      (AstDispatcher 单次 walk, 不写 visit(CU))
   │      例子: ObjectHijackCheck.visit(ClassOrInterfaceDeclaration, ctx)
   │
   └── 只关心一种节点 (e.g. 只看 MethodCallExpr)
         ↓
      2. 用 per-node visit(X, ctx) → Pattern D
         (AstDispatcher walk 时只在该节点类型命中时触发)
         例子: EnvVarCheck.visit(MethodCallExpr, ctx)
      │
      └── 2.b 需要 file-level 上下文 (Imports / Comments / 跨节点关系)
            ↓
         2.b.1 在 visit(CU) 里手工一次 walk + instanceof 分发 → Pattern A 改良
         2.b.2 如果是 [Import + Class + AnnotationExpr] 等少量明确类型
               → 用 n.findAll 多次, 但**保证 fA ≤ 2**
               → Pattern A (可接受)
         2.b.3 都不行, 才允许 Pattern C (n.walk 多次)
```

**选型红线**（强制约束）：

- ❌ **禁止** Pattern B（`visit(CU) + n.findAll(A) + n.findAll(B) + ...` 3+ 次）
- ❌ **禁止** 循环里嵌 walk（`for (X : n.findAll(A)) { n.walk(B, ...) }`）
- ❌ **禁止** Pattern D+（visit(CU) + per-node visit 同时存在）
- ⚠️ **限制** Pattern A 最多 2 次 findAll；超过必须降级到 Pattern D
- ✅ **优先** Pattern D（per-node visit 重载，AstDispatcher 单次 walk）

**当前 plumb-style checker 的 walk 模式评价**：

| Pattern | 评价 |
| --- | --- |
| A（单次 findAll） | ✓ 已最优 |
| B（多次 findAll） | ⚠️ 反模式，改成 A 或 D |
| C（多次 walk） | ⚠️ 反模式，改成 A 或 D |
| D（per-node visit 重载） | ✓ 推荐，AstDispatcher 单次 walk |
| D+（per-node + CU 同时存在） | ⚠️ 二选一 |
| E / E0（不属上述分类） | 需单独确认 |

**改造方向**：B / C 是主要优化目标，改为 A 或 D 可把每文件的 walk 数降下来。定量盘点脚本
不随文档保留，需要时直接扫 `plumb-style/src/main/java` 下各 `*Check.java` 的
`n.walk(` / `n.findAll(` 调用次数重新统计。

### 13.1 加新 Style 规则

1. 在 `plumb-style/src/main/java/org/plumb/style/<category>/FooCheck.java` 写规则，实现 `AstChecker` 接口。
2. 在 `CheckerCategories.java` 加 `m.put("FooCheck", "<category>");`（按字母序）。
3. 跑 `mvn -o -pl plumb-style install` rebuild。
4. `bin/sast-scan --list-rules | grep FooCheck` 验证。

⚠️ 第 2 步漏掉会**静默丢失**：`AstCheckerRegistry.byCategory(...)` 找不到规则，
不 emit 也不报错。（注意 `AstCheckerRegistry.builtins()` 走 classpath 扫描，
不依赖 `CheckerCategories`；漏登记影响的是按分类加载的路径。）

### 13.2 加新 DFA Unified 规则

1. 在 `plumb-dfa/src/main/java/org/plumb/dfa/checkers/<family>/FooChecker.java` 写规则，实现 `DfaChecker` 接口（或继承 `TaintedCheckerBase`）。
2. 在 `ScanRunner.buildDfaCheckers()` 加一行 `checkers.add(new org.plumb.dfa.checkers.<family>.FooChecker());`，并挂上对应的 YAML flag。
3. 在 `plumb-dfa/src/main/resources/checkers.yaml` 加一条 checker 条目（`enabled` 决定默认开关）。
4. 在 `juliet-coverage/data/plumb_rule_to_cwe.json` 加 mapping（key 用规则短名或 emit-id，两者都能被匹配）。
5. 跑 `mvn -pl plumb-dfa install` rebuild。
6. `bin/sast-scan --list-rules | grep <rule-id>` 验证。

### 13.3 加新 Taint Rule

1. 在 `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintRuleRegistry.java` 加一条 `m.put("<rule-id>", TaintRule.of(...))`。
2. **在 `plumb-dfa/src/main/resources/sink-families.yaml` 注册 source / sink 模式**（不存在
   `TaintCatalog` 这个类——早期文档提到的它从未存在）。sanitizer 若不是某个 sink family 的
   固有属性，则在对应 checker 的 `isSanitizer` 里判定。
3. 在 `juliet-coverage/data/plumb_rule_to_cwe.json` 加 mapping。
4. rebuild + verify。