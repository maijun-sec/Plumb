# wire 协议 v2 设计（6 个 sidecar → Java 引擎）

> 配套：`01-TAC-IR-扩展设计.md`（IR 提案 IR-A..IR-Q）、`02-覆盖矩阵与不可支持项.md`（覆盖矩阵）。
> 证据基线：仓库 `D:\work\workspace\idea\engine\Plumb`，HEAD = `2c15f0e`。

---

## 0. 现状：协议长什么样，哪里断了

### 0.1 谁走共享 mapper，谁不走

| sidecar | 消费方 | 说明 |
|---|---|---|
| TS | `SidecarTacMapper.java` | 共享 |
| ArkTS | `SidecarTacMapper.java` | 共享 |
| PHP | `SidecarTacMapper.java` | 共享 |
| Python | `SidecarTacMapper.java` | 共享 |
| Go | `SidecarTacMapper.java` | 共享 |
| **C#** | **`CSharpTacFrontend.java` 自己的 `toInstruction`/`toFunction`** | **不走共享 mapper** |

⇒ **协议改动要落两处**：共享 mapper（5 个 sidecar）+ C# 的独立路径。
**这两条路径必须同步演进**，否则 C# 会持续漂移——它是今天已经在发生的漂移
（C# 已经发 `returnType`，共享路径上的 TS/ArkTS 不发）。

### 0.2 今天协议上「有形状但没内容」的地方

这是审计 P0-3 / P0-6 的根：**IR 侧早就有这些槽位，wire 上从来没填过。**

| IR 槽位 | wire 上的现状 | 证据 |
|---|---|---|
| `TacFunction.tryRegions` | **硬编码 `List.of()`** | `SidecarTacMapper.java:150` |
| `classAnnotations` / `methodAnnotations` / `fieldAnnotations` / `paramAnnotations` | **硬编码 `List.of(), List.of(), Map.of(), Map.of()`** | `SidecarTacMapper.java:144-147` |
| `annotationValues` | **硬编码 `Map.of()`** | `SidecarTacMapper.java:154` |
| `TacFunction.returnType` | TS/ArkTS 发 `returnType: undefined` ⇒ JSON 里**键不存在** ⇒ 映射成 `TacType.UNKNOWN` | `ts-sidecar/src/translator.mjs:521`、`arkts-sidecar/src/translator.mjs:550`；消费 `SidecarTacMapper.java:140`、`:282-285` |
| `TacInstruction.effects` | mapper **会读** `effect`，但**没有任何 sidecar 发过** | `SidecarTacMapper.java:184-193` |
| `TacInstruction.fieldName` | mapper 会读，但**没有 sidecar 发过**（TS/ArkTS 完全没有 `FIELDLOAD`/`FIELDSTORE`） | `SidecarTacMapper.java:177` |
| `castType` | mapper 会读，但没有 sidecar 发过 | `SidecarTacMapper.java:211-214` |
| `TacFunction.synthetic` | TS 侧**硬编码 `false`**；ArkTS 发真值 | `ts-sidecar/src/translator.mjs:528` |

⇒ **协议缺的不是「字段」，是「填充」**。这意味着 wire v2 的第一优先级是
**把已有 IR 槽位接上**，而不是发明新形状。

### 0.3 envelope 与版本

TS 侧车响应（`ts-sidecar/src/main.mjs:213-219`）：

```js
return { ...stamp(), op: OP.TRANSLATE, ok: true, diagnostics: [], functions: result.functions };
```

ArkTS（`arkts-sidecar/src/main.mjs:195-201`）多一个 `problems`：

```js
return { ...stamp(), op: OP.TRANSLATE, ok: true, problems, functions: result.functions };
```

`stamp()` 给出 `protocolVersion`（int, 1）与 `irVersion`（int, 1）
（`ts-sidecar/src/protocol.mjs:120-122`），Java 侧由 `SidecarProtocol.java`（130 行）做握手。

**已有的版本号是好事**——但它现在只用于「不兼容就拒绝」，
**不能用于「老 sidecar + 新引擎」的字段级协商**。
审计已经证明了这个缺口的后果：TS 发 `returnType: undefined`，Java 侧
**静默当成 UNKNOWN**，没有任何一方察觉。

---

## 1. wire v2 的设计原则

