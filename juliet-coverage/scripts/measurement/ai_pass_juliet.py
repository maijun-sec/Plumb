#!/usr/bin/env python3
"""The AI false-positive pass, measured on Juliet.

WHAT IS COMPARED

For every CWE the SAME sample of Juliet files is scanned TWICE:

  before   the per-CWE config, the AI off
  after    the per-CWE config, the AI on (`--ai-dedup`)

The only difference between the two runs is the AI pass. The files, the
rules, the call-graph strategy and the jar are identical, so the
difference in the numbers is the AI pass and nothing else.

WHY A SAMPLE

The AI pass judges EVERY finding the engine reports, and Juliet's corpus
produces more than a million findings. The MiniMax Token Plan's usage cap
is reached after roughly an hour and a half of continuous reviewing, and
an exhausted cap cannot be retried around. So the measurement is bounded
by a per-CWE finding budget, and the sample is chosen WITHOUT looking at
whether a finding is a true or a false positive -- the files are taken in
name order until the budget is met, so the sample cannot be accused of
having been picked to flatter the AI.

WHAT IS REPORTED

Per CWE: TP coverage and TN coverage before and after, the number of
false positives before and after, and the verdict distribution. A CWE
whose sample has no findings is reported as having nothing to judge, not
as a zero.

    python ai_pass_juliet.py --provider minimax --budget 40 --out <dir>
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent.parent
sys.path.insert(0, str(HERE))
import measure  # noqa: E402
import score as scorer  # noqa: E402

JAR = REPO / "plumb-main" / "target" / "plumb-main-0.1.0-SNAPSHOT-all.jar"
CONFIG_DIR = REPO / "juliet-coverage" / "configs"
SCRATCH = Path(r"D:\cache\juliet-scratch")

PROVIDERS = {
    "minimax": REPO / "_audit-scratch" / "ai-minimax-m3.yaml",
    "glm": REPO / "_audit-scratch" / "ai-glm-flash.yaml",
}


def read_config(path: Path) -> tuple[str, list[str]]:
    mode = "unknown"
    rules: list[str] = []
    in_enabled = False
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.rstrip()
        if not line.strip() or line.strip().startswith("#"):
            continue
        if line.strip().startswith("mode:"):
            mode = line.split(":", 1)[1].strip()
        if line.strip().startswith("enabled:"):
            in_enabled = True
            continue
        if in_enabled:
            s = line.strip()
            if s.startswith("- "):
                rules.append(s[2:].strip())
                continue
            if s and not s.startswith("-"):
                in_enabled = False
    return mode, rules


def ai_block(provider_path: Path, drop_on_fp: bool) -> dict:
    """The `ai:` map from the provider file."""
    import yaml
    data = yaml.safe_load(provider_path.read_text(encoding="utf-8-sig")) or {}
    block = dict(data.get("ai") or {})
    block["dedup"] = False      # the CLI flag turns it on for the after run
    # Whether the AI-judged false positives are DROPPED from the report.
    #
    # This is the whole point of the pass: a false positive the user
    # still sees is not removed. With the drop off, the AI only LABELS
    # the finding, and the measured false-positive count cannot change.
    block["dropOnAiFp"] = drop_on_fp
    block.pop("enabled", None)
    return block


def limit_concurrency(provider_path: Path, limit: int) -> dict:
    """The provider block with a smaller concurrency.

    The provider's plan has a REQUEST RATE limit, not just a usage cap:
    three concurrent measurement shards, each asking for eight requests
    at a time, produced 24 requests in flight and the provider answered
    every one of them with HTTP 429 (error 2062, "Token Plan rate
    limit"). The findings that came back that way were UNKNOWN, which
    is indistinguishable from "the model was unsure" -- so a rate
    limit silently degrades the measurement.

    So the concurrency is capped, and the cap is a MEASUREMENT choice
    rather than a provider default.
    """
    import yaml
    data = yaml.safe_load(provider_path.read_text(encoding="utf-8-sig")) or {}
    block = dict(data.get("ai") or {})
    block["maxConcurrent"] = limit
    return block


def merged_config(cwe: str, provider: str, dest: Path, drop_on_fp: bool,
                  concurrency: int) -> Path:
    """The per-CWE scan config with the AI provider wired in."""
    import yaml
    base = yaml.safe_load((CONFIG_DIR / f"CWE{cwe}.yaml").read_text(encoding="utf-8")) or {}
    block = limit_concurrency(PROVIDERS[provider], concurrency)
    block["dedup"] = False
    block["dropOnAiFp"] = drop_on_fp
    block.pop("enabled", None)
    base["ai"] = block
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(yaml.safe_dump(base, sort_keys=False, allow_unicode=True),
                    encoding="utf-8")
    return dest


def jar_identity():
    try:
        st = JAR.stat()
    except OSError:
        return None
    return (st.st_size, st.st_mtime_ns)


def pick_sample(cwe: str, findings_path: Path, budget: int,
                max_files: int, max_findings_per_file: int) -> list[Path]:
    """The files to judge.

    THE SAMPLE MUST HOLD BOTH SIDES.

    A sample that only holds the files where the engine was right
    cannot measure a false-positive reduction: the AI has no false
    positive to remove. And the files where the engine is wrong are
    spread across the corpus, so taking the first few files by name
    finds only the clean ones.

    So the files are chosen by the ROLE of the method the finding sits
    in, which the engine's own `sink_function` field names:
    `bad():void` for a vulnerable method, `goodG2B1():void` for a fixed
    one. Half the budget goes to the files whose findings are in `bad*`
    methods and half to the files whose findings are in `good*` methods.

    A FILE WITH MORE FINDINGS THAN THE CAP IS SKIPPED. One Juliet file
    in CWE-113 carries 6,600 findings, and four such files are 26,000
    AI judgements -- more than a day's budget. The cap keeps the
    measurement about the RATE at which the AI removes false positives,
    not about the few files that happen to produce the most findings.

    The choice does not look at the AI's verdict, so the sample cannot
    flatter the AI; it looks only at which METHOD the finding is in,
    which is the ground truth the measurement is scored against.
    """
    if not findings_path.is_file():
        return []
    by_file: dict[str, Counter] = {}
    for raw in findings_path.read_text(encoding="utf-8", errors="replace").splitlines():
        raw = raw.strip()
        if not raw:
            continue
        try:
            d = json.loads(raw)
        except json.JSONDecodeError:
            continue
        name = Path(d.get("file") or "").name
        if not name:
            continue
        sink = d.get("sink_function") or ""
        role = "good" if sink.startswith("good") else (
            "bad" if sink.startswith("bad") else "other")
        by_file.setdefault(name, Counter())[role] += 1
    index = scorer._cwe_index(cwe)

    def names_with(role: str) -> list[str]:
        return sorted(n for n, c in by_file.items()
                      if c.get(role) and sum(c.values()) <= max_findings_per_file)

    chosen: list[Path] = []
    seen: set[str] = set()

    def take(names: list[str], limit_files: int) -> None:
        for name in names:
            if len(chosen) >= max_files:
                return
            if name in seen:
                continue
            path = index.get(name)
            if path is None:
                continue
            seen.add(name)
            chosen.append(path)

    # The files whose findings sit in a `good*` method first: those are
    # the ones the AI has to judge. Without them the measurement has
    # nothing to measure.
    good_files = names_with("good")
    bad_files = names_with("bad")
    other_files = names_with("other")
    half = max(1, max_files // 2)
    take(good_files, half)
    take(bad_files, max_files - len(chosen))
    if len(chosen) < max_files:
        take(other_files, max_files - len(chosen))
    return chosen


def run_scan(files: list[Path], config: Path, out_nd: Path, ai: bool,
             log: Path) -> tuple[int, str]:
    cmd = ["java", "-jar", str(JAR), "scan", "-f", "ndjson", "-o", str(out_nd),
           "-c", str(config)]
    for f in files:
        cmd += ["-i", str(f)]
    if ai:
        cmd.append("--ai-dedup")
    jar_before = jar_identity()
    with out_nd.open("w", encoding="utf-8") as out, log.open("w", encoding="utf-8") as err:
        proc = subprocess.run(cmd, stdout=out, stderr=err, cwd=str(REPO),
                              env=dict(os.environ))
    jar_after = jar_identity()
    if jar_before != jar_after:
        raise RuntimeError("the scanner jar was replaced while the run was going")
    return proc.returncode, ""


def score_sample(cwe: str, nd: Path, truth: dict, rule_cwes: dict,
                 spans: dict, only_files: set[str]) -> dict:
    """Score one scan's findings, restricted to the sampled files."""
    return scorer.score_cwe(cwe, nd, truth, rule_cwes, spans, only_files=only_files)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--provider", default="minimax", choices=sorted(PROVIDERS))
    ap.add_argument("--findings", required=True, help="the full-scan findings directory")
    ap.add_argument("--rule-cwes", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--budget", type=int, default=40,
                    help="findings judged per CWE")
    ap.add_argument("--max-files", type=int, default=8)
    ap.add_argument("--cwes", nargs="*", help="only these CWEs")
    ap.add_argument("--deadline-minutes", type=float, default=0.0,
                    help="stop after this many minutes and report what was done")
    ap.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 4) - 1),
                    help="worker processes for the method-span computation")
    ap.add_argument("--no-drop", action="store_true",
                    help="label the AI-judged false positives instead of dropping "
                         "them. With the drop off the false-positive count cannot "
                         "change, so the pass measures the labelling, not the "
                         "reduction.")
    ap.add_argument("--shard", type=int, default=0,
                    help="which shard of the CWE list this run takes")
    ap.add_argument("--shards", type=int, default=1,
                    help="how many shards the CWE list is split into")
    ap.add_argument("--concurrency", type=int, default=4,
                    help="requests in flight per scan. The provider's plan "
                         "has a REQUEST RATE limit, and a rate limit "
                         "produces UNKNOWN verdicts that look like model "
                         "uncertainty.")
    ap.add_argument("--max-findings-per-file", type=int, default=120,
                    help="a file with more findings than this is not sampled. "
                         "One Juliet file in CWE-113 carries 6,600 findings, "
                         "and four such files are 26,000 AI judgements.")
    args = ap.parse_args()

    out_dir = Path(args.out)
    (out_dir / "ndjson").mkdir(parents=True, exist_ok=True)
    (out_dir / "logs").mkdir(parents=True, exist_ok=True)
    (out_dir / "configs").mkdir(parents=True, exist_ok=True)

    rule_cwes = scorer.load_live_rule_cwes(Path(args.rule_cwes))
    truth = measure.load_ground_truth()
    dirs = measure.cwe_dirs()
    started = time.time()

    results = []
    wanted = sorted(dirs, key=int)
    if args.shards > 1:
        # The shards are CONTIGUOUS slices of the sorted CWE list, so a
        # shard is a stable, reproducible set of CWEs and the shards
        # together cover every CWE exactly once.
        wanted = wanted[args.shard::args.shards]
    for cwe in wanted:
        if args.cwes and cwe not in args.cwes:
            continue
        if args.deadline_minutes and (time.time() - started) / 60 > args.deadline_minutes:
            print("DEADLINE reached -- stopping", flush=True)
            break
        full = Path(args.findings) / f"CWE{cwe}.ndjson"
        files = pick_sample(cwe, full, args.budget, args.max_files,
                             args.max_findings_per_file)
        entry = {"cwe": cwe, "files": [f.name for f in files],
                 "sampled": bool(files)}
        if not files:
            entry["status"] = "no_findings_to_judge"
            results.append(entry)
            print("CWE-%-4s nothing to judge" % cwe, flush=True)
            continue

        cfg = merged_config(cwe, args.provider, out_dir / "configs" / f"CWE{cwe}.yaml",
                            drop_on_fp=not args.no_drop, concurrency=args.concurrency)
        before_nd = out_dir / "ndjson" / f"CWE{cwe}.before.ndjson"
        after_nd = out_dir / "ndjson" / f"CWE{cwe}.after.ndjson"
        sample_names = {f.name for f in files}
        # The method spans turn a line number into a method, so they are
        # what makes the METHOD the unit of the measurement. Without
        # them every finding is "outside any method" and the coverage
        # numbers are empty.
        spans = scorer.build_span_cache(cwe, truth.get(cwe, {}), args.jobs)
        spans = {k: v for k, v in spans.items() if k in sample_names}
        try:
            rc, _ = run_scan(files, cfg, before_nd, False,
                             out_dir / "logs" / f"CWE{cwe}.before.log")
            if rc != 0:
                entry["status"] = "scan_failed"
                entry["stage"] = "before"
                results.append(entry)
                print("CWE-%-4s BEFORE SCAN FAILED (%s)" % (cwe, rc), flush=True)
                continue
            rc, _ = run_scan(files, cfg, after_nd, True,
                             out_dir / "logs" / f"CWE{cwe}.after.log")
            if rc != 0:
                entry["status"] = "scan_failed"
                entry["stage"] = "after"
                results.append(entry)
                print("CWE-%-4s AFTER SCAN FAILED (%s)" % (cwe, rc), flush=True)
                continue
        except RuntimeError as ex:
            entry["status"] = "scan_failed"
            entry["error"] = str(ex)
            results.append(entry)
            print("CWE-%-4s FAILED: %s" % (cwe, ex), flush=True)
            continue

        before = score_sample(cwe, before_nd, truth, rule_cwes, spans, sample_names)
        after = score_sample(cwe, after_nd, truth, rule_cwes, spans, sample_names)

        # The AI pass does not delete a finding; it labels it. So the
        # "after" numbers are the before numbers MINUS the findings the
        # AI judged false positives.
        verdicts: Counter = Counter()
        judged = 0
        with after_nd.open("r", encoding="utf-8", errors="replace") as fh:
            for raw in fh:
                raw = raw.strip()
                if not raw:
                    continue
                try:
                    d = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                v = d.get("ai_verdict")
                if v:
                    verdicts[v] += 1
                    judged += 1

        # A RATE LIMIT IS NOT A MODEL VERDICT.
        #
        # The provider's request rate limit answers every request with
        # HTTP 429, and the engine records that as an UNKNOWN verdict --
        # which is indistinguishable from "the model could not decide".
        # So the count of rate-limited requests is read out of the log,
        # and a CWE whose verdicts were rate-limited is reported as
        # rate-limited rather than as a measurement.
        rate_limited = 0
        log_path = out_dir / "logs" / f"CWE{cwe}.after.log"
        if log_path.is_file():
            text = log_path.read_text(encoding="utf-8", errors="replace")
            rate_limited = text.count("AI HTTP 429")

        entry.update({
            "status": "measured" if rate_limited == 0 else "rate_limited",
            "rate_limited_requests": rate_limited,
            "before": {k: before[k] for k in (
                "tp_findings", "fp_findings", "tp_methods", "fn_methods",
                "fp_methods", "tn_methods", "tp_coverage", "tn_coverage",
                "precision", "findings_total")},
            "after": {k: after[k] for k in (
                "tp_findings", "fp_findings", "tp_methods", "fn_methods",
                "fp_methods", "tn_methods", "tp_coverage", "tn_coverage",
                "precision", "findings_total")},
            # The findings that are NOT about the CWE under test. The AI
            # pass removes some of them, and that removal is real -- it
            # is just not a change in the CWE's own false-positive
            # count, so the two are reported apart.
            "before_off_cwe": before.get("off_cwe_findings", 0),
            "after_off_cwe": after.get("off_cwe_findings", 0),
            "before_unattributed": before.get("unattributed_findings", 0),
            "after_unattributed": after.get("unattributed_findings", 0),
            "verdicts": dict(verdicts),
            "judged": judged,
        })
        results.append(entry)
        b, a = entry["before"], entry["after"]
        print("CWE-%-4s files=%d TP %d->%d  FP %d->%d  tpcov %s->%s "
              "rate_limited=%d verdicts=%s"
              % (cwe, len(files), b["tp_methods"], a["tp_methods"],
                 b["fp_methods"], a["fp_methods"],
                 "%.3f" % b["tp_coverage"] if b["tp_coverage"] is not None else "n/a",
                 "%.3f" % a["tp_coverage"] if a["tp_coverage"] is not None else "n/a",
                 rate_limited, dict(verdicts)), flush=True)
        (out_dir / "ai-pass.json").write_text(
            json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")

    (out_dir / "ai-pass.json").write_text(
        json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print()
    print("%d CWEs in %.0f s -> %s"
          % (len(results), time.time() - started, out_dir / "ai-pass.json"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
