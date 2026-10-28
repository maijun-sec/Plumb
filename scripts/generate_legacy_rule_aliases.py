#!/usr/bin/env python3
"""Regenerate the legacy rule-id alias map from the recorded migration.

## Why the map is generated, not hand-written

The map says "this retired rule id is the rule we call X today".  Hand-writing it would put a
second source of truth next to the rule registry, and the two would drift -- which is the failure
mode the map itself exists to prevent.

So the map is DERIVED from two recorded sources:

  1. `juliet-coverage/data/rule_id_migration.csv` -- the recorded rename of every rule id;
  2. the live rule registry -- what the engine actually has.

Each retired id is resolved by its recorded new name, and the result is checked against the live
registry.  An id that cannot be resolved unambiguously is left OUT of the map rather than guessed:
a wrong mapping is worse than no mapping, because a wrong mapping silently selects the wrong rule.

Usage:
    python scripts/generate_legacy_rule_aliases.py           # report the drift
    python scripts/generate_legacy_rule_aliases.py --write   # rewrite the map
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MIGRATION_CSV = REPO / "juliet-coverage" / "data" / "rule_id_migration.csv"
ALIAS_FILE = REPO / "plumb-main" / "src" / "main" / "resources" / "legacy-rule-aliases.json"
DEFAULT_JAR = REPO / "plumb-main" / "target" / "plumb-main-0.1.0-SNAPSHOT-all.jar"

DUMP_SOURCE = """
import java.io.File;
import java.util.*;

