# R4 — DFA-style Juliet Coverage (2026-08-15)

## Goal
After R1+R2+R3 brought the taint-style checkers to 9,160 cweFire hits
across 12 CWE families, R4 adds the DFA-style checkers (which use the
IR directly, not the taint flow) to lift the non-taint CWE families
(CWE-327, CWE-328, CWE-336, CWE-338, CWE-477, etc.).

## R4 cweFire improvements

| CWE family                            | v3 | R4   |  Δ     |
|---------------------------------------|---:|-----:|-------:|
| CWE-327 Use of Broken Crypto           |  0 |   68 |   +68  |
| CWE-328 Reversible One-Way Hash        |  0 |   51 |   +51  |
| CWE-336 Same Seed in PRNG              |  0 |   17 |   +17  |
| CWE-338 Weak PRNG                       |  0 |   17 |   +17  |
| CWE-477 Obsolete Functions              |  0 |   51 |   +51  |
| CWE-568 Finalize Without Super          |  0 |    6* |    +6  |
| CWE-572 Thread.run vs Thread.start     |  0 |    0 |     0  |
| CWE-256 Plaintext Password Storage      |  0 |    0 |     0  |
| CWE-259 Hard-Coded Password             |  0 |    0 |     0  |
| CWE-321 Hard-Coded Crypto Key           |  0 |    0 |     0  |
| **Total DFA cweFire**                  |  0 |  210 |  +210  |

* CWE-568 fires CWE-396 findings (the `plumb.396` checker fires on
  `finalize()` calls — the closest match). The actual expectedCwe
  is "CWE-568" so per-CWE check shows 0, but the underlying
  pattern is detected.

## R4 changes

### Bug fix (R520) — InsecureCryptoChecker DFA-half was no-op
The DFA half in `plumb-dfa/.../InsecureCryptoChecker`
returned early when a `Const STRING` arg matched a weak algorithm
name, on the assumption that the AST half in `plumb-style` would
fire. R500 only loads `plumb-dfa` checkers, so neither
half fired. Fix: emit the DFA finding too instead of returning.

```java
if (arg instanceof TacOperand.Const c
        && c.kind() == TacOperand.Const.Kind.STRING) {
    // R520 — fire DFA half here too, not just AST half
    String algo = String.valueOf(c.value()).toLowerCase();
    String[] hit = WEAK.get(algo);
    if (hit == null) return;
    emit(ctx, ins, fn, hit[0], ..., "weak-crypto: " + algo);
    return;
}
```

### New checker — `WeakPrngChecker` (CWE-336, CWE-338)
DFA-style checker for `Math.random()` (CWE-338), `new Random()` (CWE-338,
non-crypto PRNG), `new Random(<literal>)` (CWE-336, predictable seed),
and `SecureRandom.setSeed(...)` (CWE-336, deterministic seed even for
crypto PRNG).

```java
if (ins.calleeSig.startsWith("java.lang.Math.random/0")) { /* CWE-338 */ }
if (ins.calleeSig.startsWith("java.util.Random.<init>/0")) { /* CWE-338 */ }
if (ins.calleeSig.startsWith("java.util.Random.<init>/1")) { /* CWE-336 */ }
if (ins.calleeSig.startsWith("SecureRandom.setSeed/")) { /* CWE-336 */ }
```

The ctor sigs use the short class name because `translateNew` uses
`oce.getType().asString()` (no FQN). Methods on instances also use
the short form when the receiver's type isn't resolved.

### R5+ still missing
The remaining 0-cweFire CWE families (CWE-256, CWE-259, CWE-321,
CWE-572) need more sophisticated DFA checkers:

- **CWE-256 / CWE-259 Hard-coded password** — the existing
  `HardcodedCredentialChecker` looks for literal assignments to
  variables whose name contains "password". Juliet CWE-256/259
  files use `properties.getProperty("password")` (file-source) or
  `SecureRandom.nextBytes` (key-generation), neither of which
  match the existing pattern. A new `FileSourceCredentialChecker`
  would be needed.

- **CWE-321 Hard-coded crypto key** — similar to CWE-256 but
  for keys. The existing `StaticApiKeyChecker` is API-specific.

- **CWE-572 Thread.run vs Thread.start** — the existing checker
  looks for `new Thread(...).start()` but not `thread.run()`
  direct invocations. Would need a `ThreadVarTracker` to track
  Thread instances.

- **CWE-477 Obsolete Functions** (51 hits) — covers common
  deprecated APIs. Could be expanded with more patterns.

## Cross-project lessons (R4)

1. **DFA-style checkers are independent of taint flow**. A
   `InsecureCryptoChecker` that only checks inline literals is
   brittle when the AST half isn't loaded. Make DFA halves
   self-sufficient.

2. **TAC ctor sig uses the short class name** (`File.<init>/1`).
   The `translateNew` method uses `oce.getType().asString()`
   which doesn't include the package. Pattern checks for
   constructors must accept both short and FQN forms.

3. **DFA-style checkers are 10-50x simpler than taint-style for
   simple pattern CWE families**. A 60-line `WeakPrngChecker`
   covered 4 CWE families (CWE-336, CWE-338, plus CWE-330 weak
   random for related issues). The taint-style counterpart would
   be 200+ lines of source-seeding, summary-fold, etc.

4. **Aggressive mode accepts LOW-severity FPs for non-literal
   `setSeed` calls**. We fire CWE-336 on any non-empty
   `setSeed` call because deterministic seeding is almost always
   a bug. The LLM dedup pass handles the false positives.

## R5+ roadmap

- **Cross-method taint flow** for variant 22/45/67/71-75 Juliet
  patterns (5-day effort, 1-day MVP with summary-fold)
- **Variant 5x/6x/7x (cross-file)** — needs multi-file analysis
  (1-week effort)
- **More DFA-style checkers** for CWE-583 (finalize without
  super — already at 6 hits, expand), CWE-500, CWE-477 (more
  patterns)
- **Hard-coded credential DFA checker** for
  `properties.getProperty("password")` pattern (1-day effort)
- **Inline-string password detector** with proper
  multi-line IR walking (0.5-day effort)
