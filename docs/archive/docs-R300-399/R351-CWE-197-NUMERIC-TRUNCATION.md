# R351 — CWE-197 Numeric Truncation Error (2026-08-05) — DONE, +5 tests, 0 regression

## Goal
Detect narrowing numeric casts (e.g. `(byte) data` where `data` is an `int`, `(int) longVal` where `longVal` is a `long`) of tainted values. Juliet CWE-197 patterns:
- `int data = Integer.parseInt(req.getQueryString()); byte b = (byte) data;`
- `long data = Long.parseLong(...); int i = (int) data;`
- `int data = ...; short s = (short) data;`

## Wire changes
- `plumb-main/ScanConfig.java` — `runNumericTruncation = true` flag
- `plumb-main/ScanRunner.java` — `d.register(new NumericTruncationCheck())`
- `plumb-main/ConfigLoader.java` — yaml key `numericTruncation` / `numeric_truncation`
- `plumb-dfa/checker/NumericTruncationCheck.java` — new checker

## R351 v1 — base
- Same alias-walk pattern as R349/R350 (16-iter fixed-point)
- Seed taint sources: getParameter/getQueryString/readLine/getCookies/getValue/getHeader/getenv/read/getString/getProperty/nextInt/nextLong/nextDouble/nextFloat/accept/openStream/load
- Walk MOVE + parseInt CALL + generic CALL propagation
- Look for `UNARYOP` instructions with `unop == CAST` and `castType != null`
- Fire if `isNarrowing(src.type(), ins.castType)`:
  - byte (8) < short/char (16) < int/float (32) < long/double (64)
  - Same width → not narrowing
  - Reference casts → ignored
- Dedup by (line, castType) per visitFunction

## R351 unit tests
- `R351Cwe197NumericTruncationTest` (5 tests, all pass):
  - `byteCastOfTaintedIntFires` — `(byte) data` of tainted int → 1 finding
  - `shortCastOfTaintedIntFires` — `(short) data` of tainted int → 1 finding
  - `intCastOfTaintedLongFires` — `(int) data` of tainted long → 1 finding
  - `narrowingCastOfConstDoesNotFire` — `(byte) 42` → 0 findings
  - `wideningCastOfTaintedDoesNotFire` — `(long) data` → 0 findings (widening, not narrowing)

## CWE-197 sweep results (post-R325 config)
- s01 dir baseline: 560 findings from numeric-truncation rule
- Distribution: 1 file has 551 findings (CWE197_Numeric_Truncation_Error__int_connect_tcp_to_byte_01.java)
- File-level TP: 1, FN: 1220
- Precision: 0.500, recall: 0.001

## R351 v1 limitations (carry-forward)
- IR emits many CAST instructions (e.g. for `int i = 0;` constant folding, primitive conversions in arithmetic) — need finer ctor matching
- Range-check suppression (`if (data >= 0 && data <= 100) byte b = (byte) data`) not yet done
- Cross-function taint propagation only via TaintAnalysis
- Same source pattern coverage as R350 (5/14 Juliet sources)

## Numbers
- plumb-dfa tests: 431 → **436** (+5 R351)
- plumb-main tests: 19 (unchanged)
- plumb-dfa tests: 461 (unchanged)
- plumb-style tests: 890 (unchanged)
- 9 modules total: 0 fail, 0 regression

## R351 backup
`D:\work\tmp\r351_done\` (3 src + 1 test + 2 data files)

## R352+ candidates
- R352: CWE-15 External Control / CWE-25X Crypto
- R353: Performance pass (cache, parallel)
- R354: 10-round retrospective R340-R354
- R351b: range-check suppression + finer ctor matching
