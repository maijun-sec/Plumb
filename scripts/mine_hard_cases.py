#!/usr/bin/env python3
"""Mine the engine's false positives on the ground-truth test corpus.

## What it does

The corpus ships every testcase as a GROUP of files: the vulnerable program
and the fixed programs live in the SAME testcase, usually in the SAME file,
under different methods.  The script therefore

  1. groups the corpus files into testcases (same flavour + same flow number),
  2. analyses ONE testcase at a time -- the whole group is merged into a
     single program so the taint flows across the group's helper classes,
  3. attributes every finding to the method it landed in, and
  4. compares the vulnerable side with the fixed side RULE BY RULE.

A rule is judged a FALSE POSITIVE when the engine reports it on the fixed
side of a testcase whose vulnerable side the engine also reported -- i.e. the
engine said "this program has the flaw" and then said it about the program
where the flaw is repaired.

## Where the ground truth comes from

The ground truth is read from the corpus itself, and it is METHOD level:

  * `bad*`  (`bad`, `badSink`, `badSource`)  -- the vulnerable program
  * `good*` (`good`, `good1`, `goodG2B`, `goodB2G`, `goodG2BSink`, ...) --
    the repaired programs
  * the flow-81 files name their role in the FILE name (`_81_bad`,
    `_81_goodG2B`, `_81_goodB2G`); their method is called `action`.

The letter suffix of a file (`_54a`, `_54b`, ...) is NOT a bad/good marker:
`_54b` is a helper class of the multi-class flow 54 and carries `badSink`,
`goodG2BSink` and `goodB2GSink` side by side.  The methods decide, not the
letter.

## The rules are self-calibrating -- there is NO CWE-to-rule table

The rule set is not configured here.  Each rule carries its own CWE list
(the same metadata the rule ships with), and the family under measurement
supplies its CWE number (the directory name starts with it).  A rule counts
for this family when the rule's own CWE list contains the family's number:
`dfa-taint-sql-injection` carries `CWE-89`, `dfa-taint-path-traversal`
carries `CWE-22,CWE-23,CWE-36`, `dfa-taint-null-dereference` carries
`CWE-476`.  A finding on a rule that belongs to a DIFFERENT CWE (the
null-dereference noise that every Juliet testcase carries, for instance) is
reported as noise and is not counted as a false positive.

## One measurement uses ONE engine build

The engine snapshot is a shared directory that another team may rebuild while
this script runs, so the classes are copied into a temp directory first and
the copy is what gets measured.  The build is identified in the summary; a
testcase whose analysis hit a budget limit or failed is reported and NOT
judged, because a truncated analysis measures the harness, not the engine.

## Output

One TSV row per (testcase, rule) comparison:

    cwe, testcase, bad_side, good_side, rule, bad_line, good_hit, verdict, message

`bad_side` / `good_side` are `<path relative to the corpus root>#<method>:<line>`.
The summary (comparable testcases, false positives, false-positive rate per
family and globally) is printed to stdout; the rows go to `--out` when given.

## Usage

    python scripts/mine_hard_cases.py --cwe CWE89_SQL_Injection --limit-pairs 60 \
        --out D:\\work\\tmp\\plan\\miner\\sample.tsv
"""

from __future__ import annotations

import argparse
import locale
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import time
from collections import Counter, defaultdict

REPO = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_CORPUS = pathlib.Path(r"D:\work\tmp\Juliet_Java\src\testcases")
DEFAULT_SNAPSHOT = pathlib.Path(r"D:\work\tmp\plan\dfa_snapshot")
DEFAULT_DEPS = pathlib.Path(r"D:\work\tmp\plan\dfa_cp.txt")
# The snapshot holds plumb-dfa only; the sibling modules come from the repo.
MODULE_CLASSES = ("plumb-tac", "plumb-rules", "plumb-reporter", "plumb-parser")

DRIVER_FILES = {"Main.java", "ServletMain.java"}

