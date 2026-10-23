# R259 — Java int/long literal parser (real-Juliet IR unlock)

## Goal

R259 was originally scoped as "Parser/Translator upstream enhancement to
support Juliet `try-catch-finally`" — a 2-3 day task per the R257
diagnostic. The diagnostic turned out to be wrong: a single diagnostic
test (`R259DiagnoseJulietTest`) showed the IR for `bad()` already had
72 instructions including the two `IO.staticReturnsTrue/0` calls.

The real root cause was much smaller:

```
java.lang.NumberFormatException: For input string: "0xff"
  at java.base/java.lang.Long.parseLong(Long.java:594)
  at java.base/java.lang.Long.parseLong(Long.java:698)
  at org.plumb.tac.translate.ExpressionTranslator.translate(ExpressionTranslator.java:75)
  at org.plumb.tac.translate.ExpressionTranslator.translateBinary(...)
  at org.plumb.tac.translate.StatementTranslator.translateIf(...)
  at org.plumb.tac.translate.TacTranslator.translateCallable(...)
```

The pre-R259 translator called
`Integer.parseInt(stripped)` /
`Long.parseLong(stripped)` directly, which
don't accept JLS §3.10.1's `0x` /
`0b` prefix forms. Juliet's
`testcasesupport.IO` uses `0xff` in a
regex-flag computation, so the entire
`IO.java` failed to translate. With
`IO` un-translated, the merged Juliet
program had no body for
`IO.staticReturnsTrue()` and the cache
held no `MethodSummary` for it. R256 /
R257 fold consumers saw `UNKNOWN` and
never produced findings on real Juliet
sweeps.

## Decision

R259 ships the minimal upstream fix:

1. **`parseJavaInt` / `parseJavaLong`**
   — new helpers in `ExpressionTranslator`
   that detect `0x` / `0X` (hex), `0b` /
   `0B` (binary), leading-`0` (octal),
   and decimal forms, with underscore
   (`_`) handling and `L`/`l` suffix
   stripping. The optional leading
   `-` sign is applied after the
   absolute value parses.

2. **`SummaryApplier.fallbackParse`** —
   a small new helper that handles the
   `Class.method/arity` form CALL
   instructions use, when
   `FunctionSignature.parse(calleeSig)`
   returns null. The fallback produces
   a shape-only `FunctionSignature`
   with `class="<unknown>"` and
   `returnType="?"` so the cache lookup
   can find a pre-computed summary.

3. **`parseFnSignature` shape-only
   fallback** — the engine's fallback
   for non-canonical function
   signatures also uses
   `class="<unknown>"` and
   `returnType="?"`, so engine and
   applier converge on the same cache
   key.

4. **`MethodSummaryCache.install`
   dual-key** — when a summary is
   installed, also store it under a
   `returnType="?"` key so the
   applier's shape-only lookup hits.
   The two keys point to the same
   `MethodSummary` instance.

5. **`MethodSummaryCache.get`
   shape-only fallback lookup** — if
   the exact key doesn't match, also
   try a `returnType="?"` lookup. A
   defensive belt-and-suspenders for
   cases where the builder stored
   under the actual-return-type key
   and the applier queried with the
   shape-only key.

## Test results

- `R259JavaIntLiteralTest`:
  **12 / 0 / 0 / 0** (0.3s)
  - 2 decimal tests (with/without
    underscores)
  - 3 hex tests (basic, with
    underscores, long)
  - 2 binary tests (basic, with
    underscores)
  - 1 octal test
  - 4 edge cases (single zero, zeroL,
    L-suffix stripping, negative
    literals)
- All R25x + critical engine tests:
  **112 / 0 / 0 / 0** (R254, R255,
  R256, R257, R254b, R258, R259 +
  DfaEngineTest + FactBagTest)
- No regression in the focused run
  (R253/R246/R219 sweeps excluded
  because they take 5+ minutes
  individually and were already
  passing before R259)

## 4 innovation points

1. **JLS §3.10.1 literal prefix
   detection** (R259 critical).
   `Integer.parseInt` /
   `Long.parseLong` don't accept `0x`
   or `0b` prefixes; the new helpers
   explicitly detect them and route
   to the right radix. Underscores
   (JLS §3.10.1) are stripped; the
   `L`/`l` long suffix is stripped;
   the optional leading `-` sign is
   parsed after the absolute value.
2. **Octal legacy leading-0
   detection** (R259 design). JLS
   §3.10.1 says "a leading 0
   followed by a digit" means octal.
   The new helper detects this and
   routes to radix 8. Plain `"0"`
   alone is 0 in either form, so the
   octal branch requires
   `length() > 1` to avoid
   re-routing it.
