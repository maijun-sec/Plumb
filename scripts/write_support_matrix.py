"""Rewrite the category support matrix from the measured RESULT.json files.

Every number in SUPPORT-STATUS.md and SUPPORT-MATRIX.md is read from RESULT.json rather than
typed by hand, so the reports cannot drift from the measurements. The prose verdicts are kept as
explicit per-category text because the judgment is not derivable from counts.

Two things this script used to get wrong, and how they are fixed:

  * The category list was HARDCODED. Adding a category meant editing this file, and a category
    that was measured but not listed here produced no row in the matrix at all -- it was
    invisible, which is exactly how a capability gap survives a green report. The categories are
    now DISCOVERED from the directories under `testcase/`, so a measured category cannot be
    left out.

  * The verdict prose lived in a dict HERE, while the per-category `SUPPORT-STATUS.md` lived in
    the category directory. Two homes for one piece of prose means they drift, and the script
    then OVERWRITES the hand-written file with its own copy. The prose now lives with its
    category, in `<category>/VERDICT.md`, and this script only renders the measured parts.

The known limitations are a third source of truth: they are prose, but they describe what the
engine cannot decide, so they also live with the category, in `<category>/LIMITATIONS.md`.
"""

import json
import pathlib
from collections import Counter

REPO = pathlib.Path("testcase")

# The prose for a category is read from files next to its measurements, so a new category needs
# no edit here. A category without a VERDICT.md still gets a row in the matrix -- the numbers are
# the measurement -- but its verdict section says so rather than silently disappearing.
VERDICT_FILE = "VERDICT.md"
LIMITATIONS_FILE = "LIMITATIONS.md"


def discover_categories():
    """The measured categories, in directory order.

    A directory is a category when it holds a RESULT.json: that file is what the harness
    writes, so its presence is the evidence that the category was measured. The `config`
    directory and `__pycache__` are not categories and have no RESULT.json, so they are
    excluded by the same test rather than by a name list.
    """
    categories = []
    for path in sorted(REPO.iterdir()):
        if not path.is_dir():
            continue
        if not (path / "RESULT.json").is_file():
            continue
        categories.append(path.name)
    return categories


def read_text(path):
    """The file's text, or None when it does not exist."""
    if not path.is_file():
        return None
    return path.read_text(encoding="utf-8").strip()


def verdict_of(category):
    """The category's verdict prose, or a note that it has none."""
    text = read_text(REPO / category / VERDICT_FILE)
    if text:
        return text
    return (
        "*(No verdict recorded yet: this category has a measurement but no "
        f"`{VERDICT_FILE}`.)*"
    )


def limitations_of(category):
    """The category's known-limitations prose, or None."""
    return read_text(REPO / category / LIMITATIONS_FILE)


rows = []
detail = []
categories = discover_categories()

if not categories:
    raise SystemExit("no measured categories found under %s" % REPO)

# Read the scan mode back out of each RESULT.json. It used to be hardcoded per category, which
# meant every category's SUPPORT-STATUS.md claimed "directory" while the harness actually ran
# per-file. A status document that misreports how it was produced cannot be used to judge
# whether a change moved the number.
SCAN_MODE = {
    name: json.loads((REPO / name / "RESULT.json").read_text(encoding="utf-8")).get(
        "scan_mode", "directory"
    )
    for name in categories
}

for name in categories:
    result = json.loads((REPO / name / "RESULT.json").read_text(encoding="utf-8"))
    s = result["summary"]
    statuses = Counter(c["status"] for c in result["cases"])
    extra = []
    if statuses.get("unsupported"):
        extra.append(f"{statuses['unsupported']} unsupported")
    if statuses.get("unscorable"):
        extra.append(f"{statuses['unscorable']} unscorable")
    if statuses.get("known-limitation"):
        # A case where the engine is sound and the mitigation modelled is simply not statically
        # decidable. Reported rather than hidden, because dropping it would make the tallies look
        # better than the coverage actually is.
        extra.append(f"{statuses['known-limitation']} known-limitation")
    suffix = f" ({', '.join(extra)})" if extra else ""
    rows.append(
        f"| {name} | TP {s['tp_supported']}/{s['tp_total']} | "
        f"TN {s['tn_supported']}/{s['tn_total']}{suffix} |"
    )
    # Known-limitation cases are listed here as well, not only counted in the summary. A case the
    # engine cannot score stays invisible in "Outstanding" otherwise, which is exactly how a
    # silent capability gap survives a green matrix.
    misses = [c["id"] for c in result["cases"]
              if c["status"] in ("false-negative", "false-positive")]
    limits = [c["id"] for c in result["cases"]
              if c["status"] == "known-limitation"]
    detail.append((name, misses, limits))

header = (
    "| Category | True positives | True negatives |\n"
    "|---|---|---|\n"
)
table = header + "\n".join(rows) + "\n"

# Verdicts and outstanding cases belong in the matrix itself. Without them the table is just
# counts, and the reader has to open five other files to learn which gaps are real. Only true
# misses are listed; `unsupported` / `unscorable` counts already appear in the table's TN cell.
verdict_sections = []
for name, misses, limits in detail:
    miss_text = (
        "\n".join(f"- `{m}`" for m in misses) if misses else "- none"
    )
    limit_text = (
        "\n".join(f"- `{m}`" for m in limits) if limits else "- none"
    )
    verdict_sections.append(
        f"### {name}\n\n{verdict_of(name)}\n\nOutstanding: \n{miss_text}\n\n"
        f"Known limitations: \n{limit_text}\n"
    )

(REPO / "SUPPORT-MATRIX.md").write_text(
    "# Plumb analysis support matrix (measured)\n\n"
    "Generated by `scripts/write_support_matrix.py` from each category's `RESULT.json`.\n"
    "Re-run `python testcase/run_category.py testcase/<category>` to refresh.\n\n"
    + table
    + "\n## Verdicts\n\n"
    + "\n".join(verdict_sections),
    encoding="utf-8",
)

for name, misses, limits in detail:
    miss_text = ", ".join(f"`{m}`" for m in misses) if misses else "none"
    limit_text = ", ".join(f"`{m}`" for m in limits) if limits else "none"
    row = next(r for r in rows if r.startswith(f"| {name} "))
    cells = [c.strip() for c in row.strip().strip("|").split("|")]
    scan_mode = SCAN_MODE.get(name, "directory")
    prose = limitations_of(name)
    limitations_block = prose if prose else limit_text
    (REPO / name / "SUPPORT-STATUS.md").write_text(
        f"# Support status - {name}\n\n"
        f"{verdict_of(name)}\n\n"
        f"## Measured\n\n"
        f"- True positives: **{cells[1]}**\n"
        f"- True negatives: **{cells[2]}**\n"
        f"- Scan mode: {scan_mode}, callgraph strategy: `global`.\n\n"
        f"## Outstanding cases\n\n{miss_text}\n\n"
        f"## Known limitations\n\n{limitations_block}\n\n"
        f"Full per-case detail: `RESULT.json`. Expectations: `EXPECTATIONS.json`.\n",
        encoding="utf-8",
    )

print(table)
for name, misses, limits in detail:
    print(f"{name}: outstanding -> {', '.join(misses) if misses else 'none'}"
          f" | known-limitations -> {', '.join(limits) if limits else 'none'}")
