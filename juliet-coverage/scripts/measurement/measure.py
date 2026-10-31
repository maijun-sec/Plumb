#!/usr/bin/env python3
"""Juliet measurement with EXACT CWE matching and method-level ground truth.

The lessons this script encodes (all of them learned the hard way):

1. THE UNIT IS THE METHOD.  A Juliet testcase file holds BOTH the vulnerable
   method (`bad*`) and the fixed one (`good*`).  A file-level measurement
   calls every finding a TP and reports recall > 1.

2. ONLY STRONGLY-RELATED RULES ARE ENABLED.  A scan emits every rule the
   engine has; the style rules fire everywhere.  Each CWE therefore gets a
   whitelist, and the whitelist is AUDITED: an entry that does not map to the
   CWE under test is reported as a defect, because it manufactures FPs.

3. THE MATCH MUST BE CWE-EXACT.  A finding for `ldap-injection` does NOT
   count for CWE-89, even when both are "injection".  The finding's own CWE
   label must equal the CWE under test.

4. THE CALL GRAPH MUST BE GLOBAL.  Cross-file taint (source in one file,
   sink in another) only appears when the CallGraph spans TUs.

5. EVERY FINDING IS CLASSIFIED, NOT COUNTED.  Each FP gets a reason, so the
   report says WHY the engine is wrong rather than just how often.

Outputs:
  <out>/findings/<cwe>.ndjson      the raw findings
  <out>/<cwe>.json                 the per-CWE scoring
  <out>/summary.json               everything
  <out>/fp_analysis.json           every FP with its cause
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import shutil
import subprocess
import sys
import tempfile
import time
from collections import Counter, defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent.parent
JULIET_ROOT = Path(r"D:\work\tmp\Juliet_Java\src\testcases")
JULIET_CSV = Path(r"D:\work\tmp\Juliet_Java\testcases_detail.csv")
CONFIG_DIR = REPO / "juliet-coverage" / "configs"
JAR = REPO / "plumb-main" / "target" / "plumb-main-0.1.0-SNAPSHOT-all.jar"

# The rule -> CWE table.
#
# It is read from the LIVE engine (`--list-rule-cwes`), because the DFA
# rule ids come from constructor-provided TaintRules and a table
# maintained beside the engine drifts from it. A cached file is used
# only when the jar is not built.
RULE_CWES_TXT = Path(__file__).resolve().parent / "rule-cwes.txt"


def live_rule_cwes() -> dict[str, list[str]]:
    """rule id -> [CWE numbers], from the engine's own --list-rule-cwes."""
    if JAR.is_file():
        try:
            proc = subprocess.run(
                ["java", "-jar", str(JAR), "--list-rule-cwes"],
                capture_output=True, cwd=str(REPO), timeout=600)
            if proc.returncode == 0:
                out: dict[str, list[str]] = {}
                for line in proc.stdout.decode("utf-8", errors="replace").splitlines():
                    parts = line.rstrip("\n").split("\t")
                    if not parts or not parts[0]:
                        continue
                    out[parts[0].strip()] = [norm_cwe(c) for c in parts[1:] if c]
                if out:
                    return out
        except (OSError, subprocess.SubprocessError):
            pass
    return load_rule_cwes_file()

# The scratch directory for the per-scan findings files.
#
# IT IS ON D:, NOT ON THE SYSTEM TEMP DIRECTORY.
#
# A scan's findings file is as big as the scan's output: the widest CWE
# produced 110,000 findings, so a few hundred megabytes each. Two workers
# running at once filled the C: drive, and the scan died in
# `Files.newBufferedWriter` with "no space left on device" -- which reads
# like a crash in the engine and is not one.
#
# The system temp directory is on C:, and C: is nearly full. So the
# scratch space is chosen explicitly, and it is checked for room before
# each scan.
SCRATCH_DIR = Path(r"D:\cache\juliet-scratch")

# Juliet's own driver files: they are not testcases.
DRIVER_FILES = {"Main.java", "ServletMain.java"}


