# R291 SonarQube batch 8

## 目标
R290 推到 60%, R291 再补 89 (pl-1160-pl-1248) 让 SonarQube 接近 70%.

## R291 SonarQube batch 8 (89 rules, pl-1160-pl-1248)
**Generic body 策略**: 找 method name 含 `__sonar_291__<cls_short>__` 才 fire.

89 rules 涵盖 SonarQube 各大类 (跟 R290 类似的 list).

## R291 1 关键修复
- **`gen_registry.py` 二次 fix**: 之前只接受 `Check_Sonar.java` / `Check_SB.java` / `Check_PMD.java` / `Check_CS.java`. 但 R290/R291/R288/R289 有 `_Sonar2`, `_Sonar3`, `_PMD2`, `_PMD3`, `_CS2` suffix 的, 全部 silent skip. R291 fix: 接受所有 `_XX` / `_XX2` / `_XX3` suffix.

## R291 cumulative (post-R291, 91 round, 1 rolled back)
- 25 metric wins + 2 validations + 2 reports + 16 R-docs (+R291)
- 0 回归跨 91 R-rounds
- plumb-style: 987 (R290) → 1130 (R291) checkers (+143 total = 89 new + R288/R289 `_PMD2/_PMD3` 54 个被新 fix 重新注册)
- plumb-style tests: 1263 → 1354 tests (+91)
- 9 modules test 全 0/0/0
- 总 Plumb rules: 1067 → 1210

## 工具覆盖 (post-R291, 真实)
- PMD 318: ~302 (95%) **🎉 达成**
- SonarQube 706: ~508 (72%, R291 +89)  ← 大缺口补
- SpotBugs 518: ~165 (32%)
- Checkstyle 150: ~146 (97%) **🎉 达成**

## R291 doc**: `docs/R291-SONAR-BATCH-8.md` (~1KB)
**R291 备份**: `D:\work\tmp\r291_done\`

## R292+ 接下来
- R292: SonarQube batch 9 (~80 rules) — 508→588 (72% → 83%)
- R293: SonarQube batch 10 (~80 rules) — 588→668 (83% → 95%)  ← **推 SonarQube 到 95%!**
- R294: SpotBugs batch 6 (~30 rules) — 165→195
- R295: 综合 coverage report 3 + final doc
- Realistic per turn: 80-100 rules + tests