# A testcase file is `<family>__<flavour>_<flow>[<role>].java`, where the role
# is either a letter (the multi-class flow helpers) or an explicit name (the
# flow-81 files: `_base`, `_bad`, `_goodG2B`, `_goodB2G`).
TESTCASE_RE = re.compile(
    r"^(?P<flavour>.+)_(?P<flow>\d+)(?:_(?P<named>base|bad|goodG2B|goodB2G)|(?P<letter>[a-z]))?$"
)
# A method declaration line.  The role methods are named `bad*` / `good*`, and
# the flow-81 files name theirs `action`.
DECL_RE = re.compile(
    r"^\s*(?:(?:public|private|protected|static|final|abstract|synchronized)\s+)*"
    r"[\w$<>\[\],.]+\s+(?P<name>\w+)\s*\("
)

# The Java probe.  It is embedded as a string so the script owns no build
# output: the source is written to a temp directory, compiled there with
# `javac`, and run from there with `java`.
PROBE_JAVA = r"""
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.MergedProgram;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * Analyse one testcase GROUP at a time and dump every finding.
 *
 * <p>The group is merged into ONE program, so the taint of a multi-class flow
 * (the testcase's helper classes carry the sink) reaches the sink.  The rows
 * are TSV: group index, file, line, rule id, rule CWE list, message.
 */
public final class MineProbe {

  public static void main(String[] args) throws Exception {
    Path manifest = Paths.get(args[0]);
    Path out = Paths.get(args[1]);
    List<List<String>> groups = readGroups(manifest);
    List<DfaChecker> checkers = loadCheckers();
    System.err.println("checkers=" + checkers.size() + " groups=" + groups.size());
    EngineConfig base = ConfigLoader.loadFromClasspath();
    JavaSourceParser parser = new JavaSourceParser();
    StringBuilder sb = new StringBuilder();
    for (int gi = 0; gi < groups.size(); gi++) {
      try {
        analyseGroup(gi, groups.get(gi), base, checkers, parser, sb);
      } catch (Throwable t) {
        sb.append(gi).append('\t').append("!ERROR").append('\t').append(0)
            .append('\t').append("").append('\t').append("").append('\t')
            .append(one(String.valueOf(t))).append('\n');
      }
    }
    Files.writeString(out, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
  }

  private static void analyseGroup(
      int gi,
      List<String> group,
      EngineConfig base,
      List<DfaChecker> checkers,
      JavaSourceParser parser,
      StringBuilder sb)
      throws Exception {
    List<TacProgram> programs = new ArrayList<>();
    for (String f : group) {
      Path p = Paths.get(f);
      ParsedSource src = parser.parse(p);
      programs.add(new TacTranslator(src).translate());
    }
    MergedProgram merged = new MergedProgram(programs);
    Map<String, Path> srcPaths = new HashMap<>();
    for (TacProgram src : merged.sources()) {
      Path path = src.sourcePath();
      if (path == null) {
        continue;
      }
      for (TacFunction fn : src.functions()) {
        String enc = fn.enclosingClass();
        if (enc != null && !enc.isEmpty()) {
          srcPaths.put(enc, path);
        }
      }
    }
    DfaEngine engine = new DfaEngine(base, checkers, "cha", null);
    engine.withFunctionSourcePaths(srcPaths);
    FindingReporter reporter = engine.run(merged.asProgram());
    org.plumb.dfa.engine.AnalysisBudget.BudgetReport budget = engine.budgetReport();
    if (budget != null && !budget.complete()) {
      // The analysis was truncated, so this group cannot be judged.
      sb.append(gi).append('\t').append("!BUDGET").append('\t').append(0).append('\t')
          .append("").append('\t').append("").append('\t')
          .append(one(budget.summary())).append('\n');
    }
    for (Finding f : reporter.all()) {
      Path fp = f.file();
      sb.append(gi).append('\t')
          .append(fp == null ? "?" : fp.toString()).append('\t')
          .append(f.line()).append('\t')
          .append(f.ruleId() == null ? "" : f.ruleId()).append('\t')
          .append(one(f.cwe())).append('\t')
          .append(one(f.message())).append('\n');
    }
  }

  /** The taint checkers of the engine, discovered from the classpath. */
  private static List<DfaChecker> loadCheckers() throws Exception {
    List<DfaChecker> checkers = new ArrayList<>();
    for (String name : checkerClassNames()) {
      try {
        Class<?> c = Class.forName(name);
        Object instance = c.getDeclaredConstructor().newInstance();
        if (instance instanceof DfaChecker) {
          checkers.add((DfaChecker) instance);
        }
      } catch (Throwable t) {
        System.err.println("skip checker " + name + " : " + t);
      }
    }
    return checkers;
  }

  private static List<String> checkerClassNames() throws Exception {
    java.net.URL loc =
        org.plumb.dfa.checkers.taint.SqlInjectionChecker.class
            .getProtectionDomain()
            .getCodeSource()
            .getLocation();
    java.io.File dir =
        new java.io.File(Paths.get(loc.toURI()).toFile(), "org/plumb/dfa/checkers/taint");
    java.io.File[] files = dir.listFiles();
    if (files == null) {
      throw new IllegalStateException("cannot list the taint checkers in " + dir);
    }
    Arrays.sort(files);
    List<String> names = new ArrayList<>();
    for (java.io.File f : files) {
      String n = f.getName();
      if (!n.endsWith(".class") || n.indexOf('$') >= 0) {
        continue;
      }
      names.add("org.plumb.dfa.checkers.taint." + n.substring(0, n.length() - 6));
    }
    return names;
  }

  /** Groups are separated by a line holding {@code ===}. */
  private static List<List<String>> readGroups(Path manifest) throws Exception {
    List<List<String>> groups = new ArrayList<>();
    List<String> current = new ArrayList<>();
    for (String raw : Files.readAllLines(manifest, java.nio.charset.StandardCharsets.UTF_8)) {
      String line = raw;
      if (!line.isEmpty() && line.charAt(0) == '\ufeff') {
        line = line.substring(1);
      }
      if (line.equals("===")) {
        groups.add(current);
        current = new ArrayList<>();
      } else if (!line.trim().isEmpty()) {
        current.add(line.trim());
      }
    }
    if (!current.isEmpty()) {
      groups.add(current);
    }
    return groups;
  }

  private static String one(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("\r", " ").replace("\n", " ").replace("\t", " ");
  }
}
"""


