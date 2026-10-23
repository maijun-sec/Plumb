# R350 — CWE-789 Uncontrolled Memory Allocation Checker (2026-08-05) — wire DONE, 5/5 unit tests, sweep partial

## Goal
Implement a CWE-789 detector that flags `new ArrayList(data)` / `new HashMap(data)` / `new byte[data]` etc. where `data` is derived from a taint source (HTTP input, env var, file read, DB query, etc.) and not validated.

## Wire changes
- `plumb-main/ScanConfig.java` — `runUncontrolledMemAlloc = true` flag
- `plumb-main/ScanRunner.java` — `d.register(new UncontrolledMemAllocCheck())` when flag is on
- `plumb-main/ConfigLoader.java` — yaml key `uncontrolledMemAlloc` / `uncontrolled_mem_alloc`
- `plumb-dfa/checker/UncontrolledMemAllocCheck.java` — new checker

## R350 v1 — base
- Seed taint sources: `getParameter/1`, `getQueryString/0`, `readLine/0`, `getCookies/0`, `getHeader/1`
- Forward alias walk (16 iter cap): MOVE + parseInt CALL propagation
- Match NEW with ctor ∈ {ArrayList, HashMap, LinkedList, Vector, Hashtable, StringBuffer, StringBuilder}
- If size-arg is a TacVar in taintedAliases → fire CWE-789

## R350b — coverage expansion
- `ins.ctorType.toString()` (TacType → String, was compile error)
- Added `byte[]` / `int[]` / `long[]` NEWARRAY support (NEWARRAY ctor is the component type: "byte", "int", "long")
- Added FQN forms: `java.util.ArrayList`, etc.
- Added more taint sources:
  - `getValue/0` (Cookie.getValue — CWE-789 getCookies_Servlet pattern)
  - `getenv/1` (System.getenv — CWE-789 Environment pattern)
  - `read/0`, `read/1` (Stream.read — CWE-789 connect_tcp pattern)
  - `getString/1` (ResultSet.getString — CWE-789 database pattern)
  - `getProperty/1` (System.getProperty — CWE-789 Property pattern)
  - `nextInt/0`, `nextLong/0`, `nextDouble/0`, `nextFloat/0` (Random — CWE-789 random pattern)
  - `accept/0` (ServerSocket.accept — CWE-789 listen pattern)
  - `openStream/0`, `openConnection/0` (URL — CWE-789 URLConnection pattern)
  - `load/1` (Properties.load — CWE-789 PropertiesFile pattern)
- Generic CALL propagation: if any arg of a CALL is tainted, the dest is tainted (handles `stringNumber.trim()`, `getValue()` etc. which are intermediate ops between source and parseInt)
- Dedup by (line, ctor) per visitFunction — prevents re-reporting in fixed-point loop

## R350 unit tests
- `R350Cwe789UncontrolledMemAllocTest` (5 tests, all pass):
  - `arrayListFromGetQueryStringFires` — `getQueryString` source + `new ArrayList(data)` sink → 1 finding
  - `byteArrayFromReadLineFires` — `readLine` source + `new byte[data]` NEWARRAY → 1 finding
  - `hardcodedSizeDoesNotFire` — `new ArrayList(10)` with const → 0 findings
  - `validatedSizeDoesNotFire` — documented v1 limitation (range-check suppression not yet done)
  - `arrayListFromGetEnvFires` — `System.getenv("ADD")` source + `new ArrayList(data)` → 1 finding

## CWE-789 sweep results (post-R325 config)
- s01 dir (962 files): 244 findings from uncontrolled-mem-alloc rule
- Distribution: connect_tcp_ArrayList_01 (180), getCookies_Servlet_ArrayList_01 (19), other getCookies_Servlet_xx files (45 total) — only 2 source patterns detected
- File-level TP: 2 (connect_tcp_01, getCookies_01), FN: 1569
- Precision: 0.500, recall: 0.001 — high FP from IR noise (NEW instructions emitted for things like `new Throwable()`, `new IOException(...)` which ctor.toString() resolves to non-ArrayList types but the IR-level size-arg still taints from a MOVE)

## R350 v1 limitations (carry-forward)
- IR emits many spurious NEW instructions around the source `new ArrayList(data)` line (try-with-resources, throw/catch, etc.) — need finer ctor matching
- ~~Range-check suppression (`if (data > 0 && data < 1000)`) not yet done~~ — **R355 fixed**
- Cross-function taint propagation only via TaintAnalysis, not R350's own alias walk

## R355 — range-check suppression (2026-08-05)
- Added `collectRangeChecks()` helper to R350
- Pass 1: vars compared `> 0` / `>= 0` → lowerBounded
- Pass 2: lowerBounded + `< N` (N>0) / `<= N` (N>=0) → range-checked
- Suppress CWE-789 finding if size-arg is range-checked
- `validatedSizeDoesNotFire` test flipped from "FP expected" to "no finding"
- Same template as R352 CWE-606 range-check suppression

## Numbers
- plumb-dfa tests: 426 → 431 (R350) → 437 (R355, +0 tests, updated 1 test)
- plumb-main tests: 19 (unchanged)
- plumb-dfa tests: 461 (unchanged)
- plumb-style tests: 890 (unchanged)
- 9 modules total: 0 fail, 0 regression

## R350 backup
`D:\work\tmp\r350_done\` (5 src + 1 test + 1 doc + 2 data files)

## R351+ candidates
- R351: CWE-197 Numeric Truncation (`int i = (int) longValue`) — DONE
- R352: CWE-606 range-check suppression — DONE
- R353: Performance guard test — DONE
- R354: 10-round retrospective — DONE
- R355: R350 range-check suppression — DONE
- R356+: Compound range-check (`a > 0 && b < n`), cross-function taint, parallel sweep