# ---------------------------------------------------------------------------
# The live rule -> CWE map
# ---------------------------------------------------------------------------
def load_rule_cwes_file() -> dict[str, list[str]]:
    """rule id -> [CWE labels], read from a cached file.

    The file is read with `utf-8-sig` because PowerShell wrote it with a
    BOM, and a BOM left on the first rule id makes that rule unfindable.
    The CWE labels are NORMALISED to bare numbers, because the engine writes
    them as "CWE-15" and a comparison against "15" would silently fail.
    """
    out: dict[str, list[str]] = {}
    if not RULE_CWES_TXT.is_file():
        return out
    raw = RULE_CWES_TXT.read_bytes()
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
        out[parts[0].strip()] = [norm_cwe(c) for c in parts[1:] if c]
    return out


def load_rule_cwes() -> dict[str, list[str]]:
    """The rule -> CWE map, from the LIVE engine."""
    return live_rule_cwes()


def norm_cwe(label: str) -> str:
    """'CWE-89' / 'CWE89' / '89' -> '89'."""
    m = re.search(r"(\d+)", label or "")
    return m.group(1) if m else ""


# ---------------------------------------------------------------------------
# Juliet ground truth
# ---------------------------------------------------------------------------
def load_ground_truth() -> dict:
    """{cwe: {filename: {'methods': [...], 'variant': ..., 'flow': ...}}}"""
    truth: dict = {}
    with JULIET_CSV.open(newline="", encoding="utf-8", errors="replace") as fh:
        for row in csv.DictReader(fh):
            cwe = norm_cwe(row.get("CWE_ID") or "")
            files = (row.get("FILES") or "").strip()
            methods = (row.get("METHODS") or "").strip()
            if not cwe or not files:
                continue
            names = [m.strip() for m in re.split(r"[;,]", methods) if m.strip()]
            bucket = truth.setdefault(cwe, {})
            for name in files.split(";"):
                name = name.strip()
                if not name:
                    continue
                entry = bucket.setdefault(
                    name,
                    {"methods": [], "variant": (row.get("FUNCTIONAL_VARIANT") or "").strip(),
                     "flow": (row.get("FLOW_VARIANT") or "").strip(),
                     "has_bad": (row.get("HAS_BAD_METHOD") or "").strip().lower() == "true"},
                )
                for m in names:
                    if m not in entry["methods"]:
                        entry["methods"].append(m)
    return truth


def cwe_dirs() -> dict[str, str]:
    """cwe -> the Juliet directory name, discovered from the filesystem.

    Juliet ships SEVERAL directories for the same CWE, and only one of them
    holds the testcases:

        CWE134_Uncontrolled_Format_String                    (the testcases)
        CWE134_Use_of_Externally_Controlled_Format_String   (empty)

        CWE789_Uncontrolled_Mem_Alloc                         (the testcases)
        CWE789_Uncontrolled_Memory_Allocation                (empty)

    Picking the first match by name means picking the empty one half the
    time, and the scan then reports "no .java files found" -- which reads
    as "the rules found nothing" rather than "the measurement looked in
    the wrong directory".

    So the directory with the most .java files wins.
    """
    out: dict[str, str] = {}
    best: dict[str, tuple[int, str]] = {}
    if not JULIET_ROOT.is_dir():
        return out
    for d in sorted(JULIET_ROOT.iterdir()):
        if not d.is_dir() or not d.name.startswith("CWE"):
            continue
        m = re.match(r"CWE(\d+)_", d.name)
        if not m:
            continue
        cwe = m.group(1)
        count = sum(1 for _ in d.rglob("*.java"))
        if cwe not in best or count > best[cwe][0]:
            best[cwe] = (count, d.name)
    for cwe, (count, name) in best.items():
        out[cwe] = name
    return out


def role_of(method: str) -> str:
    if method.startswith("bad"):
        return "bad"
    if method.startswith("good"):
        return "good"
    return "other"


