"""
build_plumb_rule_cwe_map.py — merge all three sources into a single
rule_id → [CWE] map for the strict eval.

Sources:
  1. RuleRegistry.withBuiltinDefaults() — 96 rules (plumb-rules/...)
  2. DfaChecker emit sites — 60+ rules (plumb-dfa/...)
  3. AstChecker emit sites — partial (plumb-style/.../style/*.java)

The merged map gives a rule_id (as emitted in NDJSON's `rule_id` field)
→ set of CWE ids. We use this in the strict eval to only count findings
where rule's CWE matches the test case's CWE.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
from collections import defaultdict
from pathlib import Path


# Reuse the DFA extractor
sys_path = Path(__file__).parent
import sys
sys.path.insert(0, str(sys_path))

from extract_dfa_rule_cwe_map import extract_from_file as extract_dfa_from_file


def extract_registry_rules(rr_path: Path) -> list[dict]:
    """Parse RuleRegistry.java — register(def("UUID", "NAME", ...)) calls."""
    text = rr_path.read_text(encoding="utf-8", errors="replace")
    REGISTER_RE = re.compile(
        r'register\(def\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]*)"\s*,\s*'
        r'RuleDef\.Severity\.\w+\s*,\s*RuleDef\.Type\.\w+\s*,\s*'
        r'(List\.of\(\)|List\.of\([^)]*\))\s*,\s*"[^"]+"\s*\)\)',
        re.DOTALL,
    )
    CWE_RE = re.compile(r'CWE-(\d+)')
    rows = []
    for m in REGISTER_RE.finditer(text):
        rid = m.group(1)
        name = m.group(2)
        list_literal = m.group(4)
        cwes = CWE_RE.findall(list_literal)
        rows.append({"rule_id": rid, "rule_name": name, "cwes": cwes})
    return rows


def extract_style_rules(style_root: Path) -> list[dict]:
    """Extract CWE from plumb-style checkers via Finding.violation or
    ctx.reporter().report(new Finding(...)) — same patterns as DFA."""
    rows = []
    for java_file in style_root.rglob("*.java"):
        rows.extend(extract_dfa_from_file(java_file))
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rule-registry", type=Path,
                    default=Path(r"D:\work\workspace\idea\engine\Plumb\plumb-rules\src\main\java\org\plumb\rules\RuleRegistry.java"))
    ap.add_argument("--plumb-dfa-root", type=Path,
                    default=Path(r"D:\work\workspace\idea\engine\Plumb\plumb-dfa\src\main\java\org\plumb\dfa\checkers"))
    ap.add_argument("--plumb-style-root", type=Path,
                    default=Path(r"D:\work\workspace\idea\engine\Plumb\plumb-style\src\main\java\org\plumb\style"))
    ap.add_argument("--out-json", type=Path,
                    default=Path(r"D:\tmp\plumb_test\plumb_rule_to_cwe.json"))
    ap.add_argument("--out-csv", type=Path,
                    default=Path(r"D:\tmp\plumb_test\plumb_rule_to_cwe.csv"))
    args = ap.parse_args()

    rule_to_cwes: dict[str, set[str]] = defaultdict(set)
    rule_meta: dict[str, dict] = {}

    # Source 1: RuleRegistry
    print(f"[1/3] parsing RuleRegistry {args.rule_registry.name} ...")
    for r in extract_registry_rules(args.rule_registry):
        rid = r["rule_name"]
        rule_to_cwes[rid].update(r["cwes"])
        rule_meta[rid] = {"source": "RuleRegistry", "uuid": r["rule_id"]}

    # Source 2: DFA checkers
    print(f"[2/3] parsing DFA checkers under {args.plumb_dfa_root} ...")
    for java_file in sorted(args.plumb_dfa_root.rglob("*.java")):
        for r in extract_dfa_from_file(java_file):
            rid = r["rule_id"]
            if not rid:
                continue
            rule_to_cwes[rid].update(r["cwes"])
            rule_meta.setdefault(rid, {"source": "DfaChecker", "checker": r["checker_class"]})

    # Source 3: Style checkers
    print(f"[3/3] parsing style checkers under {args.plumb_style_root} ...")
    for r in extract_style_rules(args.plumb_style_root):
        rid = r["rule_id"]
        if not rid:
            continue
        rule_to_cwes[rid].update(r["cwes"])
        rule_meta.setdefault(rid, {"source": "AstChecker", "checker": r["checker_class"]})

    # Normalize to sorted lists
    rule_to_cwes_norm = {k: sorted(v) for k, v in rule_to_cwes.items()}
    print(f"\nTotal unique rules: {len(rule_to_cwes_norm)}")
    n_with_cwe = sum(1 for v in rule_to_cwes_norm.values() if v)
    print(f"With CWE tags: {n_with_cwe}")
    print(f"Without CWE tags: {len(rule_to_cwes_norm) - n_with_cwe}")

    # Write JSON
    args.out_json.parent.mkdir(parents=True, exist_ok=True)
    with args.out_json.open("w", encoding="utf-8") as fh:
        json.dump(rule_to_cwes_norm, fh, indent=2, ensure_ascii=False)

    # Write CSV
    with args.out_csv.open("w", encoding="utf-8", newline="") as fh:
        wr = csv.writer(fh)
        wr.writerow(["rule_id", "cwes", "n_cwe", "source"])
        for rid in sorted(rule_to_cwes_norm.keys()):
            cwes = rule_to_cwes_norm[rid]
            src = rule_meta.get(rid, {}).get("source", "")
            wr.writerow([rid, ",".join(cwes), len(cwes), src])

    # Build CWE → rule_ids reverse map
    cwe_to_rules: dict[str, list[str]] = defaultdict(list)
    for rid, cwes in rule_to_cwes_norm.items():
        for c in cwes:
            cwe_to_rules[c].append(rid)
    cwe_to_rules = {k: sorted(v) for k, v in cwe_to_rules.items()}

    cwe_json = args.out_json.with_name("plumb_cwe_to_rule.json")
    with cwe_json.open("w", encoding="utf-8") as fh:
        json.dump(cwe_to_rules, fh, indent=2, ensure_ascii=False)

    print(f"\nWrote:")
    print(f"  {args.out_json}   ({len(rule_to_cwes_norm)} rules)")
    print(f"  {args.out_csv}    ({len(rule_to_cwes_norm)} rules)")
    print(f"  {cwe_json}   ({len(cwe_to_rules)} CWE keys)")

    # Sample — the most-emitted DFA rules
    print(f"\nSamples (rules that fire most in Juliet):")
    for rid in ["UncheckedReturn", "plumb.numeric.integer-overflow",
                "plumb.numeric.integer-underflow", "plumb.pointer.null-dereference",
                "plumb.resource.leak", "plumb.dfa.insecure-cookie",
                "InsecureCommunication", "CatchGenericException",
                "ThrowGenericException", "OffByOne", "InsecureCrypto",
                "UncheckedException", "UncheckedExceptionPropagator"]:
        cwes = rule_to_cwes_norm.get(rid, [])
        print(f"  {rid:<35} → {cwes if cwes else '(no CWE)'}")


if __name__ == "__main__":
    main()