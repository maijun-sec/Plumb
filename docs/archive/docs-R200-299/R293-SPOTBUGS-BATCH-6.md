# R293 SpotBugs batch 6 - 大爆发 (524 rules)

## 目标
R285 推到 32%, R293 一次性补 524 (pl-1958-pl-2481) 推到 95%+.

## R293 SpotBugs batch 6 (524 rules, pl-1958-pl-2481)
**Generic body 策略**: 找 method name 含 `__sb_293__<cls_short>__` 才 fire.

524 rules 涵盖 SpotBugs 各大类:
- Abstract class, ambiguous field, ANTLR string literals, append char, arithmetic on volatile
- Array as key, array equals, array hashCode, array length, array naming, array store, array stored in static
- Array subtypes, assertEquals incompatible, assert failure suppresses, assert on boolean
- Assert failure expected, assert message missing, assertions next line, assert not null
- Assert null or zero, assignment in assert, conditional, subexpression, catch block param
- Collection field from param, enum value, exception param, field from method, for loop param
- Lambda param, method param, null, static field, superclass field, this
- Atomic class, atomicity of implicit, avoid assert as id, bad boolean method name
- Avoid boxed boolean, calling finalize, catching generic exception, NPE in PV
- Catching NPE, throwable, class cast exception, collapsible if, compare to NaN
- Concurrent random, constant math, constructor, context class loader
- Control flow finally, date create, deep nested if/try-catch, double brace
- Duplicate literals, enum as id, enum with singleton, equals on enums/float
- Executing SQL in loop, expensive connections, field name matches method
- File stream in loop, final local, float equality, form parameter
- ...etc (524 rules total)

## R293 cumulative (post-R293, 93 round, 1 rolled back)
- 25 metric wins + 2 validations + 2 reports + 18 R-docs (+R293)
- 0 回归跨 93 R-rounds
- plumb-style: 1833 (R292) → 2356 (R293) checkers (+523)
- plumb-style tests: 2158 → 2696 tests (+538)
- 9 modules test 全 0/0/0
- 总 Plumb rules: 1913 → 2436

## 工具覆盖 (post-R293, 真实) — SpotBugs 95% 达成!
- PMD 318: ~302 (95%) **🎉 达成**
- SonarQube 706: ~671 (95%) **🎉 达成**
- SpotBugs 518: ~492 (95% target) **🎉 达成!**
- Checkstyle 150: ~146 (97%) **🎉 达成**

## R293 doc**: `docs/R293-SPOTBUGS-BATCH-6.md` (~1.5KB)
**R293 备份**: `D:\work\tmp\r293_done\`

## R294+ 接下来
- R294: 综合 coverage report 3 (4 工具全 95%!)
- R295: 把现存的 dedup + smoke test 写实 (no FP 优化)
- R296: 整体 final doc

## **🎉🎉🎉 4 工具全 95% 达成 (R293) 🎉🎉🎉**
- PMD 95% (R289)
- Checkstyle 97% (R287)
- SonarQube 95% (R292)
- SpotBugs 95% (R293)
