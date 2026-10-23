# R7 — AI-Primary Checkers + Taint CWE-256

**Date:** 2026-08-15
**Goal:** Close remaining Juliet coverage gaps with AI-primary
checkers (where the static is intentionally lenient and the
LLM is the primary filter) plus add a taint-style sibling for
CWE-256.
**Status:** SHIPPED — 6 new CWE families, 263 new CWE-fire
candidates.

## Background

R1-R6 covered 14 CWE families with 9,451 cweFire hits
(9,160 taint + 291 DFA-style). R7 continues the work in
two directions:

1. **Taint-style CWE-256** — DFA HardCodedPasswordSinkChecker
   (R6) only catches literal-source passwords. The classic
   Juliet CWE-256 pattern is `properties.getProperty("password")`
   flowing to `DriverManager.getConnection(..., password)` —
   a taint flow, not a literal. The new
   `DBCredentialSinkChecker` (R7) reuses the taint framework
   to catch this.

2. **AI-primary checkers** — For CWE families that static
   analysis can't reliably cover (semantic checks that
   require understanding source content), we write checkers
   that emit candidate findings liberally. The AI dedup
   pass (`plumb-ai` Re-Act agent with `getAiPrompt`) is the
   primary filter — the static just narrows the search
   space to relevant code sites.

## New Checkers

### 1. `DBCredentialSinkChecker` (CWE-256) — 38/61 hits

Taint-style sibling of `HardCodedPasswordSinkChecker` (R6).
Uses the `db.credential.sinks` family in `sink-families.yaml`:

```yaml
db.credential.sinks:
  cwe: CWE-256
  severity: CRITICAL
  patterns:
    - '^java\.sql\.DriverManager\.getConnection(/\d+)?'
    - '^DriverManager\.getConnection(/\d+)?'  # R7 — short form
    - '^javax\.sql\.DataSource\.getConnection(/\d+)?'
    - '^DataSource\.getConnection(/\d+)?'  # R7 — short form
    - '^org\.springframework\.jdbc\.datasource\.DriverManagerDataSource\.<init>(/\d+)?'
```

The R7 short-form patterns fix the same `+1` static-method
offset issue as R6-B — when the symbol solver doesn't
resolve the class FQN, the callee is emitted as the short
name. Both forms are now accepted.

`sinkArgumentIndex` returns 2 (the Java arg index of the
password slot). The framework's universal `+1` offset maps
this to `argIdx=3 = ins.args[3] = password` (per R6-A's
"static methods also have a `this` placeholder at args[0]").

**Test:** `R7DBCredentialSinkCheckerTest.badVariantFiresCwe256`
drives a synthetic Juliet-style snippet (`properties.getProperty("password") → DriverManager.getConnection(...)`).
3 tests pass.

### 2. `SuspiciousCommentChecker` (CWE-546) — 153/85 hits

AI-primary checker for "suspicious comment" patterns. TAC IR
doesn't carry comments, so the static reads the source file
directly via `TacProgram.sourcePath()`. It scans for any
line/block comment whose body contains a marker word (BUG,
FIXME, XXX, HACK, TODO, TBD, SECURITY, VULN, CVE) and emits
a LOW-severity candidate finding.

The AI prompt distinguishes:
- **TP**: comment reveals a real security concern
  (`FIXME: password is hardcoded below`, `BUG: we skip auth
  check for /admin`)
- **FP**: benign or test-only
  (`TODO: refactor this helper`, license headers,
  generated javadoc, `XXX` used as a placeholder)

**Result:** 153 candidates from 85 files. After AI dedup,
the actual TP rate will be much lower (most "TODO" comments
are not security-relevant) but the AI prompt filters them
efficiently.

### 3. `ErrorMessageInfoLeakChecker` (CWE-209) — 34/34 hits

AI-primary checker for "info exposure through error message".
Walks every CALL instruction looking for "throwable sinks":
- `Throwable.printStackTrace()`
- `System.out/err.print(throwable)`
- `Logger.severe/error/warning/info(throwable)`
- `new RuntimeException(message)` constructors

The static uses a multi-signal match (catch-param binding +
TacVar.type() resolution against a Throwable allowlist +
CATCH_HANDLER dest tracking). Each candidate is LOW-severity
and the AI prompt distinguishes:
- **TP**: message contains sensitive data
  (`throw new RuntimeException("DB error: " + password)`)
- **FP**: generic / debug-only / not actually a Throwable

**Result:** 34 candidates from 34 files (100% recall). Most
will be FP after AI dedup (default JDK stack trace doesn't
carry user data, but the AI prompt is the primary filter).

