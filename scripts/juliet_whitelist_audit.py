"""Audit the per-CWE whitelist entries against the rules that actually exist.

A whitelist entry that names nothing is a SILENT failure: the rule never
fires, the CWE's recall reads 0, and nothing says so.  This script makes the
silence loud.

## The ground truth is the live registry, not the source

The obvious way to find out which rules exist is to grep the checker sources
for their rule ids.  That is wrong, and it is wrong in exactly the way this
script exists to detect: the AST checkers declare a `RuleDef` with
`new RuleId("...")`, but the DFA checkers take a `TaintRule` in their
constructor and never write the id down in the source at all.  A source scan
therefore misses every DFA rule and reports all of them as unmatched.

So this script asks the ENGINE.  It runs a tiny Java program that builds the
registry the way `ScanRunner` builds it and prints every rule id, name, class
and alias.  That is the same set the filter matches against, so an entry the
script calls "matched" really is matched at run time.

Usage:
    python scripts/juliet_whitelist_audit.py
    python scripts/juliet_whitelist_audit.py --json
    python scripts/juliet_whitelist_audit.py --strict   # non-zero exit if any entry is unmatched
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
DEFAULT_JAR = REPO / "plumb-main" / "target" / "plumb-main-0.1.0-SNAPSHOT-all.jar"
ALIAS_FILE = REPO / "plumb-main" / "src" / "main" / "resources" / "legacy-rule-aliases.json"

# The Java program that dumps the live registry.  It is written to a temp
# directory at run time so the script has no build-order dependency.
#
# The dump covers the AST registry AND the DFA checkers.  The DFA checkers are
# a separate universe: they are not AstCheckers, so registerFromCheckers never
# sees them, and they take a TaintRule in their constructor rather than
# declaring a RuleDef.  A dump that only looked at the AST registry would
# report every DFA rule as unmatched -- and the per-CWE configs name plenty of
# DFA rules.
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
            System.out.println(ck.name() + "\\t" + ck.getClass().getSimpleName() + "\\t\\t");
          } catch (Throwable ignored) {
          }
        }
      }
    }
  }
}
"""


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