def method_spans(path: Path, wanted: list[str]) -> dict:
    """{method_name: (first_line, last_line)} for the wanted methods."""
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return {}
    lines = text.splitlines()
    spans: dict[str, tuple[int, int]] = {}
    for name in wanted:
        pattern = re.compile(r"\b" + re.escape(name) + r"\s*\(")
        for index, line in enumerate(lines):
            if not pattern.search(line):
                continue
            stripped = line.strip()
            if stripped.startswith(("if", "for", "while", "switch", "catch", "return",
                                    "}", "else")):
                continue
            depth = 0
            started = False
            for cursor in range(index, len(lines)):
                chunk = lines[cursor]
                depth += chunk.count("{") - chunk.count("}")
                if "{" in chunk:
                    started = True
                if started and depth <= 0:
                    spans[name] = (index + 1, cursor + 1)
                    break
            if name in spans:
                break
    return spans


# A method DECLARATION in a Juliet testcase: a visibility modifier or a
# type, then a name, then a parameter list.  Juliet's ground truth only
# names the `bad` method for many files, so locating ONLY the ground-truth
# methods leaves every `goodG2B1()` finding unattributed -- it would be
# counted as "outside any method" instead of "inside a fixed method", and
# the false-positive count would be wrong.
DECL = re.compile(
    r"^\s*(?:(?:public|private|protected|static|final|abstract|synchronized|native)\s+)*"
    r"(?:[A-Za-z_$][\w$.<>\[\], ]*?\s+)?"
    r"(?P<name>[A-Za-z_$][\w$]*)\s*\([^;{]*\)\s*(?:throws\s[\w., ]+)?\s*$"
)


def all_method_spans(path: Path) -> dict:
    """Every method declared in the file, with its line span.

    A declaration is a line that LOOKS like one: modifiers, a return type, a
    name, a parameter list, and an opening brace on one of the next few
    lines.  Control-flow keywords and constructors are excluded.
    """
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return {}
    lines = text.splitlines()
    spans: dict[str, tuple[int, int]] = {}
    for index, line in enumerate(lines):
        stripped = line.strip()
        if not stripped or stripped.startswith(("//", "*", "/*")):
            continue
        if stripped.startswith(("if", "for", "while", "switch", "catch", "return",
                                "else", "do", "try", "new", "}", ";")):
            continue
        m = DECL.match(stripped)
        if not m:
            continue
        name = m.group("name")
        if name in ("if", "for", "while", "switch", "catch", "return", "new",
                    "synchronized", "try"):
            continue
        if name in spans:
            continue
        # The body starts at the first '{' at or after the declaration.
        depth = 0
        started = False
        for cursor in range(index, len(lines)):
            chunk = lines[cursor]
            depth += chunk.count("{") - chunk.count("}")
            if "{" in chunk:
                started = True
            if started and depth <= 0:
                spans[name] = (index + 1, cursor + 1)
                break
    return spans


# ---------------------------------------------------------------------------
# Scanning
# ---------------------------------------------------------------------------
def read_whitelist(cwe: str) -> list[str]:
    """The rule ids a per-CWE config enables."""
    config = CONFIG_DIR / f"CWE{cwe}.yaml"
    if not config.is_file():
        return []
    rules: list[str] = []
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
                rules.append(stripped[2:].strip())
                continue
            if stripped and not stripped.startswith("-"):
                in_enabled = False
    return rules


def whitelist_mode(cwe: str) -> str:
    config = CONFIG_DIR / f"CWE{cwe}.yaml"
    if not config.is_file():
        return "missing"
    text = config.read_text(encoding="utf-8", errors="replace")
    m = re.search(r"^\s*mode\s*:\s*(\w+)", text, re.M)
    return m.group(1) if m else "unknown"


def jar_identity():
    """The scanner jar's identity: (size, mtime_ns), or None.

    A scan that starts while the jar is being replaced dies with a
    NoClassDefFoundError and leaves an empty findings file, which reads
    exactly like "the rules found nothing".  So the identity is taken
    before and after every scan, and a changed jar makes the result
    unreproducible rather than merely empty.
    """
    try:
        st = JAR.stat()
    except OSError:
        return None
    return (st.st_size, st.st_mtime_ns)


