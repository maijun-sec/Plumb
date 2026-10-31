#!/usr/bin/env python3
"""Score the Juliet findings against the METHOD-level ground truth.

This is the scoring half of the measurement. The scan half lives in
`measure.py --scan-only`; the two are separate so a statistics change
never costs a re-scan, and so the findings are never held in memory.

THE UNIT IS THE METHOD.

A Juliet testcase file holds BOTH the vulnerable method (`bad*`) and the
fixed one (`good*`). A file-level measurement calls every finding a true
positive and reports a recall above 1. So:

  positive method = a `bad*` method    -> a finding in it is a TRUE POSITIVE
  negative method = a `good*` method   -> a finding in it is a FALSE POSITIVE
  no finding in a `good*` method       -> a TRUE NEGATIVE
  no finding in a `bad*` method        -> a FALSE NEGATIVE

FOUR BUCKETS, NOT TWO.

A finding is counted against the CWE under test only when the RULE is
about that CWE. A `dfa-taint-format-string` finding in a CWE-134 corpus
is scored; a `dfa-security-unchecked-return` finding in the same corpus
is a finding about CWE-252, and counting it as a false positive would
measure the WHITELIST's breadth rather than the engine's precision. So
the buckets are:

  tp             a finding in a `bad*` method, from a rule about this CWE
  fp             a finding in a `good*` method, from a rule about this CWE
  off_cwe        a finding from a rule about ANOTHER CWE
  unattributed   a finding in a tracked file but not inside any method
  driver         a finding on Juliet's own Main.java / ServletMain.java

A CWE with a rule that fires nothing is a VALID ZERO. A CWE with no rule
at all is a COVERAGE GAP. A CWE whose scan did not finish is NOT
MEASURED. The three are different and the report keeps them apart.

    python score.py --findings <dir> --status <dir>... --out <dir>
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import Counter, defaultdict
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent.parent
sys.path.insert(0, str(HERE))
import measure  # noqa: E402

DRIVER_FILES = measure.DRIVER_FILES


# ---------------------------------------------------------------------------
# The live rule -> CWE map
# ---------------------------------------------------------------------------
def load_live_rule_cwes(path: Path) -> dict[str, list[str]]:
    """rule id -> [CWE numbers], from the engine's own --list-rule-cwes.

    The map is read from the LIVE registries, so it is the engine's
    answer and not a table maintained beside the engine.
    """
    out: dict[str, list[str]] = {}
    if not path.is_file():
        return out
    raw = path.read_bytes()
    text = None
    for enc in ("utf-8-sig", "utf-16", "utf-8"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    if text is None:
        return out
    for line in text.splitlines():
        parts = line.rstrip("\n").split("\t")
        if not parts or not parts[0]:
            continue
        out[parts[0].strip()] = [measure.norm_cwe(c) for c in parts[1:] if c]
    return out


def finding_cwes(finding: dict, rule_cwes: dict[str, list[str]]) -> list[str]:
    """The CWE labels the finding itself carries."""
    out: list[str] = []
    for key in ("cwe", "cwe_id", "cweIds", "cwes"):
        v = finding.get(key)
        if isinstance(v, list):
            out.extend(measure.norm_cwe(str(x)) for x in v)
        elif v:
            out.append(measure.norm_cwe(str(v)))
    rid = finding.get("rule_id") or ""
    out.extend(rule_cwes.get(rid, []))
    seen, uniq = set(), []
    for c in out:
        if c and c not in seen:
            seen.add(c)
            uniq.append(c)
    return uniq


def rule_kind(rule_id: str) -> str:
    """'ast' or 'dfa' -- which engine produced the rule.

    The rule id carries its engine in its first token, and the two
    engines have completely different reachability semantics: an AST
    rule sees one file, a DFA rule can follow a value across files.
    A coverage number that does not say which engine produced the rule
    cannot be read.
    """
    if not rule_id:
        return "other"
    head = rule_id.split("-", 1)[0]
    if head in ("ast", "dfa"):
        return head
    return "other"


def crosses_files(finding: dict, own_file: str) -> bool:
    """Whether the finding's own trace leaves the file the finding is in.

    This is the measurable form of "the global call graph reached across
    translation units": the trace names a step in another file.
    """
    steps = finding.get("dataflow_steps")
    if not isinstance(steps, list) or not steps:
        return False
    for step in steps:
        if not isinstance(step, str):
            continue
        parts = step.split("|")
        if len(parts) < 2:
            continue
        loc = parts[1].strip()
        # "D:\path\File.java:53:-1" -> the path. The trailing ":line:column"
        # is stripped by shape, not by counting colons: a Windows path
        # starts with "D:", so splitting on the first colon would cut the
        # drive letter off.
        loc = re.sub(r":-?\d+(?::-?\d+)?$", "", loc)
        if loc and not own_file.lower().endswith(loc.lower()) and \
                Path(loc).name != own_file:
            return True
    return False


# ---------------------------------------------------------------------------
# Method spans
# ---------------------------------------------------------------------------
def _spans_for(job: tuple[str, str]) -> tuple[str, dict]:
    """(filename, {method -> (start, end)}) for one Juliet file.

    Runs in a worker process: the corpus is 46,793 files and reading
    them all on one thread is the slowest part of the scoring.
    """
    cwe, name = job
    path = juliet_file(cwe, name)
    if path is None:
        return name, {}
    return name, measure.all_method_spans(path)


def build_span_cache(cwe: str, files: dict, jobs: int) -> dict:
    """Every tracked file's method spans, computed in parallel.

    The span table is what turns a line number into a method, so it is
    what makes the METHOD the unit of the measurement. It is computed
    once per file and shared by every CWE that names the file.
    """
    todo = []
    for name in files:
        path = juliet_file(cwe, name)
        todo.append((cwe, name))
    out: dict[str, dict] = {}
    if jobs <= 1 or len(todo) < 200:
        for job in todo:
            name, spans = _spans_for(job)
            out[name] = spans
        return out
    with ProcessPoolExecutor(max_workers=jobs) as pool:
        for name, spans in pool.map(_spans_for, todo, chunksize=64):
            out[name] = spans
    return out


def juliet_file(cwe: str, name: str) -> Path | None:
    """The Juliet file with this name inside this CWE's directory."""
    return _cwe_index(cwe).get(name)


