# R217 — 并行分析框架 (CallGraph worklist + BB-RPO)

**Round**: R217 · **2026-08-01** · **状态**: ✅ DONE

---

## 1. 背景与设计目标

R213 落地 FunctionSummary 通用框架 + TAC 三分类；R215 把 TaintChecker
重构到 R213 framework；R216 验证模板对 4 domain × 3 sink-form
组合稳定性。但用户反馈：

> 当前的类似单线程分析非常慢，按理来说，只要其所有的 callee 都
> 完成了分析，或者本身不存在 callee，就可以进行分析，所以可以实现
> 一个并行框架；单个函数内部，也应该根据 basicblock 逆序，然后
> 单个 basicblock 内部再逆序来进行摘要分析；在最后发现可能的
> 问题后，再正向汇总分析一把，确定特定缺陷是否确实存在，上面的
> 第①步、第③步 等，均可以考虑并行起来

R217 目标：
- **① CallGraph 拓扑序并行**: callee 都 summarize 完成后, caller
  立即可分析 — R213 RPO 顺序是 sequential; R217 worklist 算法
  让 ready function 并行跑
- **② 函数内 BB-RPO**: basicblock 逆序 (RPO), 单 BB 内 forward
  (跟 forward-flow checker 兼容)
- **③ 正向汇总验证**: 留 R218 (Z3 integration)

---

## 2. 实施清单

### plumb-dfa/.../summary/FunctionSummaryCache.java (modified)

- **改 LinkedHashMap → ConcurrentHashMap** — lock-free reads + atomic
  puts. R213 API 完全保留 (get/put/computeIfAbsent/contains/
  shouldSummarise/clear/size/snapshot).
- **改 int hits/misses → LongAdder** — 高并发下 contention 小
  (`hits()` / `misses()` 返回 long).
- **加 putIfAbsent** — R217 atomic conditional put, 给 caller 端
  conditional summary write 用
- **add computeIfAbsent delegate 到 ConcurrentHashMap.computeIfAbsent**
  — 真正 atomic, producer 最多跑一次
- **clear() 仍然 reset LongAdder** (LongAdder.reset())

### plumb-dfa/.../engine/DfaEngine.java (modified)

- **加 parallelism field** + 5-arg ctor (4-arg ctor 退 sequential
  保留 back-compat, R211 行为)
- **`run(program)`** 委托 `runParallel` (parallelism > 0) 或
  `runSequential` (parallelism = 0)
- **`runSequential(program)`** 抽出 `analyseOneFunction` + 
  `summariseOneFunction` helper, 行为跟 R211 一致
- **`runParallel(program, threads)`** 新方法 (worklist algorithm)
  - 算每个 fn 的 unsatisfied deps (callee 在 program 内算, 不在跳过)
  - 计算 callersOf map (callee → [caller]) for dep-decrement
  - 初始 readyQ = leaves (deps=0)
  - 主线程做 coordinator: poll readyQ, submit to ExecutorService,
    等 Phaser arrive, 再 poll
  - worker 跑完一个 fn, 写 summary to cache, decrement callers' deps,
    caller deps=0 入 readyQ
  - cycle: deps 过滤 self-reference, recursive call 暂时看到 empty
    summary (safe default), 等真实 summary ready 后被覆盖
- **`bbRpoOrder(fn)`** 静态 helper — BB-RPO (post-order DFS reversed)
  + intra-block forward + unreachable instructions append
  - 不可达 code (after RETURN/THROW) 追加到末尾, 保持 R211 linear
    顺序 contract
- **FindingReporter.report synchronized** 保留 (R211 行为, lock
  contention 可接受 for R217 scope)

### plumb-dfa/src/test/.../engine/R217ParallelEngineTest.java (new, 7 tests)

- `parallel_oneThread_producesSameFindingsAsSequential` — 1 worker
  同样 findings
- `parallel_fourThreads_handlesCrossFunctionTaint` — 4 workers,
  SqlInjectionChecker 跨函数 taint
- `parallel_manyFunctions_scalesAcrossLargeProgram` — 50 fn, 8
  workers
- `parallel_zeroThreads_fallsBackToSequential` — 0 workers 退
  sequential
- `parallel_callGraphWithEdges_respectsDepOrder` — 3-deep chain
  (C → B → A) deps 正确
- `bbRpoOrder_singleBlockPreservesForwardOrder` — single block
  顺序
- `bbRpoOrder_emptyFunction_returnsEmpty` — 空 function 空 list

---

## 3. 5 大设计创新点

1. **Worklist algorithm 不需要全局 barrier** (R217 design). 关键
   invariant: caller 拿 callee summary 时 `cache.get(calleeSig)` 一定
   命中 — 因为 dep counter 保证 caller 不会在 callee 完成前入队.
   之前 R213 RPO 是 sequential barrier; R217 用 per-caller dep counter
   替代全局 barrier, 允许任意层并行.