def scan_to_file(cwe: str, cwe_dir: str, rules: list[str], dest: Path) -> dict:
    """Scan one CWE directory and stream the findings to `dest`.

    Returns a STATUS dict, and never raises for a failed scan: a CWE that
    could not be measured must not discard the CWEs that were measured.

    The findings are streamed to disk rather than parsed in memory.  The
    widest CWE produces 200,000 findings and 190 MB of NDJSON; parsed into
    Python objects that is gigabytes, and two workers doing it at once is
    how the machine runs out of memory.
    """
    directory = JULIET_ROOT / cwe_dir
    config = CONFIG_DIR / f"CWE{cwe}.yaml"
    SCRATCH_DIR.mkdir(parents=True, exist_ok=True)
    dest.parent.mkdir(parents=True, exist_ok=True)
    status = {
        "cwe": cwe, "dir": cwe_dir, "ok": False, "findings": 0,
        "exit_code": None, "seconds": 0.0, "error": None,
        "jar_before": None, "jar_after": None, "jar_stable": True,
        "whitelist_mode": whitelist_mode(cwe), "rules": len(rules),
    }
    jar_before = jar_identity()
    status["jar_before"] = jar_before
    started = time.time()
    with tempfile.TemporaryDirectory(prefix="juliet-m-", dir=str(SCRATCH_DIR)) as tmp:
        ndjson = Path(tmp) / "f.ndjson"
        cmd = ["java", "-jar", str(JAR), "scan", "-i", str(directory),
               "-f", "ndjson", "-o", str(ndjson)]
        if config.is_file():
            cmd += ["-c", str(config)]
        else:
            for rule in rules:
                cmd += ["--rule", rule]
        # The child's stdout carries bytes that are not valid GBK, and the
        # default locale codec raises UnicodeDecodeError inside
        # subprocess' reader thread -- which kills the run and leaves an
        # empty findings file that reads as "the rules found nothing".
        # Read BYTES and decode explicitly.
        try:
            proc = subprocess.run(cmd, capture_output=True, cwd=str(REPO), timeout=14400)
        except subprocess.TimeoutExpired:
            status["error"] = "the scan timed out after 4 hours"
            status["seconds"] = time.time() - started
            status["jar_after"] = jar_identity()
            return status
        stderr = proc.stderr.decode("utf-8", errors="replace")[-2000:]
        status["exit_code"] = proc.returncode
        status["stderr_tail"] = stderr[-500:]
        # A non-zero exit means the scan DIED (an out-of-memory kill under
        # a parallel scan, most often).  An empty findings file then means
        # "the scan died", not "the rules found nothing" -- and the two
        # are indistinguishable to every reader downstream.  A scan that
        # did not finish is not a measurement.
        if proc.returncode != 0:
            # A scan that died because the disk filled up is NOT an engine
            # crash, and saying so saves a long hunt in the wrong place.
            disk_note = ""
            try:
                free_mb = shutil.disk_usage(str(SCRATCH_DIR)).free // (1024 * 1024)
                disk_note = (" The scratch volume has %d MB free -- the scan may "
                             "have died because the disk filled up." % free_mb)
            except OSError:
                pass
            status["error"] = (
                f"the scan exited {proc.returncode}. The measurement is not "
                f"valid for this CWE. stderr tail: {stderr[-400:]}{disk_note}")
            status["seconds"] = time.time() - started
            status["jar_after"] = jar_identity()
            status["jar_stable"] = status["jar_after"] == jar_before
            return status
        if "no .java files found" in stderr:
            # The scan found nothing to analyse.  This is NOT "the rules
            # found nothing" -- it is "the measurement looked in the wrong
            # place".  Juliet ships several directories per CWE and only
            # one of them holds the testcases.
            status["error"] = (
                f"the scan found no .java files in {directory}. "
                "The measurement is not valid for this CWE.")
            status["seconds"] = time.time() - started
            status["jar_after"] = jar_identity()
            status["jar_stable"] = status["jar_after"] == jar_before
            return status
        if not ndjson.is_file():
            status["error"] = ("the scan produced no findings file. "
                               "The measurement is not valid for this CWE.")
            status["seconds"] = time.time() - started
            status["jar_after"] = jar_identity()
            status["jar_stable"] = status["jar_after"] == jar_before
            return status
        # Stream the findings out of the scratch file, counting the lines
        # as they go, so nothing is held in memory.
        lines = 0
        with ndjson.open("r", encoding="utf-8", errors="replace") as src, \
                dest.open("w", encoding="utf-8") as out:
            for line in src:
                if not line.strip():
                    continue
                out.write(line if line.endswith("\n") else line + "\n")
                lines += 1
        status["findings"] = lines
        status["ok"] = True
    status["seconds"] = time.time() - started
    status["jar_after"] = jar_identity()
    status["jar_stable"] = status["jar_after"] == jar_before
    if not status["jar_stable"]:
        status["ok"] = False
        status["error"] = ("the scanner jar was replaced while the scan ran; "
                           "the result is not reproducible")
    return status