def fail(message: str) -> "NoReturn":  # noqa: F821
    """Print an error and leave with a non-zero status."""
    sys.stderr.write("ERROR: " + message + "\n")
    raise SystemExit(2)


def resolve_classpath(explicit: str | None) -> str:
    """The classpath the probe needs: the dfa snapshot plus its dependencies."""
    if explicit:
        return explicit
    if not DEFAULT_SNAPSHOT.is_dir():
        fail(
            "the engine snapshot %s does not exist -- build it first (see"
            " scripts/mine_hard_cases.py) or pass --classpath" % DEFAULT_SNAPSHOT
        )
    if not DEFAULT_DEPS.is_file():
        fail("the dependency classpath file %s does not exist" % DEFAULT_DEPS)
    entries = [str(DEFAULT_SNAPSHOT)]
    for module in MODULE_CLASSES:
        path = REPO / module / "target" / "classes"
        if not path.is_dir():
            fail(
                "%s does not exist -- the engine modules have to be built once"
                " (see scripts/mine_hard_cases.py)" % path
            )
        entries.append(str(path))
    entries.append(DEFAULT_DEPS.read_text(encoding="utf-8").strip())
    return os.pathsep.join(entries)


def snapshot_fingerprint(root: pathlib.Path) -> str:
    """A short identity of an engine build: file count, size and newest mtime."""
    count = 0
    size = 0
    newest = 0.0
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        count += 1
        size += path.stat().st_size
        newest = max(newest, path.stat().st_mtime)
    stamp = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(newest))
    return "%s: %d file(s), %d byte(s), newest %s" % (root, count, size, stamp)


