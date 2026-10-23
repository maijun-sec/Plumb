"""
cwe_to_rules.py — Map each Juliet CWE to the *exact* Plumb rule ids to enable.

This is a more precise version of cwe_to_families.py. Instead of families
(which Plumb's runStyleChecks ignores — style always runs), we map each
CWE to a list of `plumb --rule <id>` IDs that should fire for that CWE.

Generated from `--list-rules` output (96 rule ids in R684).

Rule categories (from Plumb CLI `--list-rules` output + Plumb-OVERVIEW.md):
  TAINT_SINK_* / TAINT_SOURCE_* — taint family
  WEAK_CRYPTO, INSECURE_RANDOM, STATIC_API_KEY, PREDICTABLE_*, AVOID_HARDCODED_IP — crypto/random
  CORS_MISCONFIGURATION, INSECURE_COMMUNICATION, INSECURE_COOKIE — comms/cookie
  BAD_SHIFT — badShift
  INFINITE_LOOP, UNREACHABLE_CODE — infiniteLoop / constantCondition
  CONSTANT_CONDITION, MISSING_BREAK — constantCondition
  MISSING_AUTHORIZATION, MISSING_INPUT_VALIDATION — auth/input
  INFORMATION_EXPOSURE, SENSITIVE_DATA_LEAK, FIELD_HARDCODED_PASSWORD, EXPOSE_INTERNAL_REPRESENTATION
  HARDCODED_CREDENTIALS — hardcodedCred
  STYLE_* — style (we list none of these for per-CWE eval — they always run)
  CATCH_NPE, INT_DIVISION_CAST_TO_DOUBLE, ARRAY_EQUALITY, ARRAY_HASHCODE_TOSTRING
  IGNORED_RESULT, SUPERFLUOUS_INSTANCEOF, DEAD_STORE
  THROWABLE_NOT_THROWN, UNNECESSARY_RETURN, SELF_ASSIGNMENT
  BIGDECIMAL_DOUBLE_CTOR, BIG_DECIMAL_EQUALS, DEFAULT_ENCODING
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


# Map: CWE id -> list of Plumb rule ids that should fire (we'll pass these
# via `--rule <id>` repeatedly, OR `rules.enabled: [...]` in YAML)
CWE_TO_RULES: dict[str, list[str]] = {
    # ── Taint ──
    "CWE15":  ["TAINT_SINK_EXT_CONTROL"],
    "CWE23":  ["TAINT_SINK_PATH_TRAVERSAL"],
    "CWE36":  ["TAINT_SINK_PATH_TRAVERSAL"],
    "CWE78":  ["TAINT_SINK_COMMAND_INJECTION"],
    "CWE80":  ["TAINT_SINK_XSS"],
    "CWE81":  ["TAINT_SINK_XSS"],
    "CWE83":  ["TAINT_SINK_XSS"],
    "CWE89":  ["TAINT_SINK_SQL_INJECTION"],
    "CWE90":  ["TAINT_SINK_LDAP_INJECTION"],
    "CWE111": ["TAINT_SINK_REFLECTION"],
    "CWE113": ["TAINT_SINK_HEADER_INJECTION"],
    "CWE114": ["TAINT_SINK_COMMAND_INJECTION"],
    "CWE134": ["TAINT_SINK_FORMAT_STRING"],
    "CWE378": ["TAINT_SINK_PATH_TRAVERSAL"],
    "CWE379": ["TAINT_SINK_PATH_TRAVERSAL"],
    "CWE400": ["TAINT_SINK_REGEX_INJECTION"],
    "CWE470": ["TAINT_SINK_REFLECTION"],
    "CWE566": ["TAINT_SINK_SQL_INJECTION"],
    "CWE601": ["TAINT_SINK_OPEN_REDIRECT"],
    "CWE611": ["TAINT_SINK_XML_INJECTION"],
    "CWE643": ["TAINT_SINK_XPATH_INJECTION"],
    # ── Bounds / numeric ──
    "CWE129": ["ARRAY_EQUALITY", "ARRAY_HASHCODE_TOSTRING"],  # best-effort
    "CWE190": ["BAD_SHIFT"],                                  # BAD_SHIFT is closest
    "CWE191": ["BAD_SHIFT"],
    "CWE193": ["ARRAY_EQUALITY"],
    "CWE197": ["BIGDECIMAL_DOUBLE_CTOR", "BIG_DECIMAL_EQUALS", "INT_DIVISION_CAST_TO_DOUBLE"],
    "CWE681": ["BIGDECIMAL_DOUBLE_CTOR", "BIG_DECIMAL_EQUALS", "INT_DIVISION_CAST_TO_DOUBLE"],
    # ── Resource leak (no direct rule, closest: catch-npe and ignored-result) ──
    "CWE404": ["IGNORED_RESULT"],
    "CWE459": ["IGNORED_RESULT"],
    "CWE772": ["IGNORED_RESULT"],
    "CWE775": ["IGNORED_RESULT"],
    "CWE789": [],                                              # no direct rule
    # ── Null / control flow ──
    "CWE395": ["CATCH_NPE"],
    "CWE476": ["CATCH_NPE"],
    "CWE478": ["MISSING_BREAK", "CONSTANT_CONDITION"],
    "CWE484": ["MISSING_BREAK"],
    "CWE561": ["DEAD_STORE", "UNREACHABLE_CODE"],
    "CWE570": ["CONSTANT_CONDITION"],
    "CWE571": ["CONSTANT_CONDITION"],
    "CWE617": ["CONSTANT_CONDITION", "UNREACHABLE_CODE"],
    "CWE690": ["CATCH_NPE"],
    # ── Cryptography ──
    "CWE325": ["WEAK_CRYPTO"],
    "CWE327": ["WEAK_CRYPTO"],
    "CWE328": ["WEAK_CRYPTO"],
    "CWE329": ["WEAK_CRYPTO"],
    "CWE338": ["INSECURE_RANDOM", "RANDOM_USED_ONCE"],
    "CWE759": ["WEAK_CRYPTO"],
    "CWE760": ["WEAK_CRYPTO"],
    # ── Hardcoded credentials ──
    "CWE256": ["HARDCODED_CREDENTIALS"],
    "CWE259": ["HARDCODED_CREDENTIALS", "FIELD_HARDCODED_PASSWORD"],
    "CWE321": ["STATIC_API_KEY", "PREDICTABLE_KEY_PASSWORD"],
    "CWE523": ["HARDCODED_CREDENTIALS", "INSECURE_COMMUNICATION"],
    "CWE549": ["HARDCODED_CREDENTIALS"],
    "CWE798": ["HARDCODED_CREDENTIALS", "STATIC_API_KEY"],
    # ── PRNG ──
    "CWE336": ["PREDICTABLE_RANDOM_SEED"],
    # ── Info exposure / sensitive data ──
    "CWE209": ["INFORMATION_EXPOSURE", "AVOID_PRINT_STACK_TRACE"],
    "CWE226": ["SENSITIVE_DATA_LEAK", "EXPOSE_INTERNAL_REPRESENTATION"],
    "CWE499": ["SENSITIVE_DATA_LEAK"],
    "CWE526": ["INFORMATION_EXPOSURE"],
    "CWE533": ["INFORMATION_EXPOSURE"],
    "CWE534": ["INFORMATION_EXPOSURE"],
    "CWE535": ["INFORMATION_EXPOSURE"],
    "CWE598": ["INFORMATION_EXPOSURE"],
    "CWE615": ["INFORMATION_EXPOSURE"],
    # ── Cookie / comms ──
    "CWE315": ["INSECURE_COOKIE"],
    "CWE319": ["INSECURE_COMMUNICATION"],
    "CWE539": ["INSECURE_COOKIE"],
    "CWE613": ["INSECURE_COOKIE"],
    "CWE614": ["INSECURE_COOKIE"],
    # ── Exception / catch ──
    "CWE248": ["THROWABLE_NOT_THROWN", "STYLE_EMPTY_CATCH"],
    "CWE600": ["THROWABLE_NOT_THROWN"],
    "CWE390": ["STYLE_EMPTY_CATCH"],
    "CWE396": ["STYLE_CATCH_GENERIC"],
    "CWE397": ["THROWABLE_NOT_THROWN"],
    "CWE584": ["UNNECESSARY_RETURN"],
    # ── Loop / infinite ──
    "CWE606": ["INFINITE_LOOP"],
    "CWE835": ["INFINITE_LOOP", "UNREACHABLE_CODE"],
    # ── Unchecked return ──
    "CWE252": ["IGNORED_RESULT"],
    "CWE253": ["IGNORED_RESULT"],
    # ── Misc ──
    "CWE477": ["DEFAULT_ENCODING"],                           # closest: deprecated APIs
    "CWE481": ["SELF_ASSIGNMENT"],                             # closest
    "CWE482": ["SELF_ASSIGNMENT"],                             # closest (instead of comparing)
    "CWE483": ["EMPTY_STATEMENT"],
    "CWE486": ["SUPERFLUOUS_INSTANCEOF"],                      # closest
    "CWE572": ["INFINITE_LOOP"],                               # no direct rule
    "CWE585": ["INFINITE_LOOP"],                               # closest: empty sync block
    "CWE597": ["STRING_EQUALITY_WITH_EQUALS"],
    "CWE609": ["INFINITE_LOOP"],                               # closest: double-checked
    "CWE674": ["INFINITE_LOOP"],                               # closest: recursion
    "CWE681": ["INT_DIVISION_CAST_TO_DOUBLE"],
    "CWE374": ["IGNORED_RESULT"],                              # mutable object passed
    "CWE668": ["IGNORED_RESULT"],                              # non-unlock call
}


# --- CLI helpers ---

def rules_for_cwe(cwe_id: str) -> list[str]:
    return CWE_TO_RULES.get(cwe_id.upper(), [])


def all_rules() -> list[str]:
    seen = set()
    out = []
    for rules in CWE_TO_RULES.values():
        for r in rules:
            if r not in seen:
                seen.add(r)
                out.append(r)
    return sorted(out)


def render_yaml(cwe_id: str, rules: list[str]) -> str:
    """Render a YAML that disables every Plumb rule EXCEPT the ones relevant to this CWE.

    Plumb's `rules.enabled` is a whitelist but only forces enabled=true for matching rules;
    other rules still follow their default (true for most). So we must use `rules.disabled`
    as a blacklist of all rule ids EXCEPT our whitelist.
    """
    enable_set = set(rules)
    disable_ids = [r for r in all_rules() if r not in enable_set]

    # Per-CWE whitelist: rule-level only. We DO NOT touch `checks.<family>`
    # because family flags gate the entire checker pipeline (e.g. `checks.taint:
    # false` skips ALL taint checkers — so even rules we whitelisted won't fire).
    # The `rules.mode: whitelist` + `rules.enabled: [...]` combo is the
    # correct mechanism. To avoid re-emitting a redundant enabled list, we
    # use `rules.disabled` as a blacklist of every rule NOT in our whitelist,
    # and keep whitelistMode ON so any future rule added to the registry
    # defaults to disabled.
    out = [
        f"# Per-CWE YAML for {cwe_id}",
        f"# Enabled rule ids (whitelist): {sorted(enable_set) or '(none — Plumb has no rule for this CWE)'}",
        f"# Disabled rule ids (the other {len(disable_ids)} of {len(all_rules())}): full blacklist",
        f"# Generated by cwe_to_rules.py",
        f"# NOTE: `checks.<family>` is intentionally left at default. Family flags",
        f"# gate the entire checker pipeline — setting them to false would also",
        f"# suppress whitelisted rules, producing 0 findings.",
        "",
        "maxParallelism: 4",
        "cgStrategy: global",
        "aggressiveness: medium",
        "",
        "rules:",
        "  mode: whitelist",
        "  enabled:",
    ]
    if enable_set:
        for r in sorted(enable_set):
            out.append(f"    - {r}")
    else:
        # When no rule is mapped to this CWE, emit an empty enabled list so the
        # whitelist patch forces ALL rules off (defense-in-depth: produces 0
        # findings instead of polluting the CWE with unrelated rule hits).
        out.append("    []")
    out += [
        "  disabled:",
    ]
    for r in disable_ids:
        out.append(f"    - {r}")
    out += [
        "",
        "ai:",
        "  enabled: false",
    ]
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cwe", type=str, help="e.g. CWE89 — single YAML")
    ap.add_argument("--all", action="store_true", help="Generate for all known CWE")
    ap.add_argument("--out", type=Path, help="Output file (single mode)")
    ap.add_argument("--out-dir", type=Path, help="Output directory (--all mode)")
    ap.add_argument("--juliet-root", type=Path, default=Path(r"D:\work\tmp\Juliet_Java\src\testcases"),
                    help="If set, also generate YAMLs for CWE directories that exist but aren't in our mapping")
    args = ap.parse_args()

    if args.cwe:
        rules = rules_for_cwe(args.cwe)
        yaml_text = render_yaml(args.cwe, rules)
        out = args.out or Path(f"D:\\tmp\\plumb_test\\configs\\{args.cwe}.yaml")
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(yaml_text, encoding="utf-8")
        print(f"Wrote {out} (rules={rules})")
        return

    if args.all:
        out_dir = args.out_dir or Path(r"D:\tmp\plumb_test\configs")
        out_dir.mkdir(parents=True, exist_ok=True)
        cwes_to_gen = sorted(CWE_TO_RULES.keys())
        if args.juliet_root.exists():
            for d in args.juliet_root.glob("CWE*_*"):
                stem = d.name.split("_")[0]
                if stem not in cwes_to_gen:
                    cwes_to_gen.append(stem)
        cwes_to_gen = sorted(set(cwes_to_gen))

        n_with = 0
        n_without = 0
        for cwe in cwes_to_gen:
            rules = rules_for_cwe(cwe)
            yaml_text = render_yaml(cwe, rules)
            out = out_dir / f"{cwe}.yaml"
            out.write_text(yaml_text, encoding="utf-8")
            if rules:
                n_with += 1
            else:
                n_without += 1

        print(f"Wrote {len(cwes_to_gen)} YAMLs to {out_dir}")
        print(f"  with rules enabled: {n_with}")
        print(f"  with no rules (Plumb has nothing for this CWE): {n_without}")
        return

    ap.print_help()


if __name__ == "__main__":
    main()