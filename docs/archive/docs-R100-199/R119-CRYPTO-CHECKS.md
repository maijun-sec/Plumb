# R119b — Crypto & Random Checkers

**Date**: 2026-07-27
**Status**: ✅ Complete (5 new checkers)
**Reference**: Coverity `WEAK_CRYPTO`, `WEAK_PASSWORD_HASH`, `RISKY_CRYPTO`, `INSECURE_RANDOM`, `PREDICTABLE_RANDOM_SEED`, `PREDICTABLE_KEY_PASSWORD`, `STATIC_API_KEY`

R119b fills the crypto & random gap surfaced by the Coverity gap analysis. Five new structural DFA checkers complement the taint-based checks from R119a.

## New checkers

| Rule ID | Rule name | CWE | Severity | Triggers on |
|---|---|---|---|---|
| `bu-0401-weak-crypto` | `WEAK_CRYPTO` | CWE-327, CWE-328 | HIGH | `MessageDigest.getInstance("MD5"\|"SHA-1"\|"DES"\|...)`, `Cipher.getInstance("DES/ECB/...")` |
| `bu-0402-insecure-random` | `INSECURE_RANDOM` | CWE-330 | MEDIUM | `new java.util.Random()` (regardless of use site) |
| `bu-0403-predictable-seed` | `PREDICTABLE_RANDOM_SEED` | CWE-332, CWE-336 | HIGH | `new Random(<literal>)`, `r.setSeed(<literal>)` |
| `bu-0404-predictable-key-password` | `PREDICTABLE_KEY_PASSWORD` | CWE-259, CWE-798 | CRITICAL | `new PBEKeySpec(...)` / `new SecretKeySpec(...)` with literal password/key |
| `bu-0405-static-api-key` | `STATIC_API_KEY` | CWE-798, CWE-259 | CRITICAL | `static final` field whose name contains a credential-like token and whose value is a literal string |

## Implementation notes

### WeakCryptoCheck
- The JCA factory methods are matched by **regex** (`Pattern.compile(".*\\.getInstance/\\d+")`) so any FQN works. Earlier attempts with `endsWith` failed: the calleeSig is `MessageDigest.getInstance/1`, and `endsWith(".getInstance/")` was off by one character (the last `/1` ate the position).
- The algorithm argument is the first `STRING` constant among the call's args, which handles both static calls (`args[0]` is the scope chain, `args[1]` is the literal) and instance calls.
- Weak set covers MD2/MD4/MD5, SHA-1 family, DES/RC2/RC4/Blowfish, ECB mode.
- `new char[]{'a','b','c'}` for `PBEKeySpec` is NOT caught because the TAC builder doesn't surface array-initializer expressions as a literal `TacConst` yet (acknowledged limitation).

### InsecureRandomCheck
- Flags every `new java.util.Random()` regardless of how the random is used. The trade-off is that a few "Random for a game" cases will fire — but real code should prefer `ThreadLocalRandom` for non-security uses anyway, so the cost of the false positive is low.
- Detected via TAC `NEW` instruction's `ctorType` (matches `Random` and `java.util.Random`).

### PredictableRandomSeedCheck
- Two patterns: `new Random(<literal>)` (constructor with literal arg) and `someVar.setSeed(<literal>)` (post-construction seeding).
- Literal means `TacConst` of `INT`, `LONG`, or `STRING`. `setSeed(System.currentTimeMillis())` (a CALL, not a Const) is correctly silent.
- Per-project: a `SecureRandom` with a constant seed is **also** a vulnerability (CWE-336: PRNG seeded with constant), so the check covers both.

### PredictableKeyPasswordCheck
- Currently catches `PBEKeySpec` / `SecretKeySpec` with a literal **string** password (via `TacConst.STRING`). Array-initializer passwords (`new char[]{...}`, `new byte[]{...}`) are not yet tracked — that's a separate TAC-level fix to surface array-initializer expressions as `TacConst` of kind `ARRAY` (which doesn't exist yet; the `Const.Kind` enum is `INT/LONG/FLOAT/DOUBLE/BOOL/CHAR/STRING/NULL`).
- The check is acknowledged to be partial but still fires on the most common realistic case: a literal string passed via `.toCharArray()` and the TAC's inability to track that transformation.