def compile_probe(classpath: str, workdir: pathlib.Path) -> pathlib.Path:
    """Compile the embedded probe into the temp directory."""
    source = workdir / "MineProbe.java"
    source.write_text(PROBE_JAVA, encoding="utf-8")
    classes = workdir / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        [
            "javac",
            "-J-Duser.language=en",
            "-J-Duser.country=US",
            "-encoding",
            "UTF-8",
            "-nowarn",
            "-proc:none",
            "-cp",
            classpath,
            "-d",
            str(classes),
            str(source),
        ],
        capture_output=True,
    )
    output = (result.stdout + result.stderr).decode(
        locale.getpreferredencoding(False), errors="replace"
    )
    if result.returncode != 0:
        fail("the probe does not compile:\n" + output[:4000])
    return classes


class Testcase:
    """One testcase: the files of one flow, and where its bad/good sides live."""

    def __init__(
        self,
        key: str,
        suite: str,
        flavour: str,
        flow: str,
        files: list[pathlib.Path],
        role_of_file: dict[str, str],
    ):
        self.key = key
        self.suite = suite
        self.flavour = flavour
        self.flow = flow
        self.files = files
        self.role_of_file = role_of_file
        self.spans: dict[str, list[tuple[str, int, int]]] = {}

    def method_spans(self, path: pathlib.Path) -> list[tuple[str, int, int]]:
        """The (name, first line, last line) of the role methods of one file."""
        cached = self.spans.get(str(path))
        if cached is not None:
            return cached
        spans = compute_method_spans(path.read_text(encoding="utf-8", errors="replace"))
        self.spans[str(path)] = spans
        return spans


def compute_method_spans(text: str) -> list[tuple[str, int, int]]:
    """The line span of every role method (`bad*`, `good*`, `action`)."""
    lines = text.splitlines()
    spans: list[tuple[str, int, int]] = []
    for index, line in enumerate(lines):
        stripped = line.strip()
        if not stripped or stripped.endswith(";"):
            continue
        match = DECL_RE.match(line)
        if not match:
            continue
        name = match.group("name")
        if not (name.startswith("bad") or name.startswith("good") or name == "action"):
            continue
        # The body starts at the first brace at or after the declaration.
        cursor = index
        while cursor < len(lines) and "{" not in lines[cursor]:
            cursor += 1
        if cursor >= len(lines):
            continue
        depth = 0
        started = False
        end = cursor
        for probe in range(cursor, len(lines)):
            depth += lines[probe].count("{") - lines[probe].count("}")
            if "{" in lines[probe]:
                started = True
            if started and depth <= 0:
                end = probe
                break
        spans.append((name, index + 1, end + 1))
    return spans


def family_cwe_number(family: str) -> str | None:
    """The CWE number of a family directory, e.g. `CWE89_SQL_Injection` -> `89`."""
    match = re.match(r"^CWE(\d+)", family)
    return match.group(1) if match else None


def collect_testcases(family_dir: pathlib.Path) -> list[Testcase]:
    """Group the family's files into testcases."""
    family = family_dir.name
    groups: dict[tuple[str, str, str], Testcase] = {}
    for path in sorted(family_dir.rglob("*.java")):
        if path.name in DRIVER_FILES:
            continue
        stem = path.stem
        if not stem.startswith(family + "__"):
            continue
        rest = stem[len(family) + 2 :]
        match = TESTCASE_RE.match(rest)
        if not match:
            continue
        flavour = match.group("flavour")
        flow = match.group("flow")
        named = match.group("named")
        letter = match.group("letter")
        role = ""
        if named == "bad":
            role = "bad"
        elif named in ("goodG2B", "goodB2G"):
            role = "good"
        suite = path.parent.name
        key = (suite, flavour, flow)
        testcase = groups.get(key)
        if testcase is None:
            testcase = Testcase(
                "%s/%s_%s" % (suite, flavour, flow), suite, flavour, flow, [], {}
            )
            groups[key] = testcase
        testcase.files.append(path)
        testcase.role_of_file[str(path)] = role
        if letter and not named:
            # The letter files are the helper classes of a multi-class flow;
            # they carry no file-level role, their methods carry it.
            testcase.role_of_file[str(path)] = ""
    return list(groups.values())


