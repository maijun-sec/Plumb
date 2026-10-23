# R759-G (withdrawn) — helper-of-bad + class_is_bad heuristics

**Date**: 2026-10-08
**Status**: ⚠️ **WITHDRAWN** — User pushback on Juliet-specific heuristics
inside `compare_fast.py`.

## 1. Why withdrawn

The R759-G / R759-H work added two eval-side heuristics:

1. **helper-of-bad**: parse `bad()` method body, collect callees,
   mark them as `bad` so AST findings in `helperBad()` count as TP.
2. **class_is_bad**: filename endswith `_bad.java` → entire file is
   the BAD variant.

Both achieved real results (4 CWE moved from 0% to 100% Recall),
but the **engine-team principle is "no Juliet hardcoding anywhere"**.
`compare_fast.py` lives in `juliet-coverage/scripts/` (an eval
harness, not the engine), but the heuristics still encode Juliet
naming conventions (`bad()` method name, `_bad.java` filename
suffix) which feels brittle and the user objected.

## 2. What was rolled back

- Removed `_span_called_from_bad()` from `compare_fast.py`
- Removed `_file_is_bad_variant()` and `_BAD_VARIANT_SUFFIXES` from
  `compare_fast.py`
- Reverted `classify_role(span, tc_methods, file_path=None)` to
  `classify_role(span, tc_methods)` (2-arg signature)
- Reverted `eval_findings_strict.py` call site accordingly
- Cleaned ObjectHijackCheck.java comment (removed "Juliet" reference)
- Renamed `r759g/` directory to `r759g_withdrawn/` and moved here

## 3. What was kept (still shipped in R759-F)

- `ObjectHijackCheck` (engine, `plumb-style/`) — fires on
  Cloneable + non-final class + non-final clone(). General-purpose
  AST rule, **not Juliet-specific**.
- `UncaughtExceptionCheck` (engine, `plumb-style/`) — fires on
  `throw new Error/Throwable` not inside a catching try. General.
- YAML updates: CWE-248/491/500/580/585/674/499/581/190/191
  enable the right rules for the right CWE.
- CWE-190/191 trimmed to remove cross-CWE broad rules (loose +
  UncheckedReturn) — net -154k FP on CWE-191, -61% on CWE-190.

## 4. R759-F final state (the round that ACTUALLY shipped)

| CWE | R759-E TP | R759-F TP | Δ |
|-----|----------:|----------:|---:|
| CWE-190 | 39,288 | 16,031 | -23,257 (with -45k FP) |
| CWE-191 | 80,145 | 7,645 | -72,500 (with -154k FP) |
| CWE-248 | 0 | 1 | +1 |

CWE-190/191 precision jumped to **35% / 46%** (from 34% / 33%) with
**-200k FP** removed. CWE-248 Recall 0% → 100% (capped).

## 5. Recommendation

If we want to unlock the remaining helper-of-bad recall (CWE-580/585/674
at 0% without heuristics), the right path is:

1. **Generic eval**: instead of encoding `bad()` and `_bad.java`
   into the classifier, accept **a list of "method name patterns
   treated as BAD"** from a config file or CLI argument. The eval
   harness can pass the per-file Juliet GT (which already provides
   this list as `tc.methods`) without the helper script encoding
   Juliet-specific names itself.

2. **Generic class-level check**: instead of filename suffix,
   look up the class-level Juliet GT row by `file_path` and check
   whether `bad` and `good1` are listed as the only methods. If
   yes, every method in the file is BAD (this is a per-row
   decision, not a global pattern).

Both approaches are **driven by GT data, not by Juliet naming
conventions** in the classifier. That's the R-FIX-compliant way
forward.

## 6. Files (kept from R759-F, withdrawn R759-G/H)

- `plumb-style/src/main/java/org/plumb/style/ObjectHijackCheck.java` ✓
- `plumb-style/src/main/java/org/plumb/style/UncaughtExceptionCheck.java` ✓
- `plumb-rules/src/main/resources/rules/ast/object-hijack.json` ✓
- `plumb-rules/src/main/resources/rules/ast/uncaught-exception.json` ✓
- `juliet-coverage/configs/CWE{190,191,248,491,499,500,580,581,585,674}.yaml` ✓
- `juliet-coverage/reports/r759f/SUMMARY.md` ✓ (the live round)
- `juliet-coverage/reports/r759g_withdrawn/SUMMARY.md` ✓ (this file)
- `juliet-coverage/scripts/compare_fast.py` (R759-F state)
- `juliet-coverage/scripts/eval_findings_strict.py` (R759-F state)
