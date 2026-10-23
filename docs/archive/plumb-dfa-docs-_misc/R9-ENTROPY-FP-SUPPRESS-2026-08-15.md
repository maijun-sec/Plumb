# R9 — Entropy + char-class FP suppression (CWE-259) — SHIPPED 2026-08-15

## Summary

| Metric                  | Pre-R9 | Post-R9 | Δ     |
|-------------------------|-------:|--------:|------:|
| CWE-259 fires (Juliet)  |     54 |      54 |   0   |
| FP suppression on real codebases | none | placeholder patterns | n/a |
| Confidence bucket       |     no |       3 (LOW/MEDIUM/HIGH) | new |
| Tests                   |    10  |      23 | +13 |

## What R9 added

### 1. `EntropyDetector` (NEW, 7332 bytes)

Static utility combining **Shannon entropy** + **character-class
diversity** to distinguish a real random password from a
placeholder:

```java
public static Confidence classify(String literal) {
    if (literal == null || literal.length() < 6) return MEDIUM;
    double h = shannonBitsPerChar(literal);
    int classes = distinctCharClasses(literal);
    if (h < 2.85) return LOW;          // placeholder with dups
    if (classes < 2) return LOW;        // single class identifier
    if (classes >= 3 && h >= 2.85) return HIGH;  // 3+ classes
    return MEDIUM;                      // 2 classes, decent entropy
}
```

**Per-character signal reference**:

```
  Literal        | Entropy | Classes | Verdict
  ---------------+---------+---------+--------
  "P@ssw0rd!"    |   3.00  | 4       | HIGH (real random password)
  "xK9!pL2@"     |   3.00  | 4       | HIGH
  "7e5tc4s3"     |   3.00  | 2       | MEDIUM
  "admin123"     |   3.00  | 2       | MEDIUM
  "password"     |   2.75  | 1       | LOW  (placeholder)
  "test1234"     |   2.75  | 2       | LOW  (placeholder)
  "abcdefgh"     |   3.00  | 1       | LOW  (single-class identifier)
```

### 2. `HardCodedPasswordSinkChecker.resolvePwdLiteral` FP filter

R9 — Suppresses LOW-confidence literals (placeholders). The
3-char minimum length filter from R6/R8 still applies first;
entropy is the second filter.

```java
if (EntropyDetector.isLikelyPlaceholder(literal)) {
    return null;  // suppress
}
```

### 3. Confidence in finding messages

Each finding now embeds the confidence bucket (LOW/MEDIUM/HIGH)
plus the entropy value:

```
Hard-coded password: DriverManager.getConnection called with
a literal string of length 8 as the password (literal: "P@ssw0rd!",
confidence: HIGH)
```

```
[flow]: hard-coded-pwd: DriverManager.getConnection(<...>,
<literal len=8>, entropy=2.81)
```

### 4. AI prompt updated

The R9 prompt explicitly enumerates the confidence bucket
semantics so the LLM dedup agent can prioritize HIGH-confidence
findings and ignore LOW:

```
R9 — Each finding carries a confidence bucket based on
Shannon entropy of the literal:
  - HIGH (> 4.0 bits/char): looks like a real random password.
    Almost always TP.
  - MEDIUM (3.0 - 4.0 bits/char): possibly a password. Verify
    against the codebase context.
  - LOW (< 3.0 bits/char, length >= 6): usually a placeholder,
    but kept for completeness. Often FP.
```

(Plus the actual algorithm: entropy + class diversity, not pure
entropy — the prompt simplifies for the LLM.)

## Algorithm rationale

The 8-char entropy ceiling is `log2(8) = 3.0` bits/char. So pure
Shannon entropy is **not** a strong discriminator for short
strings. A string of 8 unique chars always scores ~3.0 regardless
of whether it's "7e5tc4s3" (real random) or "abcdefgh"
(non-password identifier).

The key signal is **character-class diversity**:
- Real random passwords almost always mix 3-4 classes
  (lower + upper + digit + symbol)
- Placeholders typically use 1-2 classes
- All-lowercase / all-uppercase strings (regardless of entropy)
  are usually identifiers, not passwords

Combined with the 3-char minimum and the entropy-low filter
(catches "password" with 1 duplicate), this gives:

