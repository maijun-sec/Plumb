#!/usr/bin/env python3
"""Build rule_id -> CWE mapping by scanning Plumb source code.

Sources scanned:
  - plumb-dfa/src/main/java/.../checkers/*.java
    - super(new TaintRule(...))  + RULE_ID literal + nearby CWE-NN comment
  - plumb-style/src/main/java/.../**/*.java
    - All Check.java classes — name() method returns rule id;
      RuleDef.DEFAULT_RULE constructor carries `List.of("CWE-NNN")`
      as the 5th arg.
  - plumb-dfa/src/main/resources/checkers.yaml (PascalCase -> CWE)
  - plumb-dfa/src/main/resources/cwe-mappings.yaml
"""
import json
import re
from collections import defaultdict
from pathlib import Path

SRC = Path(r"D:\work\workspace\idea\engine\Plumb")
PLUMB_DFA = SRC / "plumb-dfa" / "src" / "main"
PLUMB_STYLE = SRC / "plumb-style" / "src" / "main"

# DFA TaintRule patterns
RULE_RID = r'(?:["\'](?P<rid_lit>[^"\']+)["\']|(?P<rid_const>RULE_ID))'
RULE_TAINTRE_RE = re.compile(
    r'super\(\s*new(?:\s*TaintRule)?\s*\(\s*' + RULE_RID +
    r'\s*,\s*(?://[^\n]*\n\s*)*["\'][^"\']+["\']'  # allow inline comments
    r'\s*,\s*(?://[^\n]*\n\s*)*["\'][^"\']+["\']'
    r'\s*,\s*(?://[^\n]*\n\s*)*["\'](?P<cwe>CWE-\d+)["\']'
)
RULE_TAINTRULE_OF_RE = re.compile(
    r'TaintRule\.of\(\s*' + RULE_RID +
    r'\s*,\s*(?://[^\n]*\n\s*)*["\'][^"\']+["\']'
    r'\s*,\s*(?://[^\n]*\n\s*)*["\'][^"\']+["\']'
    r'\s*,\s*(?://[^\n]*\n\s*)*["\'](?P<cwe>CWE-\d+)["\']'
)
RULE_NEW_TR_RE = re.compile(
    r'new\s+TaintRule\(\s*' + RULE_RID +
    r'\s*,\s*(?://[^\n]*\n\s*)*["\'][^"\']+["\']'
    r'\s*,\s*(?://[^\n]*\n\s*)*["\'][^"\']+["\']'
    r'\s*,\s*(?://[^\n]*\n\s*)*["\'](?P<cwe>CWE-\d+)["\']'
)
RULE_ID_LITERAL_RE = re.compile(
    r'private\s+static\s+final\s+String\s+RULE_ID\s*=\s*["\'](?P<rid>[^"\']+)["\']'
)
CWE_NAME_RE = re.compile(r'dfa\.cwe(?P<num>\d+)')

# AST Check patterns
NAMESAME_RE = re.compile(
    r'public\s+String\s+name\(\)\s*\{[^}]*?return\s+"([a-zA-Z][a-zA-Z0-9_]*)"',
)
# RuleDef carries List.of("CWE-NNN", ...) in the 5th arg; capture all
RULEDEF_CWES_RE = re.compile(r'List\.of\(\s*"CWE-(\d+)"(?:\s*,\s*"CWE-\d+")*\s*\)')
# Or List.of(..., "CWE-209", ...) variants
RULEDEF_CWE_INLINE_RE = re.compile(r'CWE-(\d+)')
CWE_ALL_RE = re.compile(r'CWE-(\d+)')

CHECKER_RE = re.compile(
    r'^  (?P<name>[A-Z][A-Za-z]+):\s*\n(?:[^\n]*\n)*?\s*cwe:\s*(?P<cwe>CWE-\d+)',
    re.MULTILINE,
)
CWE_MAP_YAML_RE = re.compile(r'^\s*(?P<key>[A-Za-z_]+):\s*(?P<cwe>CWE-\d+)\s*$', re.MULTILINE)


def scan_dfa_dir(root: Path):
    rule_to_cwe = {}
    seen_log = set()
    for java in root.rglob("*.java"):
        try:
            text = java.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        rid_literal = None
        for m in RULE_ID_LITERAL_RE.finditer(text):
            rid_literal = m.group("rid")
        for pat in (RULE_TAINTRE_RE, RULE_TAINTRULE_OF_RE, RULE_NEW_TR_RE):
            for m in pat.finditer(text):
                rid = m.group("rid_lit") or rid_literal
                if not rid:
                    continue
                cwe = m.group("cwe")
                key = (rid, cwe)
                if key in seen_log:
                    continue
                seen_log.add(key)
                rule_to_cwe.setdefault(rid, cwe)
        for m in RULE_ID_LITERAL_RE.finditer(text):
            rid = m.group("rid")
            cm = CWE_NAME_RE.search(rid)
            if cm:
                key = (rid, f"CWE-{cm.group('num')}")
                if key not in seen_log:
                    seen_log.add(key)
                    rule_to_cwe.setdefault(rid, f"CWE-{cm.group('num')}")
            comment_window = text[max(0, m.start()-3000):m.end()+50]
            cm2 = CWE_ALL_RE.search(comment_window)
            if cm2:
                key = (rid, f"CWE-{cm2.group(1)}")
                if key not in seen_log:
                    seen_log.add(key)
                    rule_to_cwe.setdefault(rid, f"CWE-{cm2.group(1)}")
    return rule_to_cwe


