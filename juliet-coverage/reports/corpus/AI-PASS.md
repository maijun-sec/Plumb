# The AI false-positive pass on the Juliet corpus

## What the pass is

The AI pass judges every finding the engine reports and drops the ones it
calls false positives. The question is how much of the corpus's false
positive count it removes, and how many true positives it destroys while
doing it.

## How it is measured

For each CWE the SAME sample of Juliet files is scanned twice:

- **before** -- the per-CWE config, the AI off
- **after** -- the per-CWE config, the AI on (`--ai-dedup`)

The files, the rules, the call-graph strategy and the jar are identical,
so the difference between the two numbers is the AI pass and nothing
else.

The sample is chosen by the ROLE of the method the finding sits in,
which the engine's own `sink_function` field names: `bad():void` for a
vulnerable method, `goodG2B1():void` for a fixed one. Half the sample
goes to the files whose findings are in `bad*` methods and half to the
files whose findings are in `good*` methods.

The sample has to hold both sides. A sample of only the files where the
engine was right has no false positive for the AI to remove, and a
false-positive reduction cannot be measured on it. Taking the first few
files by name finds only the clean ones -- in CWE-190 the first four
files by name carry findings in `bad()` methods only, and the
false-positive count comes out as zero however long the AI runs.

The choice never looks at the AI's verdict, so the sample cannot flatter
the AI.

A file with more than `--max-findings-per-file` findings is skipped.
One Juliet file in CWE-113 carries 6,600 findings, and four such files
are 26,000 judgements -- more than a day's budget.

## What the providers can and cannot do

| provider | outcome |
| --- | --- |
| **MiniMax-M3** | the requested primary model. It judges correctly: it reads the source with the tool, then answers with a `FINAL:` verdict. Its Token Plan usage cap was reached during the measurement, and an exhausted cap cannot be retried around -- the retry absorbs a per-second rate limit, not a usage cap. Every request after the cap was refused with HTTP 429, error 2056. |
| **glm-4-flash** | the fallback. It answers, but it does not produce a `FINAL:` verdict: it keeps calling tools and stays silent until the 30-turn cap, so every finding comes back `UNKNOWN\|max-turns-exceeded`. A model that cannot answer is not a substitute for the model that can. |
| glm-4.6, glm-4.5, glm-5 | rate-limited on this account. |

**A rate-limited request is not a verdict.** The engine records it as an
`UNKNOWN`, which is indistinguishable from the model being unsure. The
harness counts the rate-limited requests in the scan log and reports the
CWE as `rate_limited` rather than as a measurement, so a provider
outage can never be reported as a result.

## The coverage of this measurement

The pass was run against the corpus with the primary model until its
quota ran out, and against a small sample of the corpus afterwards. The
runs that produced a usable verdict are in `ai-pass.json`; the runs that
were rate-limited are in the same file with their status.

The rest of the corpus is NOT measured by the AI pass. The scripts are in
`../scripts/measurement/ai_pass_juliet.py` and the run is reproducible
once a provider with quota is available:

```bash
python juliet-coverage/scripts/measurement/ai_pass_juliet.py \
  --provider minimax \
  --findings <out>/findings \
  --rule-cwes live-rule-cwes.tsv \
  --out <out>/ai
```

## Reproducing the numbers

The `before` and `after` numbers in `ai-pass.json` are scored by the
same scorer as the corpus measurement, against the same method-level
ground truth, with the denominator restricted to the sampled files. A
finding whose rule is about ANOTHER CWE is counted separately: the AI
does remove some of those, and that removal is real, but it is not a
change in the CWE's own false-positive count.
