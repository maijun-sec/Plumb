# Support status - 02-domain-sensitive

**Range and guard narrowing is sound, including across call boundaries.** Local guards, `Math.min`/`Math.max` clamps, and the early-return idiom (`if (d == 0) return 0;`) are all honoured. The early-return case needed a false-edge fact the guard tracker could not express: the guard is popped by the JUMP that skips the else block, so the else body looked unguarded (R1101).

## Measured

- True positives: **TP 7/7**
- True negatives: **TN 11/11**
- Scan mode: per-file, callgraph strategy: `global`.

## Outstanding cases

none

## Known limitations

none

Full per-case detail: `RESULT.json`. Expectations: `EXPECTATIONS.json`.