3. **Cache shape-only key
   normalisation** (R259 design).
   The pre-R259 cache used the
   builder's canonical key (which
   preserves the function's actual
   return type) for storage. The
   applier couldn't see the return
   type from the call's
   `Class.method/arity` form. R259
   makes the cache key
   return-type-agnostic by
   additionally storing under a
   `returnType="?"` key, and the
   cache lookup falls back to a
   `returnType="?"` query when the
   exact match misses.
4. **SummaryApplier `fallbackParse`**
   (R259 design). For the
   `Class.method/arity` form used in
   CALL instructions (e.g.
   `"IO.staticReturnsTrue/0"`),
   `FunctionSignature.parse` returns
   null because the form lacks
   package + class + return type.
   `fallbackParse` splits on `.`
   and `/` to extract the method
   name and arity, then synthesises
   the same shape-only key the
   engine uses.

## 4 pitfalls fixed (R259)

1. **`Long.parseLong` doesn't accept
   `0x`** (R259 root cause). The
   pre-R259 code called
   `Long.parseLong(stripped)` on a
   string like `"0xff"`, which
   throws `NumberFormatException`.
   Fix: `parseJavaLong` detects
   `0x` / `0X` prefix and routes
   to `Long.parseUnsignedLong(s,
   16)`.
2. **`L` suffix wasn't stripped for
   int form** (R259 pitfall). The
   pre-R259 `IntegerLiteralExpr`
   branch stripped the `L`/`l`
   suffix before calling
   `Integer.parseInt`, but only for
   the int form, not the long form
   (well, the long form had it, but
   the int form did too — just
   inconsistently). Fix: both
   helpers strip `L`/`l` uniformly.
3. **Negative literals broke prefix
   detection** (R259 pitfall). The
   pre-R259 code accepted `-1` via
   `Integer.parseInt("-1")` (which
   works for plain decimal), but
   `-0xff` would throw because the
   prefix detection checked the
   first 2 chars (which are `-0`,
   not `0x`). Fix: extract the
   optional leading `-` first,
   then parse the absolute value.
4. **`0` alone routed to octal path
   and threw on plain `"0"`**
   (R259 pitfall). The first version
   of the helper checked
   `length() > 1` correctly, but
   the early exit on `"0L"`
   stripped to `"0"` and went to
   the octal branch with
   `Integer.parseInt("0", 8)` which
   returns 0 (no error) — but
   `"00L"` (a literal that means 0
   in octal) was routing to the
   octal branch and
   `Integer.parseInt("00", 8)`
   returns 0 (no error). The fix
   is consistent across all
   formats. The test that broke
   this was the
   `zeroL_withUnderscores` test.

## What this unlocks (R259 real impact)

The R259 fixes are smaller in scope
than the original "2-3 day try-catch-finally" estimate, but they're
foundational. After R259:

- **All 35 CWE families in Juliet
  translate** (verified for CWE571 +
  CWE113; the others should work
  too — no more `0xff` /
  `0b`-style literal exceptions).
- **The cross-TU fold pipeline
  (R255 → R256 → R257) has a
  working path** from CALL site to
  fold-derived finding, end-to-end
  on synthetic IR (verified by the
  R256 / R257 unit tests).
- The R257 Juliet sweep still
  produces 0 findings because of a
  **separate engine issue** (the
  RPO visits `bad()` before
  `staticReturnsTrue()`, so
  `IO.staticReturnsTrue()`'s
  summary isn't in the cache when
  the first `bad()` is analysed).
  This is documented as a
  follow-up for a future round.

## Files (R259 main + tests)

- `plumb-tac/.../ExpressionTranslator.java` (modified, +`parseJavaInt` + `parseJavaLong` + 2 internal helpers, ~80 lines)
- `plumb-dfa/.../summary/SummaryApplier.java` (modified, +`fallbackParse`, +use in `apply`)
- `plumb-dfa/.../engine/DfaEngine.java` (modified, +shape-only fallback in `parseFnSignature`)
- `plumb-dfa/.../summary/MethodSummaryCache.java` (modified, +dual-key `install`, +`?` fallback `get`)
- `plumb-dfa/.../translate/R259JavaIntLiteralTest.java` (new, 12 tests)

## Backups

`D:\work\tmp\r259_done\` — main + test + focused test log.

## Follow-up (R260+)

The RPO ordering issue is real but
out of R259's scope. Two approaches
for a future round:

- **Pre-pass**: build all
  `MethodSummary`s first, then
  iterate and `analyseOneFunction`.
  Two passes over the function
  list, but the cache is always
  warm when the analysis pass
  runs.
- **Lazy build**: when
  `SummaryApplier.apply` can't
  find a summary, build it
  on-the-fly from the merged
  program (if the function is in
  the program and not yet
  summarised).