1. **只加不删**：v2 的所有新字段都是**可选**的；老 sidecar + 新引擎、新 sidecar + 老引擎都必须能工作。
2. **能力协商**：响应里带一个 `capabilities` 数组，**引擎据此决定读哪些字段**，
   而不是「看字段在不在」（「看字段在不在」正是今天 `returnType` 失效的原因）。
3. **一个字段一个语义**：不把「丢弃了」和「近似了」塞进同一个 `problems` 数组。
4. **sidecar 之间不共享 AST 代码，但共享**：
   - **协议字段表**（本文件 §2 是唯一权威）；
   - **构念 ID 词表**（§4）——两端用同一批字符串描述「丢了什么」，这样报告可跨语言聚合。

---

## 2. 协议字段表（v2）

### 2.1 envelope

```jsonc
{
  "protocolVersion": 2,        // int
  "irVersion": 2,              // int
  "op": "translate",
  "ok": true,
  "capabilities": ["drops", "guards", "tryRegions", "annotations", "domains", "labels", "types"],
  "problems": [],              // 文件级问题（所有 sidecar 统一发；TS 现在发的是恒空的 diagnostics）
  "functions": [],
  "programDrops": [],          // 文件级丢弃（没有对应函数的丢弃：整类成员被跳过等）
  "types": []                  // TacTypeDecl 数组
}
```

**变更点**：
- `protocolVersion`/`irVersion` 升到 2，但**引擎必须同时接受 1**（见 §3 的协商规则）。
- **`diagnostics` 字段取消**：它恒为空且只有 TS 在发（`ts-sidecar/src/main.mjs:217`），
  统一用 `problems`（ArkTS 已经在用，`arkts-sidecar/src/main.mjs:199`）。
  **老引擎读 `diagnostics` 不会挂**（它读到的是空数组，等价于今天）。
- 新增 `capabilities` / `programDrops` / `types`。

### 2.2 函数记录

```jsonc
{
  "name": "…",
  "signature": "owner.name(ParamTypes):Return",
  "enclosingClass": "…",            // 可选
  "returnType": { "kind": "STRING" }, // v2：必须是**类型对象**，不能是 undefined
  "precision": "SOURCE_FULL",         // SOURCE_FULL | SOURCE_LOWERED | SOURCE_DEGRADED
  "synthetic": false,
  "memberKind": "METHOD",             // 新增
  "params": [], "locals": [],
  "instructions": [],
  "problems": [],                     // 保留：解析/翻译错误（不是「丢弃」）
  "drops": [],                        // 新增：这个函数丢了什么
  "tryRegions": [],                   // 新增：异常区域结构
  "classAnnotations": [],             // 新增（IR 早已有槽位）
  "methodAnnotations": [],
  "fieldAnnotations": {},
  "paramAnnotations": {},
  "annotationValues": {}
}
```

**`returnType` 的修正**：`undefined` 在 `JSON.stringify` 里会让**键整个消失**
（不是变成 `null`）⇒ Java 侧 `wire.getObject("returnType")` 返回 `null` ⇒ `toType(null)` ⇒
`TacType.UNKNOWN`（`SidecarTacMapper.java:282-285`）。
⇒ **v2 规定：类型字段要么给类型对象，要么给 `{"kind":"UNKNOWN"}`，不给 `undefined`**。

**`precision` 的取值**：v2 增加 `SOURCE_DEGRADED`（IR-A）。
Java 侧映射改成「按 `startsWith("SOURCE_")` 判定 + 显式枚举」，避免加一档就要改一次。

### 2.3 指令记录

在今天 mapper 读的 17 个键
（`kind, dest, lhs1, lhs2, calleeSig, ctorType, fieldName, trueLabel, falseLabel, label, line, column, effect, binop, unop, castType, args`）
之外**新增 4 个**：

| 新键 | 类型 | 用在哪 | IR 落点 |
|---|---|---|---|
| `guard` | 守卫对象 | `BRANCH` | IR-C 的 `TacGuard`（`SidecarTacMapper.java` 新增一个 `toGuard`） |
| `labelRef` | `{name, kind, scopeId}` | `LABEL` / `JUMP` / `BRANCH` | IR-G 的 `TacLabelRef` |
| `ownerType` | 字符串（FQN） | `FIELDLOAD` / `FIELDSTORE` | IR-E 的 `ownerType` |
| `branchRegion` | 区域 id（字符串） | `BRANCH` / `JUMP` | IR-I 的 `TacBranchRegion`（区域本体放在函数级 `branchRegions`） |

**`ctorType` 的复用**：`THROW` 用 `ctorType` 表示**抛出类型**（IR-J）——
复用现有键，**零协议改动**。

**函数级新增 `branchRegions`**（与 `tryRegions` 并列）：

```jsonc
"branchRegions": [
  {
    "id": "R1",
    "kind": "SWITCH",
    "subject": { "kind": "VAR", "name": "x" },
    "cases": [
      { "id": "C1", "guards": [ {"lhs": {...}, "op": "EQ", "rhs": {...}} ],
        "bindings": [ {"name": "n", "type": {...} } ],
        "bodyLabel": "L_CASE_1", "nextLabel": "L_CASE_2" }
    ],
    "defaultCaseId": "C3",
    "fallThrough": true,
    "exhaustive": false,
    "missingCases": [],
    "scopeId": "f1"
  }
]
```

### 2.4 操作数与类型

**操作数**：沿用今天的形状（`VAR` / `CONST`），**不加变体**。

**类型记录**新增三个可选字段（IR-K / IR-D）：

```jsonc
{ "kind": "CLASS", "name": "java.util.List",
  "typeArguments": [ {"kind":"STRING"} ],   // 泛型实参
  "unionMembers": [ {"kind":"STRING"}, {"kind":"NULL"} ],  // 联合类型
  "bound": {"kind":"CLASS","name":"java.lang.Number"},     // 类型变量的上界
  "domain": { "domain": "INTEGRAL", "bits": 32, "signed": true }  // 数值域
}
```

**关键约束（v2 硬性）**：
> **禁止**用字符串拼接制造类型名（今天 Java 的 `"A|B"`、Scala 的 `"A&B"` 就是这么来的）。
> 联合类型用 `unionMembers`，类型变量用 `bound`（且 `name` 置 null），泛型用 `typeArguments`。

### 2.5 丢弃记录（drops）

```jsonc
"drops": [
  { "construct": "switch.fallthrough",
    "detail": "the default clause is not last in source order",
    "line": 42, "column": 5,
    "impact": "SEMANTICS",          // STRUCTURE | SEMANTICS | VALUE
    "scope": "MyClass.myMethod" }
]
```

**`impact` 的三档语义**（引擎据此推导精度，**前端不许自报精度**）：

| impact | 含义 | 精度后果 |
|---|---|---|
| `STRUCTURE` | 语义保留，形状没了（如 Scala `match` 退成分支链） | 函数 ⇒ `SOURCE_LOWERED` |
| `SEMANTICS` | 语义真的丢了 | 函数 ⇒ `SOURCE_DEGRADED` |
| `VALUE` | 单个值未知（该值降级成 `HAVOC`） | 函数仍 `SOURCE_FULL` |

**并再次强调**（见 `01` 的立场）：`drops` **不是兜底通道**。
能完整表达的构念**不许**产生 drop。sidecar 遇到不认识的节点，
**正确做法是把它降级出来**；只有在**确实无法静态表达**时才记一条 `SEMANTICS` drop。

### 2.6 抛错的形状（不是新增字段，是「值必须给全」）

今天 sidecar 的常量 `value` 是**可选**的（`protocol.mjs` 的 `constOp`：`if (value !== undefined) op.value = value`）。
⇒ **常量可能没有值**，Java 侧拿到 `null`。

v2 规定：**`CONST` 记录必须有 `value` 键**（可以是 `null`，表示 null 常量）。
缺值就是协议违例，引擎应当**报错**而不是当成 null。

**理由**：`CONST` 的 `value` 缺失与「值为 null」在今天是**同一件事**，这让「常量值未知」无法表达。
（这正是 Java 侧 `translateUnary` 未知算子返回 `Const.ofNull()` 的隐患来源。）

---

## 3. 版本与能力协商

### 3.1 协商规则

```
引擎启动时读 sidecar 的 capabilities 数组 → 建立「本次运行支持哪些字段」的能力集
每个字段的读取都走能力集，而不是「看 JSON 里有没有这个键」
```

**为什么必须这样**：今天 TS 发 `returnType: undefined` ⇒ 键消失 ⇒ 引擎静默当成 UNKNOWN。
「看键在不在」与「字段值为空」这两件事在 JSON 里不可区分 ⇒ **必须由发送方显式声明能力**。

### 3.2 兼容矩阵