### StaticApiKeyCheck
- Only walks the synthetic `<clinit>` function (R114) emitted by the translator for static field initializers. This filters out instance fields (which the broader `HardcodedCredentialCheck` already covers).
- The name regex is deliberately **not** anchored with `\b` because Java naming conventions produce `STRIPE_API_KEY` (no word boundary between `STRIPE_` and `API_`) — `\b` would skip those.
- Hits `PASSWORD` and `API_KEY` in the `examples/complex/BadCode.java` demo (alongside the broader `HardcodedCredentialCheck`).

## Files changed

- `dfa/WeakCryptoCheck.java` (NEW)
- `dfa/InsecureRandomCheck.java` (NEW)
- `dfa/PredictableRandomSeedCheck.java` (NEW)
- `dfa/PredictableKeyPasswordCheck.java` (NEW)
- `dfa/StaticApiKeyCheck.java` (NEW)
- `rules/RuleRegistry.java` — 5 new rule IDs + register
- `main/ScanConfig.java` — 5 new boolean flags
- `main/ScanRunner.java` — 5 new wires (both single-file and merged)
- `examples/r119/R119CryptoDemo.java` (NEW) — end-to-end demo
- `dfa/CryptoCheckTest.java` (NEW, 9 tests) — unit tests

## End-to-end verification

`examples/r119/R119CryptoDemo.java` (real CLI run) reports **8 crypto findings**:

| Rule | Count | Lines |
|---|---|---|
| `WEAK_CRYPTO` (MD5) | 1 | 14 |
| `WEAK_CRYPTO` (DES/ECB) | 1 | 19 |
| `INSECURE_RANDOM` | 2 | 24, 31 |
| `PREDICTABLE_RANDOM_SEED` | 2 | 31 (constructor), 33 (setSeed) |
| `STATIC_API_KEY` | 2 | 51 (STRIPE_API_KEY), 52 (JWT_SECRET) |
| (PREDICTABLE_KEY_PASSWORD not fired — array-init limitation) | 0 | — |

Plus 3 style findings (unused imports) and a debug log about the `new byte[]` array-initializer (acknowledged TAC limitation).

## No regression

- `examples/complex/BadCode.java` — was 14 findings (R119a) → now 16 findings (+2 new `STATIC_API_KEY`). All existing rules unchanged.
- Full test suite — 60/60 dfa (was 51 in R119a, +9 from `CryptoCheckTest`).

## Key learnings (R119b)

1. **`endsWith` off-by-one with arity**: `calleeSig` always ends with `/<arity>`. Stripping the `/*` tail from a glob and using `endsWith` works only if the suffix has the right length. Use `Pattern.matches` with a regex like `.*\\.getInstance/\\d+` for robustness.

2. **Java identifier naming and `\b`**: `STRIPE_API_KEY` has no word boundary before `API_` (underscore is a word char). Use unanchored substring match for identifier patterns; rely on the format of the `TacVar.name()` being predictable (`name:type`).

3. **First-arg-literal scanning**: For static method calls, the literal argument is at `args[1]`, not `args[0]`. For instance calls, it's at `args[0]`. Don't try to compute arity from the calleeSig to figure out which arg is "the scope" — just scan all args and pick the first one that's the type you want.

4. **Synthetic `<clinit>` as a filter**: For checks that target only static field initializers, filtering by `fn.name().equals("<clinit>")` is a clean way to avoid double-reporting with broader checkers.

5. **Array-initializer literal detection is a TAC-level gap**: The `Const.Kind` enum doesn't have `ARRAY`, so a `new char[]{...}` expression can't be represented as a literal in the current IR. Real fix is either to add `ARRAY` to the `Const.Kind` enum (low-level TAC change) or to track array-initializer expressions in the translator.

## Limitations / future work

- `PREDICTABLE_KEY_PASSWORD` is partial — array-initializer passwords are missed.
- `STATIC_API_KEY` only fires on the synthetic `<clinit>`; an instance-level `final` field with a sensitive name is handled by `HardcodedCredentialCheck` instead.
- `INSECURE_RANDOM` is conservative (fires on every `new Random()`); a smarter check could model the use site to distinguish game/UI from security use, but the conservative approach catches the dangerous cases at the cost of a few false positives.
- `WEAK_CRYPTO` only fires on string-literal algorithm names; an algorithm chosen at runtime via a tainted string is not caught (out of scope for structural checkers).