def scan_style_dir(root: Path):
    """AST-style checks: RuleDef.DEFAULT_RULE carries CWE in List.of("CWE-NNN").
    The RuleId first arg is "bu-NNNN-name" or "pl-NNN-name" -> emit id.
    The second arg is the BU/STYLE/UNUSED etc display name (matches --list-rules).
    We map all three: bu-id, display name, plus all AST-derive slugs.
    """
    rule_to_cwe = {}
    for java in root.rglob("*.java"):
        if not java.name.endswith("Check.java"):
            continue
        try:
            text = java.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        if not CWE_ALL_RE.search(text):
            continue
        # find rule ids: new RuleId("...", "DISPLAY_NAME")
        rid_re = re.compile(
            r'new\s+RuleId\(\s*"([^"]+)"\s*,\s*"([A-Z_][A-Z0-9_]+)"\s*\)'
        )
        # find CWE list nearby (within ~500 chars after the rule id)
        for rm in rid_re.finditer(text):
            bu_id, display_name = rm.group(1), rm.group(2)
            # search 500 chars after for CWE list
            window = text[rm.end():rm.end()+500]
            cwes = re.findall(r'CWE-(\d+)', window)
            for cn in cwes:
                cwe_tag = f"CWE-{cn}"
                # prefer "bu-NNNN-..." as canonical key but also store display name
                rule_to_cwe.setdefault(bu_id, cwe_tag)
                rule_to_cwe.setdefault(display_name, cwe_tag)
    return rule_to_cwe


def main():
    print("[*] Scanning plumb-dfa checkers ...", flush=True)
    dfa_map = scan_dfa_dir(PLUMB_DFA)
    print(f"    {len(dfa_map)} dfa rule-cwe pairs", flush=True)

    print("[*] Scanning plumb-style AST checks ...", flush=True)
    style_map = scan_style_dir(PLUMB_STYLE)
    print(f"    {len(style_map)} style rule-cwe pairs", flush=True)

    rule_to_cwe = {}
    for k, v in dfa_map.items():
        rule_to_cwe[k] = v
    for k, v in style_map.items():
        if k not in rule_to_cwe:
            rule_to_cwe[k] = v
    print(f"[*] Merged: {len(rule_to_cwe)} unique rule-cwe", flush=True)

    # Add YAML sources
    checkers_yaml = (PLUMB_DFA / "resources" / "checkers.yaml").read_text(encoding="utf-8")
    n_added = 0
    for m in CHECKER_RE.finditer(checkers_yaml):
        name = m.group("name")
        cwe = m.group("cwe")
        if name not in rule_to_cwe:
            rule_to_cwe[name] = cwe
            n_added += 1
    print(f"[*] +{n_added} from checkers.yaml", flush=True)

    cwe_map_yaml = (PLUMB_DFA / "resources" / "cwe-mappings.yaml").read_text(encoding="utf-8")
    n_added = 0
    for m in CWE_MAP_YAML_RE.finditer(cwe_map_yaml):
        key = m.group("key")
        cwe = m.group("cwe")
        if key not in rule_to_cwe:
            rule_to_cwe[key] = cwe
            n_added += 1
    print(f"[*] +{n_added} from cwe-mappings.yaml", flush=True)

    out = Path(r"D:\tmp\plumb_test\reports\r720\rule_cwe_map.json")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(rule_to_cwe, indent=2, sort_keys=True), encoding="utf-8")
    print(f"[*] Wrote {out} ({len(rule_to_cwe)} entries)")

    # also all_rule_ids.txt
    ids = sorted(rule_to_cwe.keys())
    Path(r"D:\tmp\plumb_test\reports\r720\all_rule_ids.txt").write_text("\n".join(ids), encoding="utf-8")
    print(f"[*] Wrote all_rule_ids.txt ({len(ids)} ids)")

    # show sample style-derived
    print("\nStyle-derived rules sample:")
    for k in sorted(style_map.keys())[:20]:
        print(f"  {k:50s} -> {style_map[k]}")


if __name__ == "__main__":
    main()