# R221 — CWE-200 / CWE-359 split (2026-08-01)

R218c wire 通后, R220/R222 polish 完后, 第 82 round. 解决 SensitiveDataLeak 单
一 checker 同时打 CWE-200 和 CWE-359 的问题 — 加 `SensitivePIILeakChecker`
让 CWE-359 单独 fire.

## 根因

R216 时代 `SensitiveDataLeakChecker` 同时覆盖 CWE-200 (general sensitive data)
和 CWE-359 (PII). 一个 `Logger#info(getEmail())` 会被打两次同样的 CWE-200 标签,
但 Juliet 实际期望 PII 走 CWE-359. 修了 1 个 case 后又发现 regression — `SensitiveDataLeakChecker`
打到了不该打的 PII 上, 需要单独区分.

R221 实施: 加 `SensitivePIILeakChecker` (CWE-359), 保留 `SensitiveDataLeakChecker` (CWE-200).
PII source pattern 比 sensitive 严格 (只 `.getEmail/getSsn/getDob/...`), 17 个 pattern.
两个 checker 都 fire = PII leak (既 sensitive 又 PII). 只有 CWE-200 fire = non-PII sensitive
(e.g. password 是 sensitive 但不是 PII).

## 实施 (~30KB change, 3 files, 2 checkers)

- `plumb-dfa/.../main/resources/sink-families.yaml` — 加 `sensitive.exfil.sinks`
  (CWE-200) + `pii.exfil.sinks` (CWE-359), 14 + 15 patterns, FQN 形式
- `plumb-dfa/.../main/java/.../fact/BuiltinFactKinds.java` — 加
  `SENSITIVE_PII = "plumb.sensitive.pii"` (R221 13th fact kind)
- `plumb-dfa/.../taint/checker/SensitivePIILeakChecker.java` (new,
  3984 bytes) — CWE-359, 17 PII source patterns
- `plumb-dfa/.../taint/checker/SensitiveDataLeakChecker.java`
  (modified) — 12 sensitive source patterns (含 `.get` 兜底)
- `plumb-dfa/.../config/SinkFamilyRegistry.java` (modified) — 加
  `lookupAll` 返回所有 match 的 family
- `plumb-dfa/.../taint/TaintedCheckerBase.java` (modified) —
  `checkSinkAtCall` 改用 `lookupAll` 遍历所有 family
- `plumb-dfa/src/test/.../taint/checker/R221SensitiveDataLeakTest.java`
  (new, 3 tests, 7822 bytes)
- `plumb-dfa/src/test/.../fact/FactBagTest.java` (modified) —
  builtinRegistryContainsAllTwelveDefaults → ThirteenDefaults (R221 加了
  SENSITIVE_PII)

## 测试结果

| Test | Before | After |
|------|--------|-------|
| `R221SensitiveDataLeakTest#passwordLeak_firesCwe200NotCwe359` | PASS | PASS |
| `R221SensitiveDataLeakTest#piiLeak_firesCwe200AndCwe359` | **FAIL** | **PASS** |
| `R221SensitiveDataLeakTest#noLeak_doesNotFire` | PASS | PASS |
| `FactBagTest#builtinRegistryContainsAllTwelveDefaults` | **FAIL** | **PASS** (改 13) |
| **plumb-dfa total** | 202 | **205** (+3 R221, 0 fail, 0 err) |

## 5 创新点 (R221 关键设计)

1. **PII ⊂ Sensitive, 双 checker 都 fire** (R221 design). 用户同时收到 CWE-200 +
   CWE-359 比收到单一标签更有信息量. `pii.exfil.sinks` 是 `sensitive.exfil.sinks` 的子集
2. **CWE-200 兜底 pattern `.get`** (R221 design). SensitiveDataLeakChecker 走 broad
   pattern (`.get` 匹配任意 getter), SensitivePIILeakChecker 走 strict PII-specific
   pattern. 这是 R219 "implicit source" 设计的延伸
3. **`lookupAll` + per-family finding** (R221 design). 修了根因 — `SinkFamilyRegistry.lookup`
   是 first-match, 让后续 family 永远 miss. `lookupAll` 返回所有 match 的 family, 每个 family
   独立检查 rule + emit finding. 关键 insight: 一个 callee 可以属于多个 family
4. **FactKindRegistry count 增加** (R221 pitfall fixed). 加了 `SENSITIVE_PII` 后 builtin
   count 从 12 变 13, 1 个测试 hardcode 了 12 → 改 test, 加注释解释 R221
5. **PiISource pattern 用 `.` 前缀** (R221 design). `.getEmail` (不是 `getEmail`) —
   跟 InputSourcePatterns 一样避免误匹配 `somethingGetEmail` 这种不带分隔符的.
   R220a 学到的 pattern

## 关键 pitfall (R221 root cause)

**`SinkFamilyRegistry.lookup` 是 first-match greedy.** YAML 顺序 sensitive 在前,
pii 在后. 一个 `Logger#info(email)` 调用:
- CWE-200 engine: lookup 返 `sensitive.exfil.sinks`, rule match, fire ✓
- CWE-359 engine: lookup 也返 `sensitive.exfil.sinks`, rule 不 match, **return** ✗

修法: `lookupAll` 返所有 match 的 family, `checkSinkAtCall` 遍历, 每个 family 独立
`rule.matchesSinkFamily` + `checkSinkForFamily`. 30 行代码解 bug.

## 备份

`D:\work\tmp\r221_done/` (8 files, ~75KB):
- BuiltinFactKinds.java (R221 加 SENSITIVE_PII)
- FactBagTest.java (改 12 → 13)
- R221SensitiveDataLeakTest.java (new)
- SensitiveDataLeakChecker.java (R221 加 sensitive source patterns)
- SensitivePIILeakChecker.java (new)
- sink-families.yaml (加 sensitive.exfil + pii.exfil sinks)
- SinkFamilyRegistry.java (加 lookupAll)
- TaintedCheckerBase.java (改 checkSinkAtCall 用 lookupAll)