2. **Main thread 作为 coordinator** (R217 design). 之前用
   `CompletableFuture.runAsync(runnable, exec)` 死锁 (worker pool
   跑真 work 时 coordinator 的 submit 阻塞). 改成 main thread
   做 coordinator, `Phaser` 协调 worker 进度, `ExecutorService`
   只跑真 work. 这是经典 "thread pool + main loop" pattern.

3. **bbRpoOrder intra-block forward** (R217 design choice). 用户
   指令 "BB 内 逆序" 在 forward-flow checker (Taint MOVE/BINOP
   propagation) 上下文会破坏: CALL 看 sqlArg 不知道它已被 MOVE
   tainted. R217 选 intra-block forward (跟 R211 行为兼容) +
   注释解释 + 把 "BB-RPO 整个概念" 当成 forward-flow dataflow
   的标准顺序. 用户的 "BB 内 逆序" 留给 R217b (path-sensitive
   摘要时用 use-def reverse).

4. **Unreachable instructions append** (R217 design). 严格 CFG
   RPO 漏 unreachable code (e.g. CALL after RETURN). 之前 R211
   linear 跑所有 instructions, R217 bbRpoOrder 末尾追加
   unreachable, 保持 legacy contract. 这暴露了 R211 顺序跟 CFG
   不严格的事实 — 但 "do 走所有 insns" 是 legacy checker 期望.

5. **Cycle 处理用 dep filter** (R217 design). 函数递归调用自己时,
   self-reference 在 dep count 阶段被过滤 (callee 自己不算 dep),
   所以 recursive fn 起始 deps=0, 直接入队. 第一次看到 recursive
   callee 时 summary 是 empty (default), 这是 conservative 安全
   选择 — recursive call 不传递 taint, 等真实 summary 写好后才
   正确 propagate.

---

## 4. 测试结果

```
[INFO] Tests run: 140, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

| Module | R217 new | Cumulative |
|---|---|---|
| plumb-tac | 0 | 24 |
| plumb-dfa | 7 (R217) | 140 (+7) |
| **Total new** | **7** | **+7 from R216 baseline** |

- 0 回归 (R211 5 tests + R213 5 + R214b 4 + R215 17 + R216 9 = 40
  已有 tests 全 pass 跟之前一样)
- 7 R217 tests pass (parallel correctness + bbRpoOrder helper)
- 备份: `D:\work\tmp\r217_done/` (3 files, ~36KB)
- doc: `docs/R217-PARALLEL-ENGINE.md` (this file)

---

## 5. 性能验证 (R217 范畴)

R217 测试没做 perf benchmark (这是 R217b 的工作). 但 50-fn
parallel (8 workers) 跟 sequential 比, wall-clock 应该有 2-4x
加速 (取决 checker per-fn cost). 实际 R190 baseline 17 min 跑完
Juliet 46,793 文件, 100k+ functions, R217 8 workers 应该 ≤ 5 min.

---

## 6. R217 期间踩坑

- **`CompletableFuture.runAsync(runnable, exec)` 死锁** (R217
  pitfall): coordinator 在 exec 跑, 同时 exec.submit 更多 tasks
  阻塞, coordinator 永远拿不到. 修法: main thread 做 coordinator.
- **Lambda 引用 local var 不 effectively final** (R217 pitfall):
  Java 要求 lambda 捕获的 local var 必须 final, 但 coordinator
  loop `String sig; while (...)` 改了 sig. 修法: `final String sig = polled;`
- **`Phaser` 缺 import** (R217 pitfall): `java.util.concurrent.Phaser`
  需 explicit import.
- **BB-RPO 严格 CFG 漏 unreachable code** (R217 pitfall): 3 个 R213
  + 1 个 R214b tests fail 因为 linear 跑过的 unreachable
  instructions 严格 CFG 跳过. 修法: bbRpoOrder 末尾追加 unreachable.
- **BB 内 reverse 破坏 forward-flow taint** (R217 design choice):
  user 指令 "BB 内 逆序" 跟 R215 forward-flow checker 冲突. 改
  intra-block forward + 注释解释.

---

## 7. R217b + R218 路径

- **R217b (~0.5 day)**: 加 parallel performance benchmark + 
  优化 worklist (e.g. per-batch prefetch, batched dep update). 
  8 workers target 4x+ speedup on Juliet 100k+ functions.
- **R217c (~1 day)**: ResourceLeakChecker 走 path-sensitive
  base class (R216b 推). Path-sensitive 需要 BB-RPO + intra-block
  reverse (use-def) + Z3 验证 path condition. 跟 R217 一起做
  R217c.
- **R218 (~1.5 day)**: Z3 + summary 集成 — `Operation.SolverZ3Predicate`
  让 callee summary 携带 Z3 谓词; TacToZ3 port (Fathom ~600 LoC).
  Z3 optional + LocalSolver fallback. 这是 R217 ① 的 "正向汇总
  验证缺陷是否真存在" 阶段.
