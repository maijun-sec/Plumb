# The Juliet corpus measurement

The scripts here measure the engine against the real Juliet_Java testcase
corpus: 112 CWE categories, 46,793 testcase files.

They are a MEASUREMENT harness, not a product feature. Nothing in
`plumb-main` depends on them.

## What the measurement says

A Juliet testcase file declares the vulnerable method (`bad*`) and the
fixed one (`good*`). So the unit of the measurement is the METHOD:

| | |
| --- | --- |
| a finding in a `bad*` method | a true positive |
| a finding in a `good*` method | a false positive |
| a `good*` method with no finding | a true negative |
| a `bad*` method with no finding | a false negative |

A file-level measurement calls every finding a true positive and reports
a recall above 1.

Only a finding whose rule is **about** the CWE under test is scored. A
finding from a rule labelled with another CWE is counted separately,
because counting it as a false positive would measure the breadth of the
per-CWE whitelist rather than the engine's precision.

## The four outcomes

A coverage number alone does not say what was measured, so every CWE is
given one of these, and the report keeps them apart:

| outcome | meaning |
| --- | --- |
| MEASURED | the CWE's own rules ran and found the defect |
| VALID ZERO | the CWE's own rules ran and found nothing |
| CONFIG GAP | the engine has rules for the CWE, the config enables none of them |
| COVERAGE GAP | the engine has no rule for the CWE at all |
| NOT MEASURED | the scan did not finish |

A report that collapses the last three into "recall 0.000" claims the
engine cannot find the defect when the truth is that nothing was
measured.

## Running it

The corpus lives outside the repository, at
`D:\work\tmp\Juliet_Java\src\testcases`, with the ground truth in
`D:\work\tmp\Juliet_Java\testcases_detail.csv`.

### 1. Scan

```bash
python juliet-coverage/scripts/measurement/split_run.py
```

splits the 112 CWEs between two workers, balanced by file count, and
writes `run_a_cwes.txt` and `run_b_cwes.txt`. The split is
longest-processing-time-first, so neither worker finishes early and
idles.

```bash
python juliet-coverage/scripts/measurement/measure.py --scan-only \
  --out <out>/a --findings-dir <out>/findings --status-dir <out>/status-a \
  <the CWEs from run_a_cwes.txt>
```

Two workers at a time: each scan peaks at about 4 GB of RAM. The
findings go on a D: volume -- they are hundreds of megabytes per CWE, and
the C: volume is nearly full.

`--scan-only` writes the findings and a per-CWE status file, and stops.
The statistics are a separate step, so a change to the statistics never
costs a re-scan, and the findings are never held in memory.

### 2. Score

```bash
python juliet-coverage/scripts/measurement/score.py \
  --findings <out>/findings --status <out>/status-a --status <out>/status-b \
  --rule-cwes live-rule-cwes.tsv --out <out>/score
```

The rule table comes from the engine's own `--list-rule-cwes` output:

```bash
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar \
  --list-rule-cwes > live-rule-cwes.tsv
```

### 3. Audit the configs

```bash
python juliet-coverage/scripts/measurement/whitelist_audit.py \
  --findings <out>/findings --rule-cwes live-rule-cwes.tsv --out <out>/audit
```

The audit is a PORT of the engine's own whitelist filter, and the port
is validated by the scan: every rule that fired in a CWE's scan must be
a rule the port says the whitelist selects. A port that disagrees with
the engine fails loudly instead of quietly reporting the wrong cause.

### 4. Report

```bash
python juliet-coverage/scripts/measurement/report_juliet.py \
  --score <out>/score/score.json \
  --audit <out>/audit/whitelist-audit.json \
  --rule-cwes live-rule-cwes.tsv \
  --out juliet-coverage/reports/corpus
```

### The AI false-positive pass

```bash
python juliet-coverage/scripts/measurement/ai_pass_juliet.py \
  --provider minimax --findings <out>/findings \
  --rule-cwes live-rule-cwes.tsv --out <out>/ai
```

For each CWE the SAME sample of files is scanned twice -- once with the
AI off and once with it on -- so the only difference between the two
numbers is the AI pass. The sample is chosen by the ROLE of the method
the finding sits in, which the engine's own `sink_function` field names,
so the sample holds both the files where the engine was right and the
files where it was wrong. A sample of only the first would have nothing
for the AI to remove, and a false-positive reduction cannot be measured
on it.

A rate-limited request is NOT a verdict: the provider's plan has a
request rate limit, and a rate-limited request comes back as UNKNOWN,
which is indistinguishable from the model being unsure. The harness
counts the rate-limited requests and reports the CWE as rate-limited
rather than as a measurement.

## The per-CWE configs

`juliet-coverage/configs/CWE<nnn>.yaml` whitelists the rules a
measurement runs.

The whitelist is derived from the engine's OWN rule table: a rule
belongs to a CWE when the rule's own CWE label names it. That is a fact
about the engine, not about one run.

It used to be derived from ONE past scan -- the rules that happened to
fire on that CWE's directory on that day. That was circular: the
whitelist decides which rules run, and a scan of the whitelist decided
the whitelist. So a CWE whose rule had not fired yet was written down
with a whitelist that did not contain its own rule, and could then never
be measured. The measurement found most of the CWEs in that state; the
count is in [the corpus report](../../reports/corpus/JULIET-REPORT.md).

`scripts/check_juliet_configs.py` is the gate, and
`scripts/mutate_juliet_configs.py` proves the gate bites.
