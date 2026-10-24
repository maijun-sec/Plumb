# Support status - 01-taint-crossfile

**Cross-process and cross-file taint propagation works, and findings are attributed to the caller's parameter.** Values traverse ~9 methods across 6 files and reach the sink. Every case passes, including the sink-side shapes the DFA engine has no pattern for -- those are caught by `ast-style-info-exposure-querystring` (CWE-89), which is why the harness must match on an explicit rule list rather than a rule-prefix blacklist (R1102).

## Measured

- True positives: **TP 16/16**
- True negatives: **TN 2/2**
- Scan mode: per-file, callgraph strategy: `global`.

## Outstanding cases

none

## Known limitations

none

Full per-case detail: `RESULT.json`. Expectations: `EXPECTATIONS.json`.