| 组合 | 行为 |
|---|---|
| 老 sidecar（v1）+ 新引擎 | 引擎按能力集 = 空处理新字段 ⇒ 行为**与今天完全一致**（零回归） |
| 新 sidecar（v2）+ 老引擎 | 老引擎不读新键 ⇒ 行为**与今天完全一致**（老引擎读 `tryRegions` 是硬编码 `List.of()`，读 `annotations` 也是硬编码 ⇒ 天然忽略） |
| 新 + 新 | 全量能力 |

⇒ **协议升级本身零回归**。这一点很重要：它让「先升级协议、再逐语言填字段」成为可能。

### 3.3 每条能力对应的 IR 提案

| capability | IR 提案 | 引擎侧读取点 |
|---|---|---|
| `drops` | IR-B | `ModelCoverageComputer.java:133` 附近扩展 |
| `guards` | IR-C | `GuardStore.java:115` 的写入侧新增 CFG 来源 |
| `tryRegions` | IR-F | `TacCfgBuilder.java`（异常边） |
| `annotations` | 既有 IR 槽位 | `TacFunction.classAnnotations()` 等四个访问器 |
| `domains` | IR-D | `checkers/boundary/Range.java:133-143`（`Range.ofType` 读它） |
| `labels` | IR-G | `TacCfgBuilder` 的目标校验 |
| `types` | IR-E / IR-N | 新增消费点 |
| `regions` | IR-I | 新增消费点（穷尽性检查） |

---

## 4. 构念 ID 词表（跨语言共享）

sidecar 报「丢了什么」时用**同一批字符串**，这样报告才能跨语言聚合。
词表的**权威定义在协议文档里**（`PROTOCOL.md`），由一个脚本校验两端的词表一致。

| 词条 | 含义 | 典型出现处 |
|---|---|---|
| `decl.member.unsupported` | 某类成员无法降级 | 声明面兜底 |
| `decl.field.noInitializer` | 无初始化器的字段（读不可知） | TS/ArkTS/Python |
| `decl.property.accessor` | 自定义访问器（需 getter/setter 语义） | Kotlin/C# |
| `ctrl.shortcircuit.approximated` | 短路被近似成普通二元运算 | 全部语言 |
| `ctrl.switch.defaultOrder` | `default` 不在末尾导致后续 case 不可达 | Java/ArkTS |
| `ctrl.switch.multiLabel` | 多标签 arm 只有第一个标签被测 | Java |
| `ctrl.switch.fallthrough` | fall-through 未建模 | TS/ArkTS |
| `ctrl.loop.else` | 循环 `else` 语义 | Python |
| `ctrl.jump.label` | 带标签的 break/continue | Java/Kotlin/Scala |
| `ctrl.try.finallyExit` | finally 的某个出口没有边 | Java |
| `ctrl.match.nonExhaustive` | 匹配非穷尽 | Scala/Python/Kotlin |
| `ctrl.match.patternBinding` | 模式绑定未建立 | Scala/Go/Python |
| `exc.handler.unreachable` | catch/except 不可达 | PHP/Python |
| `exc.resource.closePath` | 资源关闭只覆盖部分路径 | Java/C#/Python |
| `type.erased.generic` | 泛型实参被擦除 | 全部语言 |
| `type.fakeUnionName` | 联合类型被拼成假类名 | Java/Scala |
| `type.nullable.collapsed` | 可空性被塌陷 | TS/ArkTS |
| `value.dynamic` | 运行期才确定的类型 | TS/Python/PHP |
| `meta.decorator.unread` | 装饰器/特性未被读取 | TS/ArkTS/C#/PHP/Go/Python |

**词表的纪律**：
- 一个词条只描述**一件事**；
- 词条带 `impact`（三档）；
- **门禁**：`scripts/` 下一个脚本比对「sidecar 实际发出的词条」与「词表」，
  词表里没有的词条 ⇒ 门禁红（防止词表腐烂）。

---

## 5. 各 sidecar 的具体改动

### 5.1 共享路径（TS / ArkTS / PHP / Python / Go）