def attribute(
    testcase: Testcase, findings: list[dict]
) -> tuple[list[dict], list[dict]]:
    """Split the findings of one testcase into its bad side and its good side."""
    bad: list[dict] = []
    good: list[dict] = []
    for finding in findings:
        path = finding["file"]
        spans = testcase.method_spans(pathlib.Path(path))
        name = None
        line = finding["line"]
        for candidate, first, last in spans:
            if first <= line <= last:
                name = candidate
                break
        if name is None:
            continue
        file_role = testcase.role_of_file.get(str(pathlib.Path(path)), "")
        if name.startswith("bad"):
            side = "bad"
        elif name.startswith("good"):
            side = "good"
        else:
            side = file_role
        if side not in ("bad", "good"):
            continue
        finding = dict(finding)
        finding["method"] = name
        finding["side"] = side
        (bad if side == "bad" else good).append(finding)
    return bad, good


def cwe_numbers(cwe_text: str) -> set[str]:
    """The CWE numbers of a rule's own CWE list, e.g. `CWE-22,CWE-23`."""
    numbers = set()
    for part in (cwe_text or "").split(","):
        part = part.strip().upper().replace("CWE-", "").replace("CWE", "")
        if part.isdigit():
            numbers.add(part)
    return numbers


def run_probe(
    classes: pathlib.Path,
    classpath: str,
    testcases: list[Testcase],
    workdir: pathlib.Path,
) -> list[list[dict]]:
    """Analyse every testcase and return the findings per testcase."""
    manifest = workdir / "manifest.txt"
    lines: list[str] = []
    for testcase in testcases:
        for path in testcase.files:
            lines.append(str(path))
        lines.append("===")
    if lines and lines[-1] == "===":
        lines.pop()
    manifest.write_text("\n".join(lines) + "\n", encoding="utf-8")
    dump = workdir / "findings.tsv"
    result = subprocess.run(
        ["java", "-cp", os.pathsep.join([str(classes), classpath]), "MineProbe",
         str(manifest), str(dump)],
        capture_output=True,
    )
    stderr = result.stderr.decode("utf-8", errors="replace")
    if result.returncode != 0:
        fail("the probe failed (rc=%d):\n%s" % (result.returncode, stderr[-4000:]))
    for line in stderr.splitlines():
        if line.startswith("checkers=") or line.startswith("skip checker"):
            sys.stderr.write(line + "\n")

    per_testcase: list[list[dict]] = [[] for _ in testcases]
    incomplete: dict[int, str] = {}
    if not dump.is_file():
        fail("the probe produced no findings file")
    for line in dump.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 6:
            continue
        index = parts[0]
        if parts[1] in ("!ERROR", "!BUDGET"):
            try:
                incomplete[int(index)] = parts[1] + ": " + parts[5]
            except ValueError:
                pass
            continue
        try:
            group_index = int(index)
            line_number = int(parts[2])
        except ValueError:
            continue
        if group_index < 0 or group_index >= len(per_testcase):
            continue
        per_testcase[group_index].append(
            {
                "file": parts[1],
                "line": line_number,
                "rule": parts[3],
                "cwe": parts[4],
                "message": parts[5],
            }
        )
    return per_testcase, incomplete


def relative(path_text: str, corpus: pathlib.Path) -> str:
    path = pathlib.Path(path_text)
    try:
        return path.relative_to(corpus).as_posix()
    except ValueError:
        return path.as_posix()


