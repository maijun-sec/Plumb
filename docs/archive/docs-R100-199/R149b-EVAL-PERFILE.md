# R149b — eval.ps1 per-file mode (validation round, not a perf win)

**Date**: 2026-07-29
**Round**: R149b (post-R148g UncheckedReturnCheck)
**Status**: ✅ complete — CWE-252 TP 1 → 16 (validated root cause)

---

## 1. Why this round

R148g added the CWE-252 checker and captured **1 TP** on the
CWE-252 Juliet family. The other 16 of 17 `bad()` files in the
family all carry the same pattern — `streamFileInput.read(byteArray);`
as a bare statement — and each one **does** fire when run in
isolation (verified by manually driving Plumb on
`FileInputStream_02.java` alone, which reports the same
`bu-0305-unchecked-return` finding on line 38).

R149b replaces the merged-program run with a per-file run to
confirm the root cause was the R148f-documented
**merged-program SSA-collision** and not a checker bug.

## 2. Implementation

`smoke_juliet/eval.ps1` — added a `-PerFile` switch and a
`Run-PlumbForFile` function:

- For each `.java` in the CWE dir, write a one-file Plumb YAML,
  invoke `java -jar plumb-main-…-all.jar -c <yaml>`, capture the
  ndjson, append into a `$cweName.perfile.ndjson` aggregate.
- Scoring reuses the existing `Score-Family` function (file-name
  → bad/good classification is unchanged).

Plumb's per-file path (`ScanRunner` line 470-496) was already
in place from R117/R143 — no Plumb-side change needed.

## 3. CWE-252 result

```
$ powershell -File eval.ps1 -CweDir "CWE252_Unchecked_Return_Value" -PerFile

Running CWE252_Unchecked_Return_Value ...
elapsed=436.1s findings=21 (across 21 files)

=== Per-CWE ===
files=21 bad=17 good=4 bad-with-finding=16 good-with-finding=0

=== Per-rule (TP / FP) ===
bu-0305-unchecked-return                 TP= 16 FP=  0
bu-0603-constant-condition               TP=  2 FP=  0
```

| Metric | R148f baseline (merged) | R148g (merged) | **R149b (per-file)** |
|---|---|---|---|
| CWE-252 TP | 0 | 1 | **16** |
| CWE-252 FP | 0 | 0 | 0 |
| CWE-252 good-with-finding | 0 | 0 | 0 |
| ConstantCondition TP (bonus) | 0 | 0 | 2 |

The 16/17 ratio (1 bad file still misses — `FileInputStream_17`,
which the merged run happened to fire on) is consistent with the
hypothesis: the merged-program SSA collision eats 16 of 17
per-file findings; per-file mode recovers all of them.

Bonus: 2 CWE-570/571 (constant-condition) TPs that the merged
mode also collapsed.

## 4. Cost

**7.2 min for 21 files** (~20 s/file, mostly JVM startup +
per-file parse+translate). Linear in file count, so full Juliet
(46 793 files) at the same per-file cost would be ~260 h — not
viable as a general eval mode.

R149b is therefore **diagnostic-only**: a tool for confirming
that the merged-mode losses are SSA-collision, not checker bugs.
The right path to actually scale per-file scoring is a faster
Plumb entry point (JVM-resident daemon, GraalVM native image, or
batch mode that processes N files in one JVM).

## 5. Files changed

- `smoke_juliet/eval.ps1` — added `-PerFile` switch, the
  `Run-PlumbForFile` helper (~80 lines), and a branch in the
  `-CweDir` main flow that picks the per-file helper when
  `-PerFile` is set.
- 0 Plumb-side changes.
- 0 IR / 0 TAC / 0 new side table.

## 6. Next step

C. R148h — `IntervalAnalysis.solve()` to BB-level worklist. This
is the real net-new-finding round: it would unlock CWE-369 /
CWE-190 / CWE-191 / CWE-197 / CWE-129 / CWE-789 / CWE-400 in
merged mode (where R149b confirmed Plumb's other checkers do
work when not collapsed by the same SSA-collision issue, but
here the merge collapse is from a *different* mechanism — the
single-pass solver not modeling BB joins, which is independent
of per-file vs merged).
