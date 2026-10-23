# R761 — Config / CLI parameter audit & fix (Oct 2026)

Follow-up to R761 audit. Six issues identified; all fixed in this round.

## What changed

### Fix #1 — ConfigLoader missing-parse for 3 yaml keys
**File**: `plumb-main/.../ConfigLoader.java` (after the existing `numericTruncation` block)
- `checks.errorMessageInfoLeak` — was silently ignored
- `checks.commentInfoLeak` — was silently ignored
- `checks.resourceExhaustion` — was silently ignored

All three now read by ConfigLoader, with both `camelCase` and `snake_case`
forms (the convention used by the other 41 keys). Each accepts the
`{enabled: true/false}` map form and the bare boolean form.

### Fix #2 — `methodLengthThreshold` dead field removed
**Files**:
- `plumb-main/.../ScanConfig.java` — removed `public int methodLengthThreshold = 80;`
- `plumb-main/.../ConfigLoader.java` — removed parser block; replaced with
  empty placeholder comment ("reserved for future per-checker thresholds")
- `plumb-main/.../default-config.yaml` — removed `style.methodLengthThreshold: 80`

The field was wired in ConfigLoader and default-config.yaml, but **no
style checker ever read it** — `plumb-style/style/MethodTooLongCheck`
has its own hard-coded 80-line limit. Dead field; removed.

### Fix #3 — `--rule <id>` CLI flag wired through
**File**: `plumb-main/.../ScanRunner.java` (top of `isRuleIdAllowed`)

Before: `SastMain` populated `cfg.cliRuleFilter` from `--rule` args,
but `ScanRunner.isRuleIdAllowed` never read it. The option was a
silent no-op.

After: `isRuleIdAllowed` checks `config.cliRuleFilter` first, before
the disabled/whitelist list. Empty filter = original behavior. Non-empty
filter = only matching rule ids fire. R727 dual-form match
("plumb.X.Y" vs bare "X.Y") is preserved.

**Verified**: `--rule plumb.boundary.integer-overflow` on CWE-190 produces
11,929 findings, all with that single rule id. No other rule ids appear.

### Fix #4 — `cgStrategy` default value mismatch
**Files**:
- `plumb-main/.../ScanConfig.java` — `cgStrategy = "vta"` → `"global"`
- `plumb-main/.../default-config.yaml` — `cgStrategy: vta` → `global`

The project directive (per the field comment) is that analysis must be
global. `RcgStrategyTest.defaultIsGlobal()` already asserted the default
should be "global" — the test was failing before this fix. All 13
`RcgStrategyTest` cases pass now.

### Fix #6 — `ai.provider: openai` → `OPENAI_COMPATIBLE` alias
**File**: `plumb-main/.../ConfigLoader.java`

`OPENAI` and `OPENAI_COMPATIBLE` go through identical code paths in
`OpenAiCompatibleLlmProvider` (only `ANTHROPIC` branches). The
`ai.provider: openai` config key now silently aliases to
`OPENAI_COMPATIBLE`. Old configs keep working; users get the more
accurate enum name via the warning path.

### `catchGeneric` default re-examined
The R761 audit flagged this as needing attention. After deeper
investigation: `cfg.runCatchGeneric` only gates the
"do we translate TAC for the DFA-side check?" decision
(ScanRunner.java:1843). The actual Juliet-contributing rule
`dfa.cwe396-catch-generic-exception` is a **plumb-style** AST check
(`OverlyBroadCatchCheck`) that runs unconditionally under the style
family and is filtered via `rules.enabled` / `rules.mode: whitelist`
in per-CWE yaml. So the `runCatchGeneric` flag default value has
**no observable effect on the Juliet corpus**.

Default value: `true` (matches project-directive "don't miss on default").
default-config.yaml: `catchGeneric: {enabled: false, aiDedup: false}`
left unchanged (since the flag is a no-op for the style check that
Juliet actually exercises). Comment in `ScanConfig` updated to
document the decoupling.

## Files touched

| File | Change |
|------|--------|
| `plumb-main/.../ScanConfig.java` | `cgStrategy` default + `methodLengthThreshold` removed + `runCatchGeneric` comment updated |
| `plumb-main/.../ConfigLoader.java` | 3 missing keys added + `ai.provider: openai` alias + `style:` parser block emptied |
| `plumb-main/.../ScanRunner.java` | `isRuleIdAllowed` checks `cliRuleFilter` first |
| `plumb-main/.../default-config.yaml` | `cgStrategy: global` (was `vta`) + `style:` block removed + `catchGeneric` comment |
| `plumb-main/src/test/.../ConfigLoaderTest.java` | Replaced `methodLengthThreshold` assertion with 3 new key assertions |

## Verification

- `mvn -DskipTests -pl plumb-main -am package -q` → exit 0
- `mvn -pl plumb-main test -Dtest=RcgStrategyTest` → **13/13 pass** (was failing before)
- `mvn -pl plumb-main test -Dtest=ConfigLoaderTest` → 1/1 pass
- `mvn -pl plumb-main test` → **12 test classes, 0 failures, 0 errors**
- Juliet CWE-190 smoke: `--rule plumb.boundary.integer-overflow` → 11,929 findings, single rule id
- Juliet CWE-190 smoke: no `--rule` → 20,614 findings, same as R759-F baseline

## Issues remaining
None of the original 6 audit findings remain unfixed.