def live_rules(jar: Path) -> list[tuple[str, str, str, list[str]]]:
    """Every rule the engine knows: (id, name, checker class, aliases)."""
    with tempfile.TemporaryDirectory() as tmp:
        tmpdir = Path(tmp)
        src = tmpdir / "DumpAllRuleIds.java"
        src.write_text(DUMP_SOURCE, encoding="utf-8")
        javac = _java_tool("javac")
        cp = subprocess.run(
            [javac, "-cp", str(jar), "-d", str(tmpdir), str(src)],
            capture_output=True,
            text=True,
        )
        if cp.returncode != 0:
            raise SystemExit(f"could not build the registry dumper:\n{cp.stderr}")
        run = subprocess.run(
            [
                _java_tool("java"),
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


def _java_tool(name: str) -> str:
    """The java tool of the JVM that is on PATH, or of JAVA_HOME."""
    home = os.environ.get("JAVA_HOME")
    if home:
        candidate = Path(home) / "bin" / (name + (".exe" if os.name == "nt" else ""))
        if candidate.is_file():
            return str(candidate)
    found = shutil_which(name)
    if found:
        return found
    return name


def shutil_which(name: str) -> str | None:
    from shutil import which

    return which(name)


def keys_of(rules: list[tuple[str, str, str, list[str]]]) -> dict[str, list[str]]:
    """Every string that names a rule, mapped to the rule ids it names."""
    by_key: dict[str, list[str]] = {}
    for rid, name, cls, aliases in rules:
        simple = cls.rsplit(".", 1)[-1] if cls else ""
        keys = {rid, name, simple, *aliases}
        if simple.endswith("Check"):
            keys.add(simple[: -len("Check")])
        if simple.endswith("Checker"):
            keys.add(simple[: -len("Checker")])
        if name:
            keys.add(name.lower().replace("_", "-"))
        for key in keys:
            if key:
                by_key.setdefault(key, [])
                if rid not in by_key[key]:
                    by_key[key].append(rid)
    return by_key


def candidates_for(entry: str, rules: list[tuple[str, str, str, list[str]]]) -> list[str]:
    """The rules an entry could plausibly have meant.

    The per-CWE configs were written against the CHECKER names, and those
    names have since drifted.  So the candidates are found by the SHAPE of the
    name rather than by an exact match.
    """
    hits: list[str] = []
    entry_lower = entry.lower()
    entry_kebab = re.sub(r"(?<!^)(?=[A-Z])", "-", entry).lower()
    for rid, name, cls, aliases in rules:
        simple = cls.rsplit(".", 1)[-1] if cls else ""
        matched_shape = False
        # The entry is the class name with a `Check` / `Checker` suffix dropped.
        for suffix in ("", "Check", "Checker"):
            if simple == entry + suffix:
                hits.append(f"{simple} -> {rid}")
                matched_shape = True
                break
        if matched_shape:
            continue
        # The entry is a prefix of the class name (`OffByOne` vs `OffByOneLoopCheck`).
        if len(entry) >= 4 and simple.startswith(entry):
            hits.append(f"{simple} -> {rid}")
            continue
        # The entry is the class name in lower-kebab.
        if entry_kebab and (
            entry_kebab == simple.lower() or entry_kebab == simple.lower().removesuffix("-check")
        ):
            hits.append(f"{simple} -> {rid}")
            continue
        # The entry is the rule id with its domain segment dropped
        # (`plumb.resource.leak` vs `dfa-taint-leak` share the word `leak`).
        if entry_lower in rid.lower() and len(entry_lower) >= 8:
            hits.append(f"{simple} -> {rid}")
    return sorted(set(hits))[:6]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true")
    parser.add_argument(
        "--strict",
        action="store_true",
        help="exit non-zero when an entry names no rule",
    )
    parser.add_argument("--jar", default=str(DEFAULT_JAR))
    args = parser.parse_args()

    jar = Path(args.jar)
    if not jar.is_file():
        print(f"ERROR: the scanner jar is missing: {jar}")
        print("Build it first: mvn -pl plumb-main -am -DskipTests package")
        return 2

    rules = live_rules(jar)
    by_key = keys_of(rules)

    # A RETIRED rule id resolves through the legacy alias map, exactly as the
    # filter does.  Without this the audit would report every entry that
    # names a retired id as unmatched -- and it would be wrong: the filter
    # DOES select the rule.  An audit that cries wolf is an audit nobody
    # reads.
    legacy: dict[str, str] = {}
    if ALIAS_FILE.is_file():
        legacy = json.loads(ALIAS_FILE.read_text(encoding="utf-8"))

    configs = sorted(CONFIG_DIR.glob("*.yaml"))
    matched: dict[str, list[str]] = {}
    unmatched: dict[str, list[str]] = {}
    for config in configs:
        for entry in read_entries(config):
            if entry in matched:
                matched[entry].append(config.name)
                continue
            if entry in unmatched:
                unmatched[entry].append(config.name)
                continue
            hits = by_key.get(entry)
            if hits:
                matched[entry] = [config.name]
            elif entry in legacy:
                # A retired id: it selects the rule it was renamed to.
                matched[entry] = [config.name]
            else:
                unmatched[entry] = [config.name]

    result = {
        "rules": len(rules),
        "configs": len(configs),
        "entries_matched": dict(sorted(matched.items())),
        "entries_unmatched": dict(sorted(unmatched.items(), key=lambda kv: -len(kv[1]))),
    }
    if args.json:
        print(json.dumps(result, indent=2))
        return 1 if (args.strict and unmatched) else 0

    print()
    print("=" * 78)
    print("Per-CWE whitelist audit")
    print("=" * 78)
    print(f"rules the engine knows: {len(rules)}")
    print(f"configs: {len(configs)}")
    print(f"distinct entries that match a rule: {len(matched)}")
    print(f"distinct entries that match NOTHING: {len(unmatched)}")
    if unmatched:
        print()
        print("ENTRIES THAT NAME NO RULE (each one silently disables its rule):")
        for entry, files in sorted(unmatched.items(), key=lambda kv: -len(kv[1])):
            print(f"  {entry}  (in {len(files)} config(s), e.g. {files[0]})")
            for cand in candidates_for(entry, rules):
                print(f"      closest: {cand}")
    return 1 if (args.strict and unmatched) else 0


if __name__ == "__main__":
    sys.exit(main())