| 动作 | 内容 | 谁 |
|---|---|---|
| 协议常量 | `protocol.mjs` 增加 `DROP_IMPACT`、`GUARD`、`LABEL_KIND`、`MEMBER_KIND`、`DOMAIN` | 各 sidecar 的 `protocol.mjs`（**目前是两份独立副本**，见 §5.3） |
| 响应 envelope | 加 `capabilities` / `programDrops` / `types`；`diagnostics` 取消 | 各 `main.mjs` |
| 函数记录 | 加 `memberKind` / `drops` / `tryRegions` / 五个注解通道；**`returnType` 必须给对象** | 各 `translator` |
| 指令记录 | 加 `guard` / `labelRef` / `ownerType` / `branchRegion` | 各 `translator` |
| 类型记录 | 加 `typeArguments` / `unionMembers` / `bound` / `domain` | 各 `translator` |
| 共享 mapper | 读新字段；**把硬编码的 `List.of()` / `Map.of()` 换成「能力集决定」** | `SidecarTacMapper.java:140-155` |
| 精度映射 | 支持 `SOURCE_DEGRADED` | `SidecarTacMapper.java:132-135` |
| `CONST` 值 | 必须给 `value` 键 | `protocol.mjs` 的 `constOp` |

### 5.2 C# 的独立路径

C# 前端有自己的 `toInstruction`/`toFunction` ⇒ **同样的字段，一套独立的实现**。
⇒ **纪律**：C# 的 wire 字段名**必须**与共享路径逐字一致。
**门禁**：一个脚本比对「共享 mapper 读的键集合」与「C# 前端读的键集合」，
不一致就红。这条门禁能挡住 C# 的漂移。

### 5.3 TS 与 ArkTS 的特殊问题

两个 TS 系 sidecar 的 `protocol.mjs` 是**两份独立副本**（内容几乎相同，只是注释不同），
而它们的 AST API 形状不同（`typescript/unstable/ast` 的具名导入 vs `ohos-typescript` 的 `ts.isXxx`）。

⇒ **不改这个结构**（合并要处理两套编译器 API，代价大），但**必须**：
- 把**协议字段表**抽成一份**机器可读**的定义（JSON/YAML），
  由脚本生成/校验两份 `protocol.mjs` ⇒ 词表与常量不腐烂；
- **构念 ID 词表**（§4）由同一个文件驱动。

---

## 6. 实施顺序（wire 侧）

| 步 | 做什么 | 为什么先做 | 验收 |
|---|---|---|---|
| **W1** | 协议字段表机器可读化 + `capabilities` 协商 + `CONST.value` 必须存在 | 这是**其它一切的前置**：没有协商，填字段就会静默失效 | 老 sidecar + 新引擎、新 sidecar + 老引擎，行为与今天逐条一致 |
| **W2** | 共享 mapper 接上已有的 IR 槽位（`tryRegions` / 五个注解通道 / `returnType`） | **协议字段早已存在**，只是没填 ⇒ 收益最大、改动最小 | PHP catch / Python except 变得可达；C# 特性可见；TS/ArkTS 返回类型不再是 UNKNOWN |
| **W3** | `drops` + `precision` 三档 | 「丢掉什么」必须可见，否则「全部支持」无法证明 | 每个 sidecar 的未识别节点都有 drop 记录 |
| **W4** | `guard` / `labelRef` / `ownerType` / `branchRegion` | 控制流结构化 | 引擎自动推出的守卫数 > 0 |
| **W5** | `types` / `domain` / `typeArguments` / `unionMembers` | 类型维度 | 泛型与联合类型不再塌陷 |

**W2 的优先级被低估的风险提示**：它是**唯一一条「协议早就有、只差接线」**的路，
却一次性修好 6 个语言的三类丢失（异常流、注解/特性、返回类型）。
**建议 W2 排在所有前端改造之前。**

---

## 7. 风险

1. **协议升级本身零回归**（§3.2）——但这依赖「老引擎忽略新键」这件事在**每一个**字段上都成立。
   ⇒ 门禁：把新键逐个加进「老引擎忽略清单」，并写一个测试断言老引擎读新 wire 文件不报错。
2. **`capabilities` 是发送方自述** ⇒ 一个有 bug 的 sidecar 可能宣称自己有某能力却发不出内容。
   ⇒ 对策：**引擎侧对每个能力做「非空即异常」的自检**（例如宣称 `tryRegions` 却发空数组 ⇒ 记一条协议违例）。
3. **C# 的双路径**（§5.2）⇒ 靠门禁保同步，不要靠人记。
4. **两份 `protocol.mjs`**（§5.3）⇒ 靠机器可读字段表保同步。