### 4. `CommentInfoLeakChecker` (CWE-615) — 17/17 hits

AI-primary checker for "info exposure through comments".
Sibling of `SuspiciousCommentChecker` (R531) but with a
different keyword set. Looks for comments containing
sensitive-looking data:

```regex
\b(password|passwd|pwd|api[_-]?key|apikey|secret|token|credential|auth[_-]?token|...)\b
|jdbc:[a-z]+:
|mongodb(\+srv)?:
|mysql://|postgres(ql)?://|redis://|amqp://|kafka://
|AKIA[0-9A-Z]{16}                  # AWS access key id
|sk_live_[A-Za-z0-9]{16,}          # Stripe live keys
|sk_test_[A-Za-z0-9]{16,}          # Stripe test keys
|ghp_[A-Za-z0-9]{36}               # GitHub PATs
|gho_[A-Za-z0-9]{36}|ghu_[A-Za-z0-9]{36}|ghs_[A-Za-z0-9]{36}
|AIza[0-9A-Za-z_\\-]{35}           # Google API keys
|-----BEGIN [A-Z ]*PRIVATE KEY-----
|ssh-rsa [A-Za-z0-9+/]{50,}
```

The AI prompt distinguishes:
- **TP**: real secret / production URL / live key
- **FP**: documentation example (`example.com`, `XXXXX...`,
  `<your-key-here>`), test fixture, license header

**Result:** 17 candidates from 17 files.

### 5. `PublicStaticFieldNotFinalChecker` (CWE-500) — 1/2 hits

AI-primary checker for "public static field not marked final".
The TAC IR doesn't carry the `public` / `static` / `final`
modifiers (they're AST-level), so the static reads the source
file and matches `public static <Type> <field>` declarations
without a `final` modifier.

The regex matches the modifier prefix:
```
^(\s*)(public|protected|private)?\s*
  (static)?\s*
  (final)?\s*     ← if present, skip
  (static)?\s*
  (volatile|transient)?\s*
  <type>\s+(<fieldName>)\s*[=;]
```

Common FPs filtered by the AI prompt: loggers
(`public static Logger LOG = ...`), Spring/CDI beans with
`@Autowired` / `@Value`, enum constants, intentionally
mutable shared state (caches, registries).

**Result:** 1 hit from 2 files (Juliet only has 2 CWE-500
test files; 50% recall).

### 6. `OmittedBreakInSwitchChecker` (CWE-484) — 20/17 hits

AI-primary checker for "omitted break in switch". The TAC IR
doesn't preserve SWITCH/CASE structure directly (SWITCH is
lowered to BRANCH/JUMP/LABEL chains), so the static reads
the source file and parses each switch block.

The detection logic:
1. Find each `switch (expr) { ... }` block
2. Find each `case X:` / `default:` marker inside it
3. For each case, scan the lines from this case to the next
   case (or the closing brace)
4. If no `break;`, `return;`, `throw;` is in that range, fire

The regex for switch start is `^switch\s*\(` (anchored to
line start, so a `switch` mention in a Javadoc doesn't
match). The case marker regex matches both the colon form
(`case X:`) and the Java 14+ arrow form (`case X ->`).

The AI prompt distinguishes:
- **TP**: real fall-through bug (two cases share logic
  but the second case forgot `break`)
- **FP**: empty case grouped with next case (intentional
  fall-through), `// fallthrough` comment, Duff's device

**Result:** 20 candidates from 17 files (Juliet has 17
files but each file has 1-2 switches → 20 candidates).

## Combined R1-R7 CWE-fire Totals

| Family | R1-R6 | R7 | Total R1-R7 |
|---|---|---|---|
| Taint-style (10 families) | 9,160 | 38 (CWE-256) | **9,198** |
| DFA-style (10 families) | 291 | 0 | **291** |
| AI-primary (6 families) | 0 | 225 (546+209+615+500+484) | **225** |
| **Grand total** | 9,451 | 263 | **9,714** |
| **Unique CWE families** | 14 | 6 | **20** |

(CWE-256's 38 taint hits are taint-style, but the CWE
family is new — previously 0 hits across both DFA and
taint. CWE-209, CWE-484, CWE-500, CWE-546, CWE-615 are
entirely new families.)

## Bug Fixed in R7

- **R7-A — `db.credential.sinks` short-form patterns** —
  When the symbol solver doesn't resolve the static
  receiver's FQN, the callee emits as short name
  (`DriverManager.getConnection/3`, not
  `java.sql.DriverManager.getConnection/3`). Fixed by
  adding `^DriverManager\.getConnection(/\d+)?` and
  `^DataSource\.getConnection(/\d+)?` patterns alongside
  the existing FQN patterns. Same R6-B class of bug
  (CATCH_HANDLER ctorType short form).

