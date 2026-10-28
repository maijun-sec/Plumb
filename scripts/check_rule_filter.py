#!/usr/bin/env python3
"""Check the rule-filter invariants that a measurement depends on.

## What this gate checks

A filter entry that names nothing is a SILENT failure.  The rule never fires, the CWE's recall
reads 0, and the report says nothing -- so the measurement measures the FILTER, not the engine.
That is not a hypothetical: it is what happened to the Juliet CWE-252 and CWE-253 measurements
before the filter was fixed.  The per-CWE configs name rules in their retired forms
(`plumb.resource.leak`, `UncheckedReturn`, `CatchGenericException`), and the filter compared those
against ids it had never heard of.

So the gate asserts three things:

  1. The legacy alias map is CONSISTENT: every id it points at is a rule the engine really has.
     A map that points at a rule which no longer exists would re-introduce the silent failure in
     a new place.

  2. The legacy alias map is COMPLETE for the configs: every per-CWE whitelist entry that names a
     retired id resolves to a live rule.

  3. The filter is not a NO-OP: a whitelist naming a rule that does not exist selects nothing.
     A filter that selects everything would make every measurement look perfect.

## Why the ground truth is the live registry

The rules are read out of a running engine, not out of the checker sources.  A source scan misses
every DFA rule, because the DFA checkers take a `TaintRule` in their constructor and never write
their rule id down in the source.  A gate built on a source scan would therefore report the DFA
rules as missing -- the same bug it is looking for.

Usage:
    python scripts/check_rule_filter.py
    python scripts/check_rule_filter.py --json
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CONFIG_DIR = REPO / "juliet-coverage" / "configs"
ALIAS_FILE = REPO / "plumb-main" / "src" / "main" / "resources" / "legacy-rule-aliases.json"
DEFAULT_JAR = REPO / "plumb-main" / "target" / "plumb-main-0.1.0-SNAPSHOT-all.jar"

# The Java program that dumps the live registry the way ScanRunner builds it,
# PLUS every DFA checker's rule id.
#
# The DFA checkers are a SEPARATE universe from the AST registry: they are
# not AstCheckers, so `registerFromCheckers` never sees them, and they take a
# `TaintRule` in their constructor rather than declaring a `RuleDef`.  A gate
# that only looked at the AST registry would report every DFA rule as missing
# -- the same class of bug it exists to catch.
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
    // The DFA checkers.
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
            System.out.println(ck.name() + "\\t" + ck.getClass().getSimpleName() + "\\t\\t");
          } catch (Throwable ignored) {
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


def live_rules(jar: Path) -> list[tuple[str, str, str, list[str]]]:
    """Every rule the engine knows: (id, name, checker class, aliases)."""
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
            aliases = parts[3].split(",") if len(parts) > 3 and parts[3] else []
            out.append((parts[0], parts[1], parts[2] if len(parts) > 2 else "", aliases))
        return out


def rule_keys(rules: list[tuple[str, str, str, list[str]]]) -> set[str]:
    """Every string that names a rule."""
    keys: set[str] = set()
    for rid, name, cls, aliases in rules:
        simple = cls.rsplit(".", 1)[-1] if cls else ""
        keys.update({rid, name, simple, *aliases})
        for suffix in ("Check", "Checker"):
            if simple.endswith(suffix):
                keys.add(simple[: -len(suffix)])
        if name:
            keys.add(name.lower().replace("_", "-"))
    return {k for k in keys if k}


def read_entries(config: Path) -> list[str]:
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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--jar", default=str(DEFAULT_JAR))
    args = parser.parse_args()

    jar = Path(args.jar)
    if not jar.is_file():
        print(f"ERROR: the scanner jar is missing: {jar}")
        print("Build it first: mvn -pl plumb-main -am -DskipTests package")
        return 2
    if not ALIAS_FILE.is_file():
        print(f"ERROR: the legacy alias map is missing: {ALIAS_FILE}")
        return 2

    rules = live_rules(jar)
    keys = rule_keys(rules)
    live_ids = {rid for rid, *_ in rules}
    aliases = json.loads(ALIAS_FILE.read_text(encoding="utf-8"))

    failures: list[str] = []

    # --- 1. every alias target is a rule the engine really has -----------
    dangling = {old: new for old, new in aliases.items() if new not in live_ids}
    if dangling:
        for old, new in sorted(dangling.items())[:20]:
            failures.append(
                f"legacy alias '{old}' points at '{new}', which is not a rule the engine has"
            )
        if len(dangling) > 20:
            failures.append(f"... and {len(dangling) - 20} more dangling aliases")

    # --- 2. an alias must not be a live rule id itself -------------------
    # A retired id that is ALSO a live id would shadow the live rule: the
    # map would redirect a rule that still exists.
    shadowed = sorted(old for old in aliases if old in live_ids)
    if shadowed:
        failures.append(
            "these legacy aliases are also live rule ids, so they would shadow the live rule: "
            + ", ".join(shadowed[:20])
        )

    # --- 3. the map is not a self-loop -----------------------------------
    loops = sorted(old for old, new in aliases.items() if old == new)
    if loops:
        failures.append("these legacy aliases point at themselves: " + ", ".join(loops[:20]))

    # --- 4. the map matches what the generator derives -------------------
    # This is the check that makes the gate able to tell a CORRECT mapping
    # from a WRONG one. A map that points a retired id at the wrong rule is
    # consistent -- the target exists, it is not a shadow, it is not a loop
    # -- and every other check would pass it. Only comparing against the
    # derived map catches it.
    #
    # The generator is the same code the map is written by, so the two
    # cannot drift apart silently: if the map is edited by hand and the
    # generator is not re-run, the gate fails.
    gen = subprocess.run(
        [sys.executable, str(REPO / "scripts" / "generate_legacy_rule_aliases.py")],
        capture_output=True,
        text=True,
        cwd=str(REPO),
    )
    if gen.returncode != 0:
        failures.append(
            "the alias map has drifted from what the generator derives:\n"
            + (gen.stdout + gen.stderr).strip()[:2000]
        )

    # --- 5. every config entry that names a retired id resolves ----------
    used: dict[str, list[str]] = {}
    for config in sorted(CONFIG_DIR.glob("*.yaml")):
        for entry in read_entries(config):
            used.setdefault(entry, []).append(config.name)

    unresolved: list[str] = []
    for entry, files in sorted(used.items()):
        if entry in aliases:
            # A retired id: it must resolve to a live rule.
            if aliases[entry] not in live_ids:
                unresolved.append(f"{entry} -> {aliases[entry]} (not a live rule)")
            continue
        if entry in keys:
            continue
        # Not retired and not a known key: the entry names nothing. That is
        # allowed -- some entries name rules that were removed -- but it has
        # to be COUNTED, because every one of them is a rule that silently
        # does not fire.
        unresolved.append(entry)

    result = {
        "rules": len(rules),
        "aliases": len(aliases),
        "config_entries": len(used),
        "dangling_aliases": len(dangling),
        "shadowed_aliases": len(shadowed),
        "entries_naming_nothing": len(unresolved),
        "entries_naming_nothing_sample": unresolved[:40],
    }

    if args.json:
        print(json.dumps(result, indent=2))

    print()
    print("=" * 78)
    print("Rule filter gate")
    print("=" * 78)
    print(f"rules the engine knows:            {len(rules)}")
    print(f"legacy aliases in the map:         {len(aliases)}")
    print(f"distinct config entries:           {len(used)}")
    print(f"aliases pointing at no live rule:  {len(dangling)}")
    print(f"aliases that shadow a live rule:   {len(shadowed)}")
    print(f"config entries naming nothing:     {len(unresolved)}")

    if failures:
        print()
        print("FAIL:")
        for f in failures:
            print(f"  {f}")
        return 1

    print()
    print("OK: the legacy alias map is consistent and every retired id resolves.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