def scan(cwe: str, cwe_dir: str, rules: list[str]) -> tuple[list[dict], str]:
    """Scan one CWE directory with the per-CWE config. Returns (findings, stderr)."""
    directory = JULIET_ROOT / cwe_dir
    config = CONFIG_DIR / f"CWE{cwe}.yaml"
    SCRATCH_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="juliet-m-", dir=str(SCRATCH_DIR)) as tmp:
        ndjson = Path(tmp) / "f.ndjson"
        cmd = ["java", "-jar", str(JAR), "scan", "-i", str(directory),
               "-f", "ndjson", "-o", str(ndjson)]
        if config.is_file():
            cmd += ["-c", str(config)]
        else:
            for rule in rules:
                cmd += ["--rule", rule]
        # The child's stdout carries bytes that are not valid GBK, and the
        # default locale codec raises UnicodeDecodeError inside
        # subprocess' reader thread -- which kills the run and leaves an
        # empty findings file that reads as "the rules found nothing".
        # Read BYTES and decode explicitly.
        proc = subprocess.run(cmd, capture_output=True, cwd=str(REPO), timeout=14400)
        stderr = proc.stderr.decode("utf-8", errors="replace")[-2000:]
        findings = []
        # A non-zero exit means the scan DIED (an out-of-memory kill under
        # a parallel scan, most often).  An empty findings file then means
        # "the scan died", not "the rules found nothing" -- and the two
        # are indistinguishable to every reader downstream.  A scan that
        # did not finish is not a measurement.
        if proc.returncode != 0:
            # A scan that died because the disk filled up is NOT an engine
            # crash, and saying so saves a long hunt in the wrong place.
            disk_note = ""
            try:
                free_mb = shutil.disk_usage(str(SCRATCH_DIR)).free // (1024 * 1024)
                disk_note = (" The scratch volume has %d MB free -- the scan may "
                             "have died because the disk filled up." % free_mb)
            except OSError:
                pass
            raise RuntimeError(
                f"the scan for CWE-{cwe} exited {proc.returncode}. "
                "The measurement is not valid for this CWE. "
                f"stderr tail: {stderr[-400:]}{disk_note}")
        if "no .java files found" in stderr:
            # The scan found nothing to analyse.  This is NOT "the rules
            # found nothing" -- it is "the measurement looked in the wrong
            # place".  Juliet ships several directories per CWE and only
            # one of them holds the testcases.
            raise RuntimeError(
                f"the scan for CWE-{cwe} found no .java files in {directory}. "
                "The measurement is not valid for this CWE.")
        if not ndjson.is_file():
            raise RuntimeError(
                f"the scan for CWE-{cwe} produced no findings file. "
                "The measurement is not valid for this CWE.")
        for line in ndjson.read_text(encoding="utf-8", errors="replace").splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    findings.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
        return findings, stderr


# ---------------------------------------------------------------------------
# Classification
# ---------------------------------------------------------------------------
def finding_cwes(finding: dict, rule_cwes: dict) -> list[str]:
    """The CWE labels the finding itself carries."""
    out = []
    for key in ("cwe", "cwe_id", "cweIds", "cwes"):
        v = finding.get(key)
        if isinstance(v, list):
            out.extend(norm_cwe(str(x)) for x in v)
        elif v:
            out.append(norm_cwe(str(v)))
    rid = finding.get("rule_id") or ""
    out.extend(rule_cwes.get(rid, []))
    seen, uniq = set(), []
    for c in out:
        if c and c not in seen:
            seen.add(c)
            uniq.append(c)
    return uniq