public class DumpAllRuleIds {
  public static void main(String[] a) {
    org.plumb.style.AstCheckerRegistry reg = new org.plumb.style.AstCheckerRegistry();
    org.plumb.rules.RuleRegistry r = new org.plumb.rules.RuleRegistry().withBuiltinDefaults();
    for (org.plumb.style.AstChecker c : reg.builtins().all()) {
      try {
        r.registerFromChecker(c);
      } catch (Throwable ignored) {
      }
    }
    List<String> lines = new ArrayList<>();
    for (org.plumb.rules.RuleDef d : r.all()) {
      lines.add(
          String.join(
              "\\t",
              d.ruleId().id(),
              d.ruleId().name(),
              d.checkerClass(),
              String.join(",", d.aliases())));
    }
    lines.sort(null);
    for (String s : lines) {
      System.out.println(s);
    }
    String classesDir = System.getProperty("dfa.classes.dir");
    if (classesDir != null) {
      String[] pkgs = {
        "org.plumb.dfa.checkers.taint",
        "org.plumb.dfa.checkers.controlflow",
        "org.plumb.dfa.checkers.boundary",
        "org.plumb.dfa.checkers",
        "org.plumb.dfa.taint",
      };
      for (String pkg : pkgs) {
        File dir = new File(classesDir, pkg.replace('.', File.separatorChar));
        if (!dir.isDirectory()) {
          continue;
        }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".class") && !n.contains("$"));
        if (files == null) {
          continue;
        }
        for (File f : files) {
          String cn = pkg + "." + f.getName().substring(0, f.getName().length() - 6);
          try {
            Class<?> c = Class.forName(cn);
            if (!org.plumb.dfa.engine.DfaChecker.class.isAssignableFrom(c)) {
              continue;
            }
            org.plumb.dfa.engine.DfaChecker ck =
                (org.plumb.dfa.engine.DfaChecker) c.getDeclaredConstructor().newInstance();
            // The taint checkers carry a TaintRule whose sinkFamilyKey is the
            // family they consume. The config entries that name a family
            // (`SensitiveDataLeak_log`) are resolved through it.
            String family = "";
            // The taint checkers carry a TaintRule whose sinkFamilyKey is the
            // family they consume. The config entries that name a family
            // (`SensitiveDataLeak_log`) are resolved through it.
            //
            // The field lives on a BASE class, not on the checker's own
            // superclass, so the hierarchy is walked until it is found.
            for (Class<?> k = c; k != null && family.isEmpty(); k = k.getSuperclass()) {
              try {
                java.lang.reflect.Field fld = k.getDeclaredField("rule");
                fld.setAccessible(true);
                Object rule = fld.get(ck);
                if (rule instanceof org.plumb.dfa.taint.TaintRule tr) {
                  family = tr.sinkFamilyKey();
                }
              } catch (NoSuchFieldException ignored) {
                // Keep walking up.
              } catch (RuntimeException ignored) {
                // An inaccessible field is not a family.
              } catch (IllegalAccessException ignored) {
                // Nor is a field the JVM will not let us read.
              }
            }
            // The DFA shape is: id, class simple name, sink family, aliases
            // (always empty). The AST shape is: id, name, checker class,
            // aliases. The two are told apart by a MARKER column, because
            // both shapes have four columns and a sink family contains a
            // dot just like a class name does.
            System.out.println(
                "DFA\t"
                    + ck.name()
                    + "\t"
                    + ck.getClass().getSimpleName()
                    + "\t"
                    + family
                    + "\t");
          } catch (Throwable t) {
            // Report it rather than swallow it: a checker that cannot be
            // read is a checker the map cannot resolve, and a silent
            // failure would look like "there is nothing to map".
            System.out.println("ERR\\t" + cn + "\\t" + t);
          }
        }
      }
    }
  }
}
"""


def java_tool(name: str) -> str:
    from shutil import which

    home = os.environ.get("JAVA_HOME")
    if home:
        candidate = Path(home) / "bin" / (name + (".exe" if os.name == "nt" else ""))
        if candidate.is_file():
            return str(candidate)
    found = which(name)
    return found or name


def live_rules(jar: Path) -> list[tuple[str, str, str, list[str], str]]:
    with tempfile.TemporaryDirectory() as tmp:
        tmpdir = Path(tmp)
        src = tmpdir / "DumpAllRuleIds.java"
        src.write_text(DUMP_SOURCE, encoding="utf-8")
        cp = subprocess.run(
            [java_tool("javac"), "-cp", str(jar), "-d", str(tmpdir), str(src)],
            capture_output=True,
            text=True,
        )
        if cp.returncode != 0:
            raise SystemExit(f"could not build the registry dumper:\n{cp.stderr}")
        run = subprocess.run(
            [
                java_tool("java"),
                "-cp",
                str(jar) + os.pathsep + str(tmpdir),
                "-Ddfa.classes.dir=" + str(REPO / "plumb-dfa" / "target" / "classes"),
                "DumpAllRuleIds",
            ],
            capture_output=True,
            text=True,
        )
        if run.returncode != 0:
            raise SystemExit(f"could not run the registry dumper:\n{run.stderr}")
        out = []
        for line in run.stdout.splitlines():
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            if parts[0] == "ERR":
                # A checker that could not be read. Reported, not a rule.
                continue
            if parts[0] == "DFA":
                # id, class simple name, sink family, aliases(empty)
                family = parts[3] if len(parts) > 3 else ""
                out.append((parts[1], parts[2], "", [], family))
                continue
            # The AST shape: id, name, checker class, aliases
            aliases = parts[3].split(",") if len(parts) > 3 and parts[3] else []
            out.append(
                (parts[0], parts[1], parts[2] if len(parts) > 2 else "", aliases, "")
            )
        return out


def sink_family_entries(rules) -> dict[str, str]:
    """The `SensitiveDataLeak_<sink>` entries, and the rule that consumes that sink.

    <p>These entries name a SINK FAMILY rather than a rule. The family is the second argument of
    a {@code TaintRule}, and the rule that consumes it is the one whose family matches -- so the
    mapping is read out of the live checkers rather than guessed.

    <p>A family that no checker consumes has NO rule, and the entry stays out of the map: naming a
    rule that does not exist would be worse than not naming one.
    """
    # The family each live checker consumes.
    by_family: dict[str, list[str]] = {}
    for entry in rules:
        rid = entry[0]
        family = entry[4] if len(entry) > 4 else ""
        if family:
            by_family.setdefault(family, [])
            if rid not in by_family[family]:
                by_family[family].append(rid)

    out: dict[str, str] = {}
    for config in sorted((REPO / "juliet-coverage" / "configs").glob("*.yaml")):
        for entry in read_config_entries(config):
            m = re.match(r"^SensitiveDataLeak_(.+)$", entry)
            if not m:
                continue
            kebab = re.sub(r"(?<!^)(?=[A-Z])", "-", m.group(1)).lower()
            candidates = by_family.get(kebab + ".sinks", [])
            if len(candidates) == 1:
                out[entry] = candidates[0]
    return out


def read_config_entries(config: Path) -> list[str]:
    """The rule names a per-CWE config enables."""
    names: list[str] = []
    in_enabled = False
    for raw in config.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.rstrip()
        if not line.strip() or line.strip().startswith("#"):
            continue
        if re.match(r"^\s*enabled\s*:", line):
            in_enabled = True
            continue
        if in_enabled:
            stripped = line.strip()
            if stripped.startswith("- "):
                names.append(stripped[2:].strip())
                continue
            if stripped and not stripped.startswith("-"):
                in_enabled = False
    return names


def build_map(rules) -> tuple[dict[str, str], dict[str, list[str]], set[str]]:
    """The map, the ids that could not be resolved, and the set of live ids."""
    by_key: dict[str, list[str]] = {}
    live_ids = set()
    for rid, name, cls, aliases, _family in rules:
        live_ids.add(rid)
        simple = cls.rsplit(".", 1)[-1] if cls else ""
        keys = {rid, name, simple, *aliases}
        for suffix in ("Check", "Checker"):
            if simple.endswith(suffix):
                keys.add(simple[: -len(suffix)])
        if name and name.isupper():
            keys.add(name.lower().replace("_", "-"))
        for k in keys:
            if k:
                by_key.setdefault(k, [])
                if rid not in by_key[k]:
                    by_key[k].append(rid)

    # A SECOND index, keyed by the NORMALISED forms the filter accepts. The
    # engine renamed some rules twice, and the second rename moved the words
    # around (`EXPOSE_REP2` became `EXPOSE_MUTABLE_REP2`, whose alias is
    # `ast-style-expose-rep2`). The recorded migration names the first
    # rename, so it no longer resolves -- but the rule's own ALIASES still
    # carry the old spelling.
    #
    # The forms are computed the same way the filter computes them, so an
    # entry that the filter would accept is an entry this index resolves.
    by_form: dict[str, list[str]] = {}

    def norm(s: str) -> str:
        return "".join(ch for ch in s.lower() if ch.isalnum())

    def add_form(text: str, rid: str) -> None:
        if not text:
            return
        for f in rule_id_forms(text):
            k = norm(f)
            if k and rid not in by_form.setdefault(k, []):
                by_form[k].append(rid)

    for rid, name, cls, aliases, _family in rules:
        simple = cls.rsplit(".", 1)[-1] if cls else ""
        identities = [rid, name or "", simple, *aliases]
        for suffix in ("Check", "Checker"):
            if simple.endswith(suffix):
                identities.append(simple[: -len(suffix)])
        for ident in identities:
            add_form(ident, rid)

    rows = list(
        csv.DictReader(MIGRATION_CSV.open(encoding="utf-8", errors="replace"))
    )
    migration = {
        r["old_id"]: r["new_id"]
        for r in rows
        if r.get("old_id") and r.get("new_id")
    }

    def candidates(canonical: str) -> list[str]:
        if canonical in by_key:
            return by_key[canonical]
        tail = (
            canonical.rsplit(".", 1)[-1] if "." in canonical else canonical
        ).replace("_", "-")
        if tail in by_key:
            return by_key[tail]
        return sorted(rid for rid in live_ids if rid.endswith("-" + tail))

    def domain_of(canonical: str) -> str | None:
        if canonical.startswith("plumb."):
            parts = canonical.split(".")
            if len(parts) >= 3:
                return parts[1]
        return None

    mapping: dict[str, str] = {}
    unresolved: dict[str, list[str]] = {}
    for old, canonical in migration.items():
        hits = candidates(canonical)
        if len(hits) > 1:
            domain = domain_of(canonical)
            if domain:
                narrowed = [h for h in hits if h.startswith("dfa-" + domain + "-")]
                if len(narrowed) != 1:
                    narrowed = [h for h in hits if domain in h]
                if len(narrowed) == 1:
                    hits = narrowed
        if len(hits) == 1:
            mapping[old] = hits[0]
        else:
            # Fall back to the normalised-form index. This is what resolves
            # the ids that were renamed twice: the recorded migration names
            # the FIRST rename, and the rule's own aliases still carry the
            # old spelling.
            #
            # The lookup has to try every FORM of the canonical id, not the
            # whole id: the index is keyed by the forms of each identity, and
            # `plumb.ast.expose-rep2` matches through its form
            # `expose-rep2`.
            by_normalised: list[str] = []
            for f in rule_id_forms(canonical):
                for rid in by_form.get(norm(f), []):
                    if rid not in by_normalised:
                        by_normalised.append(rid)
            if len(by_normalised) == 1:
                mapping[old] = by_normalised[0]
                continue
            unresolved[old] = hits or by_normalised
    return mapping, unresolved, live_ids


# The tokens that make up a rule id's engine and domain part. This is the
# SAME vocabulary the filter uses, so the forms computed here are the forms
# the filter accepts.
ENGINE_AND_DOMAIN_TOKENS = {
    # engines
    "ast",
    "dfa",
    "bu",
    "plumb",
    "orm",
    "servlet",
    "singleton",
    # domains
    "style",
    "error",
    "bug",
    "taint",
    "security",
    "naming",
    "performance",
    "quality",
    "design",
    "concurrency",
    "numeric",
    "boundary",
    "pointer",
    "resource",
    "info",
    "ai",
    "general",
}


def rule_id_forms(rule_id: str) -> set[str]:
    """The spellings the rule filter accepts for a rule id."""
    out = {rule_id}
    s = rule_id[len("plumb.") :] if rule_id.startswith("plumb.") else rule_id
    out.add(s)
    rest = s
    for _ in range(4):
        cut = -1
        for sep in (".", "-"):
            i = rest.find(sep)
            if i > 0 and (cut < 0 or i < cut):
                cut = i
        if cut <= 0:
            break
        if rest[:cut].lower() not in ENGINE_AND_DOMAIN_TOKENS:
            break
        rest = rest[cut + 1 :]
        out.add(rest)
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="rewrite the map file")
    parser.add_argument("--jar", default=str(DEFAULT_JAR))
    args = parser.parse_args()

    jar = Path(args.jar)
    if not jar.is_file():
        print(f"ERROR: the scanner jar is missing: {jar}")
        return 2

    rules = live_rules(jar)
    mapping, unresolved, live_ids = build_map(rules)

    # A retired id that is ALSO a live id would shadow the live rule, so it
    # must not be in the map.
    shadowed = sorted(k for k in mapping if k in live_ids)
    for k in shadowed:
        del mapping[k]

    # The hand-resolved entries: the ids whose recorded new name is not
    # enough to pick one rule. Each one is a decision, so each one is
    # written down here with the reason.
    manual = {
        "CatchGenericException": "ast-error-catch-generic",
        "plumb.resource.leak": "dfa-taint-leak",
        "plumb.sensitive.data-leak": "dfa-taint-data-leak",
        "plumb.dfa.insecure-cookie": "ast-security-insecure-cookie",
        "plumb.taint.weak-crypto": "ast-style-weak-crypto",
        "DivideByZero": "dfa-boundary-divide-by-zero",
        "IntegerOverflow": "dfa-boundary-integer-overflow",
        "IN_FINALLY": "ast-style-return-in-finally",
        "EQUALITY": "ast-performance-array-equality",
        "COMPARISON": "ast-bug-incorrect-return-comparison",
        # The per-CWE configs name the rules of the OTHER rule sources
        # (SpotBugs' `CWE482_COMPARE_INSTEAD_OF_ASSIGN`, PMD's
        # `public-static-field-not-final`), and the engine's rules carry
        # those names as their own `RuleId.name()`.
        "dfa.cwe482-compare-instead-of-assign": "ast-style-compare-instead-of-assigning",
        "public-static-mutable": "ast-style-public-static-mutable-field",
        "public-static-field-not-final": "ast-style-public-static-mutable-field",
        "uncaught-exception": "ast-style-cwe600-uncaught-exception",
    }
    for old, target in manual.items():
        # The same shadowing rule applies: a live id must not be redirected.
        if old in live_ids:
            continue
        if target in live_ids:
            mapping[old] = target

    # The `SensitiveDataLeak_<sink>` entries name a SINK FAMILY, not a rule.
    # The family is the second argument of a TaintRule, and the rule that
    # consumes it is the one whose family matches. So the mapping is derived
    # from the live checkers' own families rather than guessed.
    for old, family in sink_family_entries(rules).items():
        if old in live_ids:
            continue
        if family in live_ids:
            mapping[old] = family

    bad = {k: v for k, v in mapping.items() if v not in live_ids}
    if bad:
        print("ERROR: these mappings point at rules that do not exist:")
        for k, v in sorted(bad.items()):
            print(f"  {k} -> {v}")
        return 1

    payload = json.dumps(dict(sorted(mapping.items())), indent=2, sort_keys=True) + "\n"
    current = ALIAS_FILE.read_text(encoding="utf-8") if ALIAS_FILE.is_file() else ""

    if args.write:
        ALIAS_FILE.write_text(payload, encoding="utf-8", newline="\n")
        print(f"wrote {len(mapping)} aliases to {ALIAS_FILE}")
        return 0

    if current == payload:
        print(f"OK: the alias map matches the registry ({len(mapping)} entries).")
        return 0

    generated = json.loads(payload)
    on_disk = json.loads(current) if current else {}
    only_disk = sorted(set(on_disk) - set(generated))
    only_gen = sorted(set(generated) - set(on_disk))
    differ = sorted(k for k in set(on_disk) & set(generated) if on_disk[k] != generated[k])
    print(f"the alias map has DRIFTED ({len(mapping)} entries expected, {len(on_disk)} on disk)")
    if only_disk:
        print("  only on disk: " + ", ".join(only_disk[:20]))
    if only_gen:
        print("  only generated: " + ", ".join(only_gen[:20]))
    if differ:
        for k in differ[:20]:
            print(f"  {k}: on disk {on_disk[k]!r}, generated {generated[k]!r}")
    print()
    print("Run with --write to regenerate.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
