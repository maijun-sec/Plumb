"""
evaluate_plumb_findings.py — TP / FP / FN / TN evaluator for Plumb NDJSON on Juliet Java.

Inputs:
  --plumb-ndjson  Plumb NDJSON output file (one finding per line)
  --testcases-csv D:/work/tmp/Juliet_Java/testcases_detail.csv
  --juliet-root   D:/work/tmp/Juliet_Java/src/testcases
  --out-dir       output directory for summary CSVs / reports

Each Juliet test case is identified by:
  (cwe_id, functional_variant, flow_variant) — uniquely identifying a *_XX.java file (or file set).
Each test case has POSITIVE=True/False (bad source present) and NEGATIVE=True/False (good methods).

Ground truth per (cwe_id, flow_variant, file):
  - In POSITIVE test cases, the `bad()` method body should be flagged (positive is the bug).
    * POSITIVE=NEGATIVE=True (most common): the file has both bad() AND good1()/goodG2B() methods.
    * Only the bad() body is supposed to be flagged.
    * Findings in good1()/goodG2B()/good2()/... are FP (false positives).
  - In NEGATIVE-only test cases (rare), no findings should appear.

A finding is TP iff:
  rule_id fires in bad() body of a POSITIVE test case file.
A finding is FP iff:
  rule_id fires in good*() body of a POSITIVE test case file (or in any method of a NEGATIVE-only file).
A finding is FN iff:
  no rule_id fires in bad() body of a POSITIVE test case file but bad() exists.

We approximate "method body" by parsing the .java file with a simple regex-based locator:
  Look for the method declaration with the function signature given in finding's sink_function.
  The finding line is TP if it falls inside that method's body.

To make this robust, we read each .java file once, locate the method header line (e.g.
`public void bad()`), and capture the span from the { to the matching }.

For class-based / multi-file CWE, we look up file set in testcases_detail.csv.

Drives are excluded: Main.java, ServletMain.java.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable


# ---------- Plumb NDJSON parsing ----------

@dataclass
class Finding:
    rule_id: str
    rule_name: str
    severity: str
    cwe: list[str]
    file: str
    line: int
    column: int
    message: str
    sink_function: str
    dataflow: str = ""

    @classmethod
    def from_nd(cls, row: dict) -> "Finding":
        cwe = row.get("cwe") or []
        if isinstance(cwe, str):
            cwe = [cwe]
        line = int(row.get("line") or 0)
        col = int(row.get("column") or -1)
        return cls(
            rule_id=row.get("rule_id", ""),
            rule_name=row.get("rule_name", ""),
            severity=row.get("severity", ""),
            cwe=cwe,
            file=row.get("file", "unknown"),
            line=line,
            column=col,
            message=row.get("message", ""),
            sink_function=row.get("sink_function", ""),
            dataflow=row.get("dataflow", ""),
        )


def load_plumb(ndjson_path: Path) -> list[Finding]:
    out: list[Finding] = []
    if not ndjson_path.exists():
        return out
    with ndjson_path.open("r", encoding="utf-8", errors="replace") as fh:
        for ln, raw in enumerate(fh, 1):
            raw = raw.strip()
            if not raw:
                continue
            try:
                row = json.loads(raw)
            except json.JSONDecodeError as ex:
                # Skip malformed line
                continue
            out.append(Finding.from_nd(row))
    return out


# ---------- Juliet testcase index ----------

@dataclass
class TestCase:
    cwe_id: str
    cwe_name: str
    functional_variant: str
    flow_variant: str
    files: list[str]   # relative to juliet_root
    positive: bool
    negative: bool
    methods: list[str] = field(default_factory=list)


def load_testcases(csv_path: Path) -> tuple[dict[str, list[TestCase]], dict[str, dict[str, list[TestCase]]]]:
    """Index testcases by file basename, and by (cwe_id, method_name) for unknown-file lookup."""
    by_file: dict[str, list[TestCase]] = defaultdict(list)
    by_cwe_method: dict[str, dict[str, list[TestCase]]] = defaultdict(lambda: defaultdict(list))
    with csv_path.open("r", encoding="utf-8", errors="replace") as fh:
        rd = csv.DictReader(fh)
        for row in rd:
            files = [f.strip() for f in (row.get("FILES") or "").split(",") if f.strip()]
            methods = [m.strip() for m in (row.get("METHODS") or "").split(",") if m.strip()]
            tc = TestCase(
                cwe_id=row["CWE_ID"],
                cwe_name=row["CWE_NAME"],
                functional_variant=row["FUNCTIONAL_VARIANT"],
                flow_variant=row["FLOW_VARIANT"],
                files=files,
                positive=(row.get("POSITIVE", "False").strip().lower() == "true"),
                negative=(row.get("NEGATIVE", "False").strip().lower() == "true"),
                methods=methods,
            )
            for f in files:
                by_file[Path(f).name].append(tc)
            for m in methods:
                by_cwe_method[tc.cwe_id][m].append(tc)
    return by_file, by_cwe_method


# ---------- Method body locator ----------

# Driver files: skip these from TP/FP accounting
DRIVER_FILES = {"Main.java", "ServletMain.java"}

# Pattern: `private <type> bad()` or `public <type> bad(` or `private static <type> bad()`
METHOD_DECL_RE = re.compile(
    r"(?P<sig>(?:public|private|protected)\s+(?:static\s+|final\s+|abstract\s+|\s)*"
    r"(?:[\w<>?\[\],\s.]+?)\s+(?P<name>\w+)\s*\([^)]*\)\s*(?:throws\s+[\w.,\s]+)?\s*\{)"
)


@dataclass
class MethodSpan:
    name: str
    signature: str
    start_line: int
    end_line: int  # inclusive


def parse_method_spans(java_path: Path) -> list[MethodSpan]:
    if not java_path.exists():
        return []
    text = java_path.read_text(encoding="utf-8", errors="replace")
    lines = text.split("\n")
    spans: list[MethodSpan] = []
    for idx, line in enumerate(lines):
        m = METHOD_DECL_RE.search(line)
        if not m:
            continue
        # Find the matching '}' starting from this line's end column
        depth = 0
        # Walk all subsequent lines; simpler: walk the rest of `lines` from idx
        # using a brace balance that opens at m.end()-1 (the { in the signature)
        rest_start_col = m.end() - 1  # column of '{' in the matched line
        # Initialize depth by counting braces on this line up to and including the '{'
        # Then continue.
        open_idx = m.end() - 1
        depth = text[:open_idx + 1].count("{") - text[:open_idx + 1].count("}")
        # Continue scanning chars after the '{' in the matched line
        tail = line[rest_start_col + 1:]
        local_depth = depth
        end_line = idx
        first_line = True
        for j, ch in enumerate(tail):
                if ch == "{":
                    local_depth += 1
                elif ch == "}":
                    local_depth -= 1
                    if local_depth == 0:
                        end_line = idx
                        # find exact column
                        spans.append(MethodSpan(
                            name=m.group("name"),
                            signature=m.group("sig"),
                            start_line=idx + 1,
                            end_line=end_line + 1,
                        ))
                        break
            # If not yet closed, fall through to multi-line tracking below.
            # Continue to scan remaining lines if depth still > 0.
        # Better strategy: brute force with brace counter over entire file
        # from this method's '{' position.
        # Compute byte offset of the opening '{' in the file.
        # We use line offsets.
    return _parse_method_spans_brute(text, lines)


def _parse_method_spans_brute(text: str, lines: list[str]) -> list[MethodSpan]:
    """More reliable: walk each line, find method headers, then track braces from '{'."""
    spans: list[MethodSpan] = []
    # Build cumulative offsets so we can locate a brace position
    line_starts = [0]
    for ln in lines:
        line_starts.append(line_starts[-1] + len(ln) + 1)  # +1 for '\n'

    def line_of(offset: int) -> int:
        # binary search
        lo, hi = 0, len(line_starts) - 1
        while lo < hi - 1:
            mid = (lo + hi) // 2
            if line_starts[mid] <= offset:
                lo = mid
            else:
                hi = mid
        return lo + 1  # 1-indexed

    # Scan for method headers
    header_re = re.compile(
        r"(?:public|private|protected)\s+(?:static\s+|final\s+|synchronized\s+|abstract\s+|\s)*"
        r"[\w<>?\[\],\s.]+?\s+(?P<name>\w+)\s*\([^)]*\)\s*(?:throws\s+[\w.,\s]+)?\s*\{",
        re.MULTILINE,
    )
    for m in header_re.finditer(text):
        name = m.group("name")
        # Locate the final '{' of this match
        brace_offset = m.end() - 1
        # Walk forward, counting braces, until balance returns to 0
        depth = 1
        i = brace_offset + 1
        n = len(text)
        while i < n and depth > 0:
            ch = text[i]
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
            i += 1
        close_offset = i - 1
        start_line = line_of(m.start())  # 1-indexed
        end_line = line_of(close_offset)
        spans.append(MethodSpan(name=name, signature=m.group(0), start_line=start_line, end_line=end_line))
    return spans


def find_method_span(spans: list[MethodSpan], method_name: str, target_line: int) -> MethodSpan | None:
    """Find the method span whose range contains target_line.

    If method_name is given and matches, prefer that one.
    Otherwise, fall back to any span whose range contains target_line
    (innermost match wins).
    """
    if method_name:
        candidates = [s for s in spans if s.name == method_name]
        if candidates:
            for s in candidates:
                if s.start_line <= target_line <= s.end_line:
                    return s
            return candidates[0]
    # Fallback: any span containing the line, preferring innermost
    containing = [s for s in spans if s.start_line <= target_line <= s.end_line]
    if containing:
        containing.sort(key=lambda s: (s.end_line - s.start_line, s.start_line))
        return containing[0]
    return None


def classify_method_role(method_name: str, testcase_methods: list[str]) -> str:
    """
    Return one of:
      - 'bad' if method is the bad() (or bad1/bad2 if list has bad1)
      - 'good_g2b' (good source -> bad sink, e.g., goodG2B)
      - 'good_b2g' (bad source -> good sink, e.g., goodB2G)
      - 'good1'/'good2' etc. (other good variants)
      - 'driver' (Main / ServletMain should never appear here)
      - 'unknown'
    """
    if method_name == "bad" or method_name.startswith("bad"):
        return "bad"
    # goodG2B / goodB2G / good1 / good2 / good3 / good4 / good5
    if method_name.startswith("goodG2B") or method_name.startswith("goodB2G"):
        if method_name.startswith("goodG2B"):
            return "good_g2b"
        return "good_b2g"
    if method_name.startswith("good"):
        return "good_other"
    return "other"


# ---------- Evaluator ----------

@dataclass
class EvalResult:
    tp: int = 0
    fp_g2b: int = 0
    fp_b2g: int = 0
    fp_good_other: int = 0
    fp_negative_only: int = 0
    fp_driver: int = 0
    fp_other: int = 0
    bad_method_lines: set[int] = field(default_factory=set)  # lines in bad() body
    fired_bad_lines: set[int] = field(default_factory=set)
    fired_good_lines: set[int] = field(default_factory=set)
    n_findings: int = 0
    by_rule: dict[str, dict[str, int]] = field(default_factory=lambda: defaultdict(lambda: defaultdict(int)))
    fp_examples: list[dict] = field(default_factory=list)

    def add_finding(self, role: str, rule_id: str, line: int, example: dict | None = None):
        self.n_findings += 1
        self.by_rule[rule_id][f"total"] += 1
        if role == "bad":
            self.tp += 1
            self.fired_bad_lines.add(line)
            self.by_rule[rule_id]["tp"] += 1
        elif role == "good_g2b":
            self.fp_g2b += 1
            self.fired_good_lines.add(line)
            self.by_rule[rule_id]["fp_g2b"] += 1
            if example:
                self.fp_examples.append(example)
        elif role == "good_b2g":
            self.fp_b2g += 1
            self.fired_good_lines.add(line)
            self.by_rule[rule_id]["fp_b2g"] += 1
            if example:
                self.fp_examples.append(example)
        elif role == "good_other":
            self.fp_good_other += 1
            self.fired_good_lines.add(line)
            self.by_rule[rule_id]["fp_good_other"] += 1
            if example:
                self.fp_examples.append(example)
        elif role == "driver":
            self.fp_driver += 1
            self.by_rule[rule_id]["fp_driver"] += 1
        elif role == "neg_only":
            self.fp_negative_only += 1
            self.by_rule[rule_id]["fp_negative_only"] += 1
        else:
            self.fp_other += 1
            self.by_rule[rule_id]["fp_other"] += 1

    @property
    def fp(self) -> int:
        return self.fp_g2b + self.fp_b2g + self.fp_good_other + self.fp_negative_only + self.fp_driver + self.fp_other

    @property
    def precision(self) -> float:
        if self.n_findings == 0:
            return 0.0
        return self.tp / self.n_findings


def evaluate(plumb_ndjson: Path, testcases_csv: Path, juliet_root: Path, scope_cwe_id: str | None = None) -> tuple[dict, dict]:
    """
    Returns:
      per_rule: dict rule_id -> {tp, fp, fp_*, n_findings, cwes, examples}
      per_cwe:  dict cwe_id -> {tp, fp, n_findings, rules}

    scope_cwe_id: optional "CWE<N>" (e.g. "CWE248") — if set, restrict lookup to this CWE only.
    """
    testcases_by_file, testcases_by_cwe_method = load_testcases(testcases_csv)
    findings = load_plumb(plumb_ndjson)

    # Restrict by CWE if requested
    if scope_cwe_id:
        scope_prefix = scope_cwe_id.upper()
        testcases_by_file = {fn: [tc for tc in tcs if tc.cwe_id.upper() == scope_prefix]
                             for fn, tcs in testcases_by_file.items()}
        testcases_by_cwe_method = {cwe: {m: [tc for tc in tcs if tc.cwe_id.upper() == scope_prefix]
                                        for m, tcs in methods.items()}
                                   for cwe, methods in testcases_by_cwe_method.items()}
        # Filter empty
        testcases_by_file = {fn: tcs for fn, tcs in testcases_by_file.items() if tcs}
        testcases_by_cwe_method = {cwe: methods for cwe, methods in testcases_by_cwe_method.items() if methods}

    per_rule: dict[str, dict] = defaultdict(lambda: {
        "tp": 0,
        "fp_total": 0,
        "fp_g2b": 0,
        "fp_b2g": 0,
        "fp_good_other": 0,
        "fp_negative_only": 0,
        "fp_driver": 0,
        "fp_other": 0,
        "n_findings": 0,
        "cwes": set(),
        "examples": [],
    })
    per_cwe: dict[str, dict] = defaultdict(lambda: {
        "tp": 0,
        "fp_total": 0,
        "n_findings": 0,
        "rules": defaultdict(lambda: {"tp": 0, "fp": 0}),
    })

    # Cache: file -> method spans
    span_cache: dict[Path, list[MethodSpan]] = {}

    for f in findings:
        # Resolve file path
        fpath = Path(f.file)
        if not fpath.is_absolute() or f.file == "unknown":
            # Plumb may emit 'unknown' — we'll resolve via method-name lookup below
            fpath_resolved = None
        else:
            fpath_resolved = fpath if fpath.exists() else None

        target_method_name = f.sink_function.split(":")[0].split("(")[0].strip() if f.sink_function else ""

        if fpath_resolved is None:
            # Plumb's 'file' field is unreliable for DFA findings (often "unknown").
            # Best strategy: search testcases whose methods include target_method_name.
            # The finding's own `cwe` field is the rule's CWE category (e.g. CWE-404), NOT
            # the CWE of the file it actually came from. So we must scan all (or the scoped)
            # CWE directories for a file that declares the target method.
            candidates: list[Path] = []
            for cwe_id, methods in testcases_by_cwe_method.items():
                if target_method_name not in methods:
                    continue
                for tc in methods[target_method_name]:
                    for fname in tc.files:
                        # testcases_detail.csv's FILES field has either:
                        #   "src/testcases/CWE.../file.java" (3+ parts)
                        #   "CWE..._...__..._XX.java" (1 part — basename only)
                        # In the basename case, derive the CWE directory from the cwe_id.
                        # The directory is juliet_root / CWE<N>_<descriptive_name>/
                        full = None
                        parts = fname.split("/")
                        if len(parts) >= 3:
                            rel = "/".join(parts[2:])
                            full = juliet_root / rel
                        else:
                            # Basename only — search for matching dir
                            base = fname
                            # Find the CWE<N>_<name> dir that contains this file
                            stem = base.split("_")[0]  # e.g. "CWE248"
                            for d in juliet_root.glob(f"{stem}_*"):
                                if (d / base).exists():
                                    full = d / base
                                    break
                        if full and full.exists():
                            candidates.append(full)
            # Pick the candidate file that actually contains the method (and not a driver)
            for c in candidates:
                if c.name in DRIVER_FILES:
                    continue
                if c not in span_cache:
                    span_cache[c] = parse_method_spans(c)
                spans = span_cache[c]
                if any(s.name == target_method_name for s in spans):
                    fpath_resolved = c
                    break
            # Last resort: just pick first non-driver candidate
            if fpath_resolved is None:
                for c in candidates:
                    if c.name not in DRIVER_FILES:
                        fpath_resolved = c
                        break

        if fpath_resolved is None:
            # Cannot resolve: counts as fp_other (uncategorized)
            for c in f.cwe:
                cwe_id = c.replace("CWE-", "")
                per_cwe[cwe_id]["n_findings"] += 1
                per_cwe[cwe_id]["fp_total"] += 1
                per_cwe[cwe_id]["rules"][f.rule_id]["fp"] += 1
            entry = per_rule[f.rule_id]
            entry["n_findings"] += 1
            entry["fp_total"] += 1
            entry["fp_other"] += 1
            entry["cwes"].update(c.replace("CWE-", "") for c in f.cwe)
            continue

        # Determine if driver
        if fpath_resolved.name in DRIVER_FILES:
            for c in f.cwe:
                cwe_id = c.replace("CWE-", "")
                per_cwe[cwe_id]["n_findings"] += 1
                per_cwe[cwe_id]["fp_total"] += 1
                per_cwe[cwe_id]["rules"][f.rule_id]["fp"] += 1
            entry = per_rule[f.rule_id]
            entry["n_findings"] += 1
            entry["fp_total"] += 1
            entry["fp_driver"] += 1
            entry["cwes"].update(c.replace("CWE-", "") for c in f.cwe)
            continue

        # Determine method role
        if fpath_resolved not in span_cache:
            span_cache[fpath_resolved] = parse_method_spans(fpath_resolved)
        spans = span_cache[fpath_resolved]
        # Find the span containing f.line
        span = find_method_span(spans, target_method_name, f.line)
        if span is None:
            role = "other"
        else:
            # Look up test case to confirm
            rel_file = fpath_resolved.name
            tcs = testcases_by_file.get(rel_file, [])
            tc = tcs[0] if tcs else None
            role = classify_method_role(span.name, tc.methods if tc else [])

        # Map to test case role
        # For POSITIVE+bad: TP
        # For POSITIVE+good*: FP
        # For NEGATIVE-only files (no bad method): all findings FP
        # Otherwise: role=other -> fp_other

        # Determine the *real* CWE ID for this finding, derived from the resolved
        # file path (not from finding.cwe, which is the rule's category, not the testcase).
        real_cwe_id: str | None = None
        rel_file = fpath_resolved.name
        tcs_for_real = testcases_by_file.get(rel_file, [])
        if tcs_for_real:
            # Use the FIRST testcase whose POSITIVE bit matches the role
            real_cwe_id = tcs_for_real[0].cwe_id.replace("CWE", "")
        else:
            # Fallback: parse the CWE from the directory path
            for part in fpath_resolved.parts:
                if part.startswith("CWE") and "_" in part:
                    real_cwe_id = part.split("_")[0].replace("CWE", "")
                    break

        # For per-cwe aggregation, use real_cwe_id (the testcase CWE, not the rule CWE).
        # If we cannot derive one, fall back to the finding's reported CWEs.
        cwe_ids = [real_cwe_id] if real_cwe_id else [c.replace("CWE-", "").replace("CWE", "") for c in f.cwe]

        example = None
        if role != "bad":
            example = {
                "file": str(fpath_resolved),
                "line": f.line,
                "rule_id": f.rule_id,
                "method": target_method_name,
                "role": role,
                "message": f.message[:200],
            }

        for cwe_id in cwe_ids:
            per_cwe[cwe_id]["n_findings"] += 1
            if role == "bad":
                per_cwe[cwe_id]["tp"] += 1
                per_cwe[cwe_id]["rules"][f.rule_id]["tp"] += 1
            else:
                per_cwe[cwe_id]["fp_total"] += 1
                per_cwe[cwe_id]["rules"][f.rule_id]["fp"] += 1

        entry = per_rule[f.rule_id]
        entry["n_findings"] += 1
        # Track rule->CWE mapping by the *real* testcase CWE only
        if real_cwe_id:
            entry["cwes"].add(real_cwe_id)
        else:
            entry["cwes"].update(c.replace("CWE-", "").replace("CWE", "") for c in f.cwe)
        if role == "bad":
            entry["tp"] += 1
        elif role == "good_g2b":
            entry["fp_total"] += 1
            entry["fp_g2b"] += 1
            if len(entry["examples"]) < 10:
                entry["examples"].append(example)
        elif role == "good_b2g":
            entry["fp_total"] += 1
            entry["fp_b2g"] += 1
            if len(entry["examples"]) < 10:
                entry["examples"].append(example)
        elif role == "good_other":
            entry["fp_total"] += 1
            entry["fp_good_other"] += 1
            if len(entry["examples"]) < 10:
                entry["examples"].append(example)
        else:
            entry["fp_total"] += 1
            entry["fp_other"] += 1

    return per_rule, per_cwe


def write_summary(per_rule: dict, per_cwe: dict, out_path: Path, scope_name: str):
    out_path.parent.mkdir(parents=True, exist_ok=True)
    rows = []
    for rule_id, e in sorted(per_rule.items(), key=lambda kv: -kv[1]["n_findings"]):
        tp = e["tp"]
        fp = e["fp_total"]
        total = e["n_findings"]
        precision = tp / total if total else 0.0
        rows.append({
            "scope": scope_name,
            "rule_id": rule_id,
            "tp": tp,
            "fp_total": fp,
            "fp_g2b": e["fp_g2b"],
            "fp_b2g": e["fp_b2g"],
            "fp_good_other": e["fp_good_other"],
            "fp_other": e["fp_other"],
            "n_findings": total,
            "precision": f"{precision:.4f}",
            "n_cwes": len(e["cwes"]),
            "example_files": "; ".join(ex["file"] if ex else "" for ex in e["examples"][:3]),
        })
    with out_path.open("w", encoding="utf-8", newline="") as fh:
        wr = csv.DictWriter(fh, fieldnames=list(rows[0].keys()) if rows else [
            "scope", "rule_id", "tp", "fp_total", "fp_g2b", "fp_b2g",
            "fp_good_other", "fp_other", "n_findings", "precision", "n_cwes", "example_files"
        ])
        wr.writeheader()
        for r in rows:
            wr.writerow(r)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--plumb-ndjson", type=Path, required=True)
    ap.add_argument("--testcases-csv", type=Path, required=True)
    ap.add_argument("--juliet-root", type=Path, required=True)
    ap.add_argument("--out-dir", type=Path, required=True)
    ap.add_argument("--scope-name", type=str, required=True)
    ap.add_argument("--scope-cwe-id", type=str, default=None,
                    help="Optional: restrict to one Juliet CWE directory (e.g. CWE248)")
    args = ap.parse_args()

    per_rule, per_cwe = evaluate(args.plumb_ndjson, args.testcases_csv, args.juliet_root,
                                  scope_cwe_id=args.scope_cwe_id)
    args.out_dir.mkdir(parents=True, exist_ok=True)
    write_summary(per_rule, per_cwe, args.out_dir / f"{args.scope_name}_per_rule.csv", args.scope_name)
    # Per-CWE
    rows = []
    for cwe_id, e in sorted(per_cwe.items()):
        tp = e["tp"]
        fp = e["fp_total"]
        n = e["n_findings"]
        precision = tp / n if n else 0.0
        rows.append({
            "scope": args.scope_name,
            "cwe_id": cwe_id,
            "tp": tp,
            "fp": fp,
            "n_findings": n,
            "precision": f"{precision:.4f}",
            "n_rules": len(e["rules"]),
        })
    out = args.out_dir / f"{args.scope_name}_per_cwe.csv"
    with out.open("w", encoding="utf-8", newline="") as fh:
        wr = csv.DictWriter(fh, fieldnames=["scope", "cwe_id", "tp", "fp", "n_findings", "precision", "n_rules"])
        wr.writeheader()
        for r in rows:
            wr.writerow(r)

    # Top rules by FP
    print(f"\n=== {args.scope_name} summary ===")
    top_fp = sorted(per_rule.items(), key=lambda kv: -kv[1]["fp_total"])[:15]
    for rule_id, e in top_fp:
        print(f"  {rule_id:55s} tp={e['tp']:5d} fp={e['fp_total']:5d}  prec={e['tp']/max(1,e['n_findings']):.3f}  total={e['n_findings']}")


if __name__ == "__main__":
    main()