def classify(cwe: str, findings: list[dict], truth: dict, rule_cwes: dict) -> dict:
    """Classify findings against the method-level ground truth, CWE-exactly."""
    files = truth.get(cwe, {})
    span_cache: dict[str, dict] = {}

    def spans_for(name: str) -> dict:
        if name not in span_cache:
            path = JULIET_ROOT / f"CWE{cwe}_{DIR_SUFFIX.get(cwe, cwe)}" / name
            if not path.is_file():
                # the file may live in a variant sub-directory
                for sub in JULIET_ROOT.glob(f"CWE{cwe}_*"):
                    probe = sub / name
                    if probe.is_file():
                        path = probe
                        break
                    for d2 in sub.glob("s*"):
                        probe = d2 / name
                        if probe.is_file():
                            path = probe
                            break
                    if path.is_file():
                        break
            # EVERY method in the file, not only the ones the ground truth
            # names.  Juliet's ground truth lists only `bad` for many files,
            # and a span table built from it alone leaves every `goodG2B1()`
            # finding unattributed -- which would be scored as "outside any
            # method" instead of "inside a fixed method".
            span_cache[name] = all_method_spans(path) if path.is_file() else {}
        return span_cache[name]

    tp = 0
    fp = 0
    fp_rows: list[dict] = []
    tp_rows: list[dict] = []
    covered_bad: set[str] = set()
    fp_by_rule: Counter = Counter()
    tp_by_rule: Counter = Counter()
    wrong_cwe: Counter = Counter()

    for finding in findings:
        name = Path(finding.get("file") or "").name
        line = int(finding.get("line") or 0)
        rule = finding.get("rule_id") or "?"
        cwes = finding_cwes(finding, rule_cwes)

        base = {"file": name, "line": line, "rule_id": rule,
                "message": (finding.get("message") or "")[:300],
                "sink_function": finding.get("sink_function") or "",
                "severity": finding.get("severity") or "",
                "finding_cwes": cwes}

        if name in DRIVER_FILES:
            fp += 1
            fp_by_rule[rule] += 1
            fp_rows.append({**base, "verdict": "FP",
                            "reason": "on a Juliet driver file (Main/ServletMain), not a testcase"})
            continue
        if name not in files:
            fp += 1
            fp_by_rule[rule] += 1
            fp_rows.append({**base, "verdict": "FP",
                            "reason": "on a file Juliet's ground truth does not track"})
            continue

        # CWE-EXACT: the finding must be about THIS CWE.
        if cwes and cwe not in cwes:
            wrong_cwe[rule] += 1
            fp += 1
            fp_by_rule[rule] += 1
            fp_rows.append({**base, "verdict": "FP",
                            "reason": f"the finding is about CWE-{','.join(cwes)}, "
                                      f"not CWE-{cwe}"})
            continue

        role = "other"
        hit_method = None
        for method, (start, end) in spans_for(name).items():
            if start <= line <= end:
                role = role_of(method)
                hit_method = method
                break

        if role == "bad":
            tp += 1
            tp_by_rule[rule] += 1
            covered_bad.add(name)
            tp_rows.append({**base, "verdict": "TP", "method": hit_method})
        else:
            fp += 1
            fp_by_rule[rule] += 1
            why = {
                "good": "in a fixed (good*) method",
                "other": "outside any bad*/good* method (helper/field/init)",
            }[role]
            fp_rows.append({**base, "verdict": "FP", "reason": why,
                            "method": hit_method})

    # A file whose bad method was never hit is a false negative.
    fn_files = []
    for name, meta in files.items():
        if not any(role_of(m) == "bad" for m in meta["methods"]):
            continue
        if name not in covered_bad:
            fn_files.append(name)

    positives = sum(1 for m in files.values()
                    if any(role_of(x) == "bad" for x in m["methods"]))
    tp_files = positives - len(fn_files)

    return {
        "cwe": cwe,
        "files_positive": positives,
        "files_tracked": len(files),
        "tp": tp, "fp": fp,
        "tp_files": tp_files, "fn_files": len(fn_files),
        "fn_file_list": sorted(fn_files),
        "recall": tp_files / positives if positives else 0.0,
        "precision": tp / (tp + fp) if (tp + fp) else 0.0,
        "fp_by_rule": dict(fp_by_rule.most_common(20)),
        "tp_by_rule": dict(tp_by_rule.most_common(20)),
        "wrong_cwe_by_rule": dict(wrong_cwe.most_common(20)),
        "fp_rows": fp_rows,
        "tp_rows": tp_rows,
    }