| Pattern | Entropy | Classes | R9 verdict |
|---------|---------|---------|-----------|
| `password` | 2.75 | 1 | **LOW** (suppress) |
| `test1234` | 2.75 | 2 | **LOW** (suppress) |
| `abcdefgh` | 3.00 | 1 | **LOW** (suppress) |
| `7e5tc4s3` | 3.00 | 2 | MEDIUM (fire) |
| `admin123` | 3.00 | 2 | MEDIUM (fire) |
| `P@ssw0rd!` | 3.00 | 4 | **HIGH** (fire, high conf) |
| `xK9!pL2@` | 3.00 | 4 | **HIGH** (fire, high conf) |

## Real Juliet CWE-259 baseline (post-R9)

**No regression** — all 54 R8 fires still fire. Juliet CWE-259
uses `data = "7e5tc4s3"` which is MEDIUM (2 classes, 3.0 entropy),
not LOW, so the entropy filter does not suppress it.

| Sink family     | Fires | Verdict |
|-----------------|------:|---------|
| driverManager   |    18 | unchanged |
| kerberosKey     |    18 | unchanged |
| passwordAuth    |    18 | unchanged |
| **Total**       | **54**| no change |

## R9 unit tests (13/13 pass)

| Test | What it covers |
|------|----------------|
| `shannonEntropy_randomStringIsHigh` | 8 unique chars → 2.9-3.0 |
| `shannonEntropy_placeholderIsLow` | "password" → 2.5-3.0 |
| `shannonEntropy_allSameCharIsZero` | "aaaaaaaa" → 0 |
| `shannonEntropy_emptyOrNullIsZero` | "" / null / "a" → 0 |
| `classify_randomPasswordIsHIGH` | "P@ssw0rd!" / "xK9!pL2@" → HIGH |
| `classify_twoClassesIsMEDIUM` | "7e5tc4s3" / "admin123" → MEDIUM |
| `classify_placeholderIsLOW` | "password" / "test1234" / "abcdefgh" / "ABCDEFGH" → LOW |
| `classify_shortStringIsAlwaysMedium` | "ab1" / "12345" → MEDIUM |
| `isLikelyPlaceholder_filtersLongPlaceholders` | smoke test of predicate |
| `highEntropyLiteralFires` | integration: random 8-char fires |
| `lowEntropyLiteralSuppressed_r9New` | "password" placeholder suppressed |
| `shortLiteralStillFires_r9ShortBypass` | 4-char bypass (entropy unreliable) |
| `findingMessageIncludesConfidence_r9New` | "P@ssw0rd!" → "confidence: HIGH" |

Plus R8 unit tests (10/10 still pass) — no regression on R8.

## R6 + R7 + R8 + R9 累计 (2026-08-15)

| Round | Checker / change | Coverage effect |
|-------|------------------|-----------------|
| R6 | HardCodedPasswordSinkChecker (DFA) — DriverManager only | CWE-259 18 fires |
| R7 | DBCredentialSinkChecker (taint) — file-sourced | CWE-256 38 fires |
| R8 | HardCodedPasswordSinkChecker (DFA 3 sinks) | CWE-259 54 fires (+200%) |
| **R9** | **EntropyDetector + class-diversity FP filter** | **CWE-259 54 fires (no regression), +3 confidence bucket** |

R9's real value is **real codebase precision** (placeholder
suppression) — not Juliet recall. On Juliet all 54 still fire;
on a real codebase, obvious placeholders like
`getConnection(url, user, "password")` would be suppressed
without affecting real random password detection.

## Cross-references

- `plumb-dfa/.../checkers/EntropyDetector.java` (NEW, 7332 bytes)
- `plumb-dfa/.../checkers/EntropyDetectorTest.java` (NEW, 9252 bytes)
- `plumb-dfa/.../checkers/HardCodedPasswordSinkChecker.java` (UPDATED)
  - `resolvePwdLiteral` + EntropyDetector call
  - 3 finding emissions updated with confidence bucket + entropy value
  - AI prompt updated

## Next steps (R10+)

- **R10** — Real codebase validation (curl/zlib/openssl) — stable
  id `plumb.dfa.hard-coded-password-sink` enables CI baselines
- **R10** — Cross-fn summary-fold (R5+) — unlock 126 more CWE-259
  fires (5-day effort, high ROI)
- **R11** — Refactor: extract shared `LiteralSourcedTracker` from
  HardCodedPasswordSinkChecker / HardCodedKeyChecker (DRY)