def mine_family(
    family_dir: pathlib.Path,
    corpus: pathlib.Path,
    classes: pathlib.Path,
    classpath: str,
    limit: int | None,
    workdir: pathlib.Path,
) -> tuple[list[dict], dict]:
    """Mine one family; returns the rows and the family's counters."""
    family = family_dir.name
    cwe = family_cwe_number(family)
    testcases = collect_testcases(family_dir)
    testcases.sort(key=lambda t: (t.flow, t.flavour, t.suite))
    if limit is not None and limit < len(testcases):
        # A stride, so the sample spans the family's flows and flavours
        # instead of taking the first N groups of one flow.
        total = len(testcases)
        picked = sorted({min(total - 1, (i * total) // limit) for i in range(limit)})
        testcases = [testcases[i] for i in picked]
    if not testcases:
        return [], {"family": family, "cwe": cwe, "pairs": 0, "rows": 0, "fp_rows": 0,
                    "fp_pairs": 0, "no_bad_hit": 0, "no_family_rule": 0, "errors": 0,
                    "rules": Counter(), "noise": Counter()}

    findings_per_testcase, incomplete = run_probe(classes, classpath, testcases, workdir)

    rows: list[dict] = []
    pairs = 0
    fp_rows = 0
    fp_pairs = 0
    no_bad_hit = 0
    no_family_rule = 0
    errors = 0
    rule_counter: Counter = Counter()
    noise_counter: Counter = Counter()
    skipped: list[str] = []

    for index, (testcase, findings) in enumerate(zip(testcases, findings_per_testcase)):
        if index in incomplete:
            # The analysis of this group was truncated or failed, so it cannot
            # be judged -- counting it would measure the harness, not the engine.
            errors += 1
            skipped.append(testcase.key + " (" + incomplete[index] + ")")
            continue
        bad, good = attribute(testcase, findings)
        if not bad:
            no_bad_hit += 1
            skipped.append(testcase.key + " (nothing on the bad side)")
            continue
        # The rule set is self-calibrating: a rule counts for this family when
        # the rule's own CWE list contains the family's CWE number.
        relevant: dict[str, list[dict]] = defaultdict(list)
        for finding in bad:
            numbers = cwe_numbers(finding["cwe"])
            if cwe is not None and cwe in numbers:
                relevant[finding["rule"]].append(finding)
            else:
                noise_counter[finding["rule"]] += 1
        if not relevant:
            no_family_rule += 1
            skipped.append(
                testcase.key + " (the family's rule did not fire on the bad side)"
            )
            continue
        pairs += 1
        pair_has_fp = False
        for rule, hits in sorted(relevant.items()):
            good_hits = [g for g in good if g["rule"] == rule]
            bad_location = ";".join(
                "%s#%s:%d" % (relative(h["file"], corpus), h["method"], h["line"])
                for h in hits
            )
            bad_line = ",".join(str(h["line"]) for h in hits)
            if good_hits:
                pair_has_fp = True
                fp_rows += 1
                rule_counter[rule] += 1
                good_location = ";".join(
                    "%s#%s:%d" % (relative(g["file"], corpus), g["method"], g["line"])
                    for g in good_hits
                )
                rows.append(
                    {
                        "cwe": family,
                        "testcase": testcase.key,
                        "bad_side": bad_location,
                        "good_side": good_location,
                        "rule": rule,
                        "bad_line": bad_line,
                        "good_hit": "YES",
                        "verdict": "FP",
                        "message": good_hits[0]["message"],
                    }
                )
            else:
                rows.append(
                    {
                        "cwe": family,
                        "testcase": testcase.key,
                        "bad_side": bad_location,
                        "good_side": "",
                        "rule": rule,
                        "bad_line": bad_line,
                        "good_hit": "NO",
                        "verdict": "Correct",
                        "message": "",
                    }
                )
        if pair_has_fp:
            fp_pairs += 1
    counters = {
        "family": family,
        "cwe": cwe,
        "pairs": pairs,
        "rows": len(rows),
        "fp_rows": fp_rows,
        "fp_pairs": fp_pairs,
        "no_bad_hit": no_bad_hit,
        "no_family_rule": no_family_rule,
        "errors": errors,
        "rules": rule_counter,
        "noise": noise_counter,
        "skipped": skipped,
    }
    return rows, counters


def main() -> int:
    parser = argparse.ArgumentParser(
        description="mine the engine's false positives on the ground-truth corpus"
    )
    parser.add_argument(
        "--corpus-root",
        default=str(DEFAULT_CORPUS),
        help="the corpus root that holds the CWE family directories",
    )
    parser.add_argument(
        "--cwe",
        action="append",
        default=[],
        help="limit the run to this family (repeatable); default: every family",
    )
    parser.add_argument(
        "--limit-pairs",
        type=int,
        default=None,
        help="compare at most this many testcases per family",
    )
    parser.add_argument("--out", default=None, help="the TSV output path (default: stdout)")
    parser.add_argument(
        "--classpath",
        default=None,
        help="the classpath for the engine (default: the snapshot plus its dependencies)",
    )
    args = parser.parse_args()

    corpus = pathlib.Path(args.corpus_root)
    if not corpus.is_dir():
        fail("the corpus root %s does not exist" % corpus)
    classpath = resolve_classpath(args.classpath)

    if args.cwe:
        families = [corpus / name for name in args.cwe]
        missing = [f for f in families if not f.is_dir()]
        if missing:
            fail(
                "these families do not exist under %s: %s"
                % (corpus, ", ".join(m.name for m in missing))
            )
    else:
        families = sorted(d for d in corpus.iterdir() if d.is_dir())
        families = [f for f in families if family_cwe_number(f.name)]
    if not families:
        fail("no CWE family found under %s" % corpus)

    workdir = pathlib.Path(tempfile.mkdtemp(prefix="plumb-mine-"))
    started = time.time()
    try:
        # The engine snapshot is a shared directory that another team may
        # rebuild while this measurement runs.  One measurement must use ONE
        # build, so the classes are copied into the temp directory first and
        # the copy is what gets measured.
        fingerprint = ""
        if args.classpath is None:
            frozen = workdir / "dfa_snapshot"
            shutil.copytree(DEFAULT_SNAPSHOT, frozen)
            # A shared snapshot can be half-rebuilt (the classes are deleted
            # and recompiled); measuring that would measure a broken build.
            required = [
                frozen / "org" / "plumb" / "dfa" / "checkers" / "taint" / "SqlInjectionChecker.class",
                frozen / "org" / "plumb" / "dfa" / "engine" / "DfaEngine.class",
                frozen / "sink-families.yaml",
            ]
            missing = [str(p) for p in required if not p.is_file()]
            if missing:
                fail(
                    "the engine snapshot %s is incomplete (missing %s) -- it is"
                    " probably being rebuilt right now; retry when the rebuild"
                    " has finished" % (DEFAULT_SNAPSHOT, ", ".join(missing))
                )
            classpath = classpath.replace(str(DEFAULT_SNAPSHOT), str(frozen), 1)
            fingerprint = snapshot_fingerprint(DEFAULT_SNAPSHOT)
            sys.stderr.write("engine snapshot: %s\n" % fingerprint)
        classes = compile_probe(classpath, workdir)
        all_rows: list[dict] = []
        counters: list[dict] = []
        for family_dir in families:
            family_started = time.time()
            rows, family_counters = mine_family(
                family_dir, corpus, classes, classpath, args.limit_pairs, workdir
            )
            all_rows.extend(rows)
            counters.append(family_counters)
            sys.stderr.write(
                "%-46s pairs=%-5d fp=%-4d %.1fs\n"
                % (
                    family_dir.name,
                    family_counters["pairs"],
                    family_counters["fp_rows"],
                    time.time() - family_started,
                )
            )

        header = ["cwe", "testcase", "bad_side", "good_side", "rule", "bad_line",
                  "good_hit", "verdict", "message"]
        lines = ["\t".join(header)]
        for row in all_rows:
            lines.append(
                "\t".join(
                    [
                        row["cwe"],
                        row["testcase"],
                        row["bad_side"],
                        row["good_side"],
                        row["rule"],
                        row["bad_line"],
                        row["good_hit"],
                        row["verdict"],
                        row["message"],
                    ]
                )
            )
        text = "\n".join(lines) + "\n"
        if args.out:
            out_path = pathlib.Path(args.out)
            out_path.parent.mkdir(parents=True, exist_ok=True)
            out_path.write_text(text, encoding="utf-8")
            sys.stderr.write("rows written to %s (%d data rows)\n" % (out_path, len(all_rows)))
        else:
            sys.stdout.write(text)

        print()
        print("=" * 78)
        print("false positives on the ground-truth corpus")
        print("=" * 78)
        print(
            "%-46s %6s %6s %6s %8s %6s %8s"
            % ("family", "pairs", "cmp", "fp", "pair-fp%", "rows", "row-fp%")
        )
        total_pairs = 0
        total_rows = 0
        total_fp_rows = 0
        total_fp_pairs = 0
        for counter in counters:
            rows = counter["rows"]
            fp_rows = counter["fp_rows"]
            pair_rate = (counter["fp_pairs"] / counter["pairs"] * 100) if counter["pairs"] else 0.0
            row_rate = (fp_rows / rows * 100) if rows else 0.0
            print(
                "%-46s %6d %6d %6d %7.2f%% %6d %7.2f%%"
                % (
                    counter["family"],
                    counter["pairs"],
                    rows,
                    fp_rows,
                    pair_rate,
                    rows,
                    row_rate,
                )
            )
            total_pairs += counter["pairs"]
            total_rows += rows
            total_fp_rows += fp_rows
            total_fp_pairs += counter["fp_pairs"]
        pair_rate = (total_fp_pairs / total_pairs * 100) if total_pairs else 0.0
        row_rate = (total_fp_rows / total_rows * 100) if total_rows else 0.0
        print("-" * 78)
        print(
            "%-46s %6d %6d %6d %7.2f%% %6d %7.2f%%"
            % ("GLOBAL", total_pairs, total_rows, total_fp_rows, pair_rate, total_rows, row_rate)
        )
        print()
        print(
            "pairs = testcases where the family's own rule fired on the vulnerable side;"
        )
        print(
            "cmp   = (testcase, rule) comparisons;  fp = false positives;"
        )
        print(
            "noise = rules that fired on the vulnerable side but belong to another CWE"
            " (not counted as false positives)"
        )
        for counter in counters:
            skipped = counter["no_bad_hit"] + counter["no_family_rule"]
            print(
                "  %-46s skipped=%d (no hit on the bad side=%d, no rule of this CWE=%d)"
                % (counter["family"], skipped, counter["no_bad_hit"], counter["no_family_rule"])
            )
            if counter["noise"]:
                top = ", ".join(
                    "%s=%d" % (rule, count)
                    for rule, count in counter["noise"].most_common(6)
                )
                print("      noise rules: " + top)
            if counter["rules"]:
                top = ", ".join(
                    "%s=%d" % (rule, count)
                    for rule, count in counter["rules"].most_common(6)
                )
                print("      false positives by rule: " + top)
            if counter["errors"]:
                print(
                    "      not judged (budget limit or probe error): %d testcase(s)"
                    % counter["errors"]
                )
            for entry in counter.get("skipped", [])[:10]:
                print("      skipped: " + entry)
        print()
        if fingerprint:
            print("engine build measured: " + fingerprint)
            after = snapshot_fingerprint(DEFAULT_SNAPSHOT)
            if after != fingerprint:
                print(
                    "WARNING: the shared snapshot changed while this measurement ran"
                    " (the numbers above are from the frozen copy, which is the build"
                    " named above)"
                )
        print("elapsed %.1fs" % (time.time() - started))
        return 0
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