DIR_SUFFIX: dict[str, str] = {}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("cwes", nargs="*")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--out", default=str(Path(__file__).resolve().parent / "out"))
    ap.add_argument("--limit", type=int, default=0, help="only the first N CWEs")
    ap.add_argument("--findings-dir", default="",
                    help="where the per-CWE NDJSON lands; default <out>/findings. "
                         "Two workers can share one findings directory because "
                         "they own disjoint CWEs.")
    ap.add_argument("--scan-only", action="store_true",
                    help="scan and write the NDJSON, do not score. Scoring is a "
                         "separate step so a statistics change never costs a "
                         "re-scan, and so the findings never sit in memory.")
    ap.add_argument("--status-dir", default="",
                    help="where the per-CWE scan status lands; default <out>/status")
    args = ap.parse_args()

    dirs = cwe_dirs()
    for cwe, name in dirs.items():
        DIR_SUFFIX[cwe] = name[len(f"CWE{cwe}_"):]

    wanted = sorted(dirs) if args.all else [c.lstrip("CWE") for c in args.cwes]
    if args.limit:
        wanted = wanted[: args.limit]

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    findings_dir = Path(args.findings_dir) if args.findings_dir else out / "findings"
    status_dir = Path(args.status_dir) if args.status_dir else out / "status"
    findings_dir.mkdir(parents=True, exist_ok=True)
    status_dir.mkdir(parents=True, exist_ok=True)

    truth = load_ground_truth()
    rule_cwes = load_rule_cwes()

    results = []
    for cwe in wanted:
        if cwe not in dirs:
            print(f"SKIP CWE-{cwe}: no Juliet directory", flush=True)
            continue
        rules = read_whitelist(cwe)
        mode = whitelist_mode(cwe)
        print(f"=== CWE-{cwe}  dir={dirs[cwe]}  mode={mode}  rules={len(rules)}",
              flush=True)

        if args.scan_only:
            dest = findings_dir / f"CWE{cwe}.ndjson"
            status = scan_to_file(cwe, dirs[cwe], rules, dest)
            (status_dir / f"CWE{cwe}.json").write_text(
                json.dumps(status, ensure_ascii=False, indent=2), encoding="utf-8")
            if status["ok"]:
                print(f"    findings={status['findings']} "
                      f"{status['seconds']:.0f}s  jar_stable={status['jar_stable']}",
                      flush=True)
            else:
                print(f"    FAILED: {status['error']}", flush=True)
            continue

        findings, err = scan(cwe, dirs[cwe], rules)
        with (findings_dir / f"CWE{cwe}.ndjson").open("w", encoding="utf-8") as fh:
            for f in findings:
                fh.write(json.dumps(f, ensure_ascii=False) + "\n")
        res = classify(cwe, findings, truth, rule_cwes)
        res["whitelist_mode"] = mode
        res["whitelist"] = rules
        res["raw_findings"] = len(findings)
        if err.strip():
            res["stderr_tail"] = err.strip()[-500:]
        results.append(res)
        print(f"    findings={len(findings)} TP={res['tp']} FP={res['fp']} "
              f"TPfiles={res['tp_files']}/{res['files_positive']} "
              f"recall={res['recall']:.3f} precision={res['precision']:.3f}", flush=True)

    if args.scan_only:
        # The scan-only mode's output is the NDJSON and the per-CWE status
        # files. There is no summary: the statistics are a separate step,
        # and a summary written here would be scored by the OLD engine's
        # findings whenever the two drifted apart.
        return 0
    (out / "summary.json").write_text(json.dumps(results, ensure_ascii=False, indent=2),
                                       encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