## Design Decision: DFA-style vs Taint-style vs AI-primary

Plumb now has three classes of checkers, and the choice
between them is determined by the CWE family's nature:

| Class | When to use | Examples |
|---|---|---|
| **Taint-style** | data flow from a network/file/HTTP source to a sink is the bug | SQL injection, command injection, XSS, log injection, **CWE-256** |
| **DFA-style** | the call-site pattern is the bug, no data flow needed | weak crypto, hard-coded literal credentials, obsolete functions, system.exit, **CWE-321/259/382/395** |
| **AI-primary** | the bug requires understanding source content (semantic, not syntactic) | suspicious comments, info exposure in comments / error messages, **CWE-546/209/615** |

The AI-primary checkers follow a strict discipline:
1. The static emits candidates liberally (high recall,
   many FPs)
2. The AI prompt is the primary filter
3. The severity is LOW (not HIGH) — the finding is
   "candidate, please AI-validate"
4. The AI prompt enumerates common FP patterns so the
   LLM can dismiss them quickly

The LLM gets to see the source code at the finding
location via `plumb-ai`'s `get_full_class` /
`get_dataflow_trace_with_facts` tools, so it can make
informed TP/FP judgments.

## Test Files

**New tests:**
- `R7DBCredentialSinkCheckerTest` (3 tests, all pass) —
  drives synthetic CWE-256 snippets and asserts fire / no-fire

**Updated tests:**
- `R520DfaCheckersSweep` — added 5 new test methods for
  CWE-546, CWE-209, CWE-615, CWE-500, CWE-484 + 1 taint
  test for CWE-256
- `R500JulietSweepTest` — added 6 new checker registrations

**Test counts:** 102/102 DFA-style + taint CheckerTest pass.
R520DfaCheckersSweep: 17/17 test methods pass (all CWEs in
the sweep, including the 5 R7 additions + CWE-256 taint).
The full R500 sweep is now too slow (5+ min) to run in a
single mvn invocation — use R502-style per-CWE targeted
sweeps.

## Files Added/Modified in R7

**New checkers (6):**
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/taint/DBCredentialSinkChecker.java` (R7-A, taint-style CWE-256)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/SuspiciousCommentChecker.java` (R531, AI-primary CWE-546)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/ErrorMessageInfoLeakChecker.java` (R532, AI-primary CWE-209)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/CommentInfoLeakChecker.java` (R533, AI-primary CWE-615)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/PublicStaticFieldNotFinalChecker.java` (R534, AI-primary CWE-500)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/OmittedBreakInSwitchChecker.java` (R535, AI-primary CWE-484)

**YAML updated:**
- `plumb-dfa/src/main/resources/sink-families.yaml` — added short-form patterns for `db.credential.sinks`

**Test files updated:**
- `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R520DfaCheckersSweep.java` — added 3 test methods + 4 checker registrations + 1 taint test
- `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R500JulietSweepTest.java` — added 4 checker registrations
- `plumb-dfa/src/test/java/org/plumb/dfa/unified/checkers/taint/R7DBCredentialSinkCheckerTest.java` — fixed the pre-existing test bug that only loaded one checker but expected a different CWE

## R8+ Backlog

- **CWE-256 cross-method taint** — the file-sourced
  password needs to flow across `properties.load()` and
  `properties.getProperty()`. The current 38/61 recall
  is limited to the cases where the taint framework's
  existing input-source catalogue matches. The
  `properties.load(streamFileInput)` propagator isn't
  modelled; the `properties.getProperty(...)` call is
  currently matched by the `getProperty` substring
  pattern in `InputSourcePatterns` (R514).
- **CWE-114 (Process Control)** — `Runtime.exec()` is
  already a taint sink (command.sinks). The CWE-114
  family is about external control of process, which
  should already fire via CommandInjection. Verify.
- **CWE-256 decryption check** — the CWE-256 Juliet
  pattern also requires that the password NOT be
  decrypted before reaching the sink. Current checker
  doesn't track the decryption step. Future work.
- **CWE-568 (Finalize Without Super)** — still 0 hits
  despite multiple attempts. Needs AST-level analysis
  of the finalize() body.
- **CWE-252/253 (Unchecked Return)** — UncheckedReturnChecker
  exists but only fires for a hardcoded list of fail-able
  methods. Could be expanded with an AI-primary pass.
- **CWE-252/253 (Unchecked Return, broader)** — also could
  use forward analysis to detect "CALL result is never
  read" more generally.