_CWE_INDEX: dict[str, dict[str, Path]] = {}


def _cwe_index(cwe: str) -> dict[str, Path]:
    """filename -> path, for one CWE's directory tree.

    The index is built once per CWE. Looking each file up by globbing the
    variant directories is O(files x variants), which on a 7,000-file CWE
    is tens of thousands of directory listings.
    """
    if cwe in _CWE_INDEX:
        return _CWE_INDEX[cwe]
    index: dict[str, Path] = {}
    root = measure.JULIET_ROOT
    for sub in sorted(root.glob(f"CWE{cwe}_*")):
        if not sub.is_dir():
            continue
        for p in sub.rglob("*.java"):
            index.setdefault(p.name, p)
    _CWE_INDEX[cwe] = index
    return index


def roles_of(spans: dict) -> tuple[list[str], list[str]]:
    """(positive method names, negative method names)."""
    pos, neg = [], []
    for method in spans:
        if method.startswith("bad"):
            pos.append(method)
        elif method.startswith("good"):
            neg.append(method)
    return pos, neg


# ---------------------------------------------------------------------------
# Scoring one CWE
# ---------------------------------------------------------------------------
def score_cwe(cwe: str, findings_path: Path, truth: dict,
              rule_cwes: dict[str, list[str]],
              span_cache: dict[str, dict],
              only_files: set[str] | None = None) -> dict:
    """Score one CWE's findings against its method-level ground truth.

    `only_files` restricts the ground truth to a subset of the CWE's
    files. It is what a sampled measurement needs: the denominator has
    to be the methods of the files that were actually scanned, or the
    coverage number describes a corpus that was never looked at.
    """
    files = truth.get(cwe, {})
    if only_files is not None:
        files = {k: v for k, v in files.items() if k in only_files}

    # The method identity is (file, method), NOT the method name.
    #
    # Every Juliet file of a CWE declares a method called `bad`, so keying
    # by the name alone collapses 1,098 distinct methods into three. The
    # method's identity is its declaration site.
    positives: dict[tuple[str, str], str] = {}   # (file, method) -> file
    negatives: dict[tuple[str, str], str] = {}
    for name in files:
        pos, neg = roles_of(span_cache.get(name, {}))
        for m in pos:
            positives.setdefault((name, m), name)
        for m in neg:
            negatives.setdefault((name, m), name)

    tp_methods: set[tuple[str, str]] = set()
    fp_methods: set[tuple[str, str]] = set()
    off_cwe_methods: set[tuple[str, str]] = set()

    counts = Counter()
    fp_by_rule: Counter = Counter()
    fp_by_reason: Counter = Counter()
    off_cwe_by_rule: Counter = Counter()
    off_cwe_cwes: Counter = Counter()
    tp_by_rule: Counter = Counter()
    tp_by_kind: Counter = Counter()
    fp_by_kind: Counter = Counter()
    wrong_cwe_in_bad: Counter = Counter()
    unattributed_samples: list[dict] = []
    fp_samples: list[dict] = []

    def attribute(line: int, spans: dict) -> tuple[str, str]:
        """(role, method) for a line, or ('none', '') when outside a method."""
        for method, (start, end) in spans.items():
            if start <= line <= end:
                if method.startswith("bad"):
                    return "bad", method
                if method.startswith("good"):
                    return "good", method
                return "other", method
        return "none", ""

    def spans_of(name: str) -> dict:
        return span_cache.get(name, {})

    with findings_path.open("r", encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            raw = raw.strip()
            if not raw:
                continue
            try:
                finding = json.loads(raw)
            except json.JSONDecodeError:
                counts["unparsable"] += 1
                continue
            counts["total"] += 1
            name = Path(finding.get("file") or "").name
            try:
                line = int(finding.get("line") or 0)
            except (TypeError, ValueError):
                line = 0
            rule = finding.get("rule_id") or "?"
            cwes = finding_cwes(finding, rule_cwes)

            if name in DRIVER_FILES:
                counts["driver"] += 1
                continue
            if name not in files:
                counts["unknown_file"] += 1
                continue

            # CWE-EXACT: only a rule that is ABOUT this CWE is scored here.
            if cwes and cwe not in cwes:
                counts["off_cwe"] += 1
                off_cwe_by_rule[rule] += 1
                for c in cwes:
                    off_cwe_cwes[c] += 1
                role, method = attribute(line, spans_of(name))
                if role == "bad":
                    wrong_cwe_in_bad[rule] += 1
                continue
            if not cwes:
                # The rule carries no CWE label at all, so it cannot be
                # credited to a CWE. It is neither a hit nor a miss here.
                counts["unlabelled_rule"] += 1
                off_cwe_by_rule[rule] += 1
                continue

            role, method = attribute(line, spans_of(name))
            if role == "bad":
                counts["tp"] += 1
                tp_methods.add((name, method))
                tp_by_rule[rule] += 1
                kind = rule_kind(rule)
                tp_by_kind[kind] += 1
                if crosses_files(finding, name):
                    counts["tp_cross_file"] += 1
            elif role == "good":
                counts["fp"] += 1
                fp_methods.add((name, method))
                fp_by_rule[rule] += 1
                fp_by_reason["in a fixed (good*) method"] += 1
                kind = rule_kind(rule)
                fp_by_kind[kind] += 1
                if crosses_files(finding, name):
                    counts["fp_cross_file"] += 1
                if len(fp_samples) < 40:
                    fp_samples.append({
                        "file": name, "line": line, "rule_id": rule,
                        "method": method,
                        "message": (finding.get("message") or "")[:220],
                        "sink_function": finding.get("sink_function") or "",
                    })
            elif role == "other":
                counts["other_method"] += 1
                fp_by_reason["in a method that is neither bad* nor good*"] += 1
            else:
                counts["unattributed"] += 1
                if len(unattributed_samples) < 20:
                    unattributed_samples.append({
                        "file": name, "line": line, "rule_id": rule,
                        "message": (finding.get("message") or "")[:220],
                    })

    tp = len(tp_methods)
    fn = len(positives) - tp
    fp = len(fp_methods)
    tn = len(negatives) - fp
    return {
        "cwe": cwe,
        "findings_total": counts["total"],
        "tp_findings": counts["tp"],
        "fp_findings": counts["fp"],
        "tp_cross_file_findings": counts["tp_cross_file"],
        "fp_cross_file_findings": counts["fp_cross_file"],
        "off_cwe_findings": counts["off_cwe"] + counts["unlabelled_rule"],
        "unlabelled_findings": counts["unlabelled_rule"],
        "unattributed_findings": counts["unattributed"],
        "driver_findings": counts["driver"],
        "unknown_file_findings": counts["unknown_file"],
        "other_method_findings": counts["other_method"],
        "methods_positive": len(positives),
        "methods_negative": len(negatives),
        "tp_methods": tp,
        "fn_methods": fn,
        "fp_methods": fp,
        "tn_methods": tn,
        "tp_coverage": tp / len(positives) if positives else None,
        "tn_coverage": tn / len(negatives) if negatives else None,
        "precision": counts["tp"] / (counts["tp"] + counts["fp"]) if (counts["tp"] + counts["fp"]) else None,
        "tp_by_rule": dict(tp_by_rule.most_common(15)),
        "fp_by_rule": dict(fp_by_rule.most_common(15)),
        "tp_by_engine": dict(tp_by_kind.most_common()),
        "fp_by_engine": dict(fp_by_kind.most_common()),
        "fp_by_reason": dict(fp_by_reason.most_common(10)),
        "off_cwe_by_rule": dict(off_cwe_by_rule.most_common(15)),
        "off_cwe_cwes": dict(off_cwe_cwes.most_common(15)),
        "off_cwe_in_bad_method": dict(wrong_cwe_in_bad.most_common(10)),
        "fp_samples": fp_samples,
        "unattributed_samples": unattributed_samples,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--findings", required=True, help="the per-CWE NDJSON directory")
    ap.add_argument("--status", action="append", required=True,
                    help="a per-CWE scan status directory (repeatable)")
    ap.add_argument("--rule-cwes", required=True,
                    help="the engine's --list-rule-cwes output")
    ap.add_argument("--out", required=True, help="where the score lands")
    ap.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 4) - 1))
    args = ap.parse_args()

    findings_dir = Path(args.findings)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    rule_cwes = load_live_rule_cwes(Path(args.rule_cwes))
    truth = measure.load_ground_truth()
    dirs = measure.cwe_dirs()

    # the scan status: a CWE is MEASURED only when its scan finished.
    status: dict[str, dict] = {}
    for d in args.status:
        for p in sorted(Path(d).glob("CWE*.json")):
            try:
                s = json.loads(p.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            status[str(s.get("cwe"))] = s

    # The live rules that exist for each CWE, so "no rule at all" is a
    # statement about the ENGINE and not about this run.
    rules_for_cwe: dict[str, set[str]] = defaultdict(set)
    for rid, cwes in rule_cwes.items():
        for c in cwes:
            rules_for_cwe[c].add(rid)

    # every CWE the corpus has, and its method spans
    span_cache: dict[str, dict] = {}
    results = []
    for cwe in sorted(dirs, key=int):
        st = status.get(cwe)
        entry: dict = {
            "cwe": cwe,
            "dir": dirs[cwe],
            "status": "not_measured",
            "error": (st or {}).get("error"),
            "scan_seconds": (st or {}).get("seconds"),
            "scan_findings": (st or {}).get("findings"),
            "whitelist_mode": measure.whitelist_mode(cwe),
            "whitelist_rules": measure.read_whitelist(cwe),
            "engine_rules_for_cwe": sorted(rules_for_cwe.get(cwe, [])),
            "engine_has_rule_for_cwe": bool(rules_for_cwe.get(cwe)),
        }
        nd = findings_dir / f"CWE{cwe}.ndjson"
        if st is None:
            entry["status"] = "not_measured"
            entry["error"] = "the scan produced no status file"
            results.append(entry)
            continue
        if not st.get("ok"):
            entry["status"] = "scan_failed"
            results.append(entry)
            continue
        if not nd.is_file():
            entry["status"] = "scan_failed"
            entry["error"] = "the scan reported success but wrote no findings file"
            results.append(entry)
            continue
        entry["status"] = "measured"
        # The method spans are computed in parallel: the corpus is 46,793
        # files and reading them all on one thread is the slowest part of
        # the scoring.
        span_cache.update(build_span_cache(cwe, truth.get(cwe, {}), args.jobs))
        entry.update(score_cwe(cwe, nd, truth, rule_cwes, span_cache))
        results.append(entry)

        r = entry
        print("CWE-%-4s %-9s rules=%-2d methods bad/good=%4d/%-4d "
              "TP=%-4d FN=%-4d FP=%-4d TN=%-4d tpcov=%s tncov=%s"
              % (cwe, r["whitelist_mode"], len(r["engine_rules_for_cwe"]),
                 r["methods_positive"], r["methods_negative"],
                 r["tp_methods"], r["fn_methods"], r["fp_methods"], r["tn_methods"],
                 ("%.3f" % r["tp_coverage"]) if r["tp_coverage"] is not None else "n/a",
                 ("%.3f" % r["tn_coverage"]) if r["tn_coverage"] is not None else "n/a"),
              flush=True)

    (out_dir / "score.json").write_text(
        json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print()
    print("scored %d CWEs -> %s" % (len(results), out_dir / "score.json"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
