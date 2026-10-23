"""
Phase 3: 自动生成 rule_id 迁移表 (old → new)

Mapping rules (based on Phase 2 classification):
  - pl-XXXX-*          → plumb.ast.<name>  (PMD style is style/ast)
  - bu-XXXX-*          → plumb.<domain>.<name>  (SpotBugs, domain via context)
  - cov-XXXX-*         → plumb.<domain>.<name>  (Coverity, domain via context)
  - UPPER_SNAKE (PMD)  → plumb.<domain>.<name>  (matches PMD category)
  - plumb.X.Y          → plumb.<domain>.<name>  (collapse 3-level to domain=second)
  - plumb.X.Y.Z        → plumb.<domain>.<name>  (collapse 4-level)
  - dfa.cweXXX-*       → plumb.<domain>.<name>  (DFA loose-style)
  - loose.X.Y          → plumb.boundary.<name>  (loose-class is boundary)
  - PascalCase         → plumb.<domain>.<name>  (ClassName; needs context for domain)
  - lowercase-kebab    → plumb.<domain>.<name>
  - OTHER (Cwe674Recursion etc.) → plumb.<domain>.<name>

The mapping is intentionally **conservative**: when domain is ambiguous,
default to `plumb.ast.` and require manual review.

Outputs:
  - D:\work\workspace\idea\engine\Plumb\juliet-coverage\data\rule_id_migration.csv
  - D:\work\workspace\idea\engine\Plumb\juliet-coverage\data\rule_id_migration_review_needed.csv
"""
import csv
import re
from pathlib import Path

INVENTORY_CSV = Path(r"D:\work\workspace\idea\engine\Plumb\juliet-coverage\data\rule_ids_inventory.csv")
OUT_MIGRATION = Path(r"D:\work\workspace\idea\engine\Plumb\juliet-coverage\data\rule_id_migration.csv")
OUT_REVIEW = Path(r"D:\work\workspace\idea\engine\Plumb\juliet-coverage\data\rule_id_migration_review_needed.csv")

# Domain heuristics per CWE (rough mapping for CWE-tagged rules)
CWE_TO_DOMAIN = {
    # taint
    "CWE-20": "taint", "CWE-22": "taint", "CWE-77": "taint", "CWE-78": "taint",
    "CWE-79": "taint", "CWE-89": "taint", "CWE-90": "taint", "CWE-91": "taint",
    "CWE-93": "taint", "CWE-94": "taint", "CWE-95": "taint", "CWE-96": "taint",
    "CWE-117": "taint", "CWE-1333": "taint", "CWE-134": "taint", "CWE-502": "taint",
    "CWE-601": "taint", "CWE-643": "taint", "CWE-918": "taint", "CWE-943": "taint",
    "CWE-470": "taint",
    # boundary
    "CWE-189": "boundary", "CWE-190": "boundary", "CWE-191": "boundary",
    "CWE-193": "boundary", "CWE-197": "boundary", "CWE-369": "boundary",
    "CWE-129": "boundary", "CWE-131": "boundary", "CWE-805": "boundary",
    # concurrency
    "CWE-362": "concurrency", "CWE-764": "concurrency", "CWE-765": "concurrency",
    "CWE-820": "concurrency", "CWE-821": "concurrency", "CWE-822": "concurrency",
    "CWE-833": "concurrency", "CWE-835": "concurrency",
    # resource
    "CWE-404": "resource", "CWE-459": "resource", "CWE-665": "resource",
    "CWE-772": "resource", "CWE-775": "resource",
    # crypto
    "CWE-256": "crypto", "CWE-259": "crypto", "CWE-261": "crypto", "CWE-311": "crypto",
    "CWE-312": "crypto", "CWE-319": "crypto", "CWE-320": "crypto", "CWE-321": "crypto",
    "CWE-322": "crypto", "CWE-323": "crypto", "CWE-324": "crypto", "CWE-325": "crypto",
    "CWE-326": "crypto", "CWE-327": "crypto", "CWE-328": "crypto", "CWE-329": "crypto",
    "CWE-330": "crypto", "CWE-331": "crypto", "CWE-332": "crypto", "CWE-333": "crypto",
    "CWE-336": "crypto", "CWE-337": "crypto", "CWE-338": "crypto", "CWE-340": "crypto",
    "CWE-352": "crypto", "CWE-353": "crypto", "CWE-916": "crypto", "CWE-1204": "crypto",
    "CWE-1240": "crypto", "CWE-1241": "crypto", "CWE-1275": "crypto", "CWE-1335": "crypto",
    # info
    "CWE-200": "info", "CWE-201": "info", "CWE-209": "info", "CWE-210": "info",
    "CWE-211": "info", "CWE-212": "info", "CWE-213": "info", "CWE-214": "info",
    "CWE-215": "info", "CWE-226": "info", "CWE-244": "info", "CWE-359": "info",
    "CWE-374": "info", "CWE-375": "info", "CWE-532": "info", "CWE-533": "info",
    "CWE-534": "info", "CWE-535": "info", "CWE-536": "info", "CWE-537": "info",
    "CWE-538": "info", "CWE-539": "info", "CWE-540": "info", "CWE-541": "info",
    "CWE-542": "info", "CWE-543": "info", "CWE-544": "info", "CWE-545": "info",
    "CWE-546": "info", "CWE-547": "info", "CWE-548": "info", "CWE-549": "info",
    "CWE-552": "info", "CWE-555": "info", "CWE-598": "info", "CWE-615": "info",
    "CWE-749": "info", "CWE-756": "info", "CWE-763": "info", "CWE-921": "info",
    "CWE-922": "info", "CWE-1278": "info", "CWE-1295": "info",
    # ast
    "CWE-252": "ast", "CWE-253": "ast", "CWE-440": "ast", "CWE-471": "ast",
    "CWE-476": "ast", "CWE-477": "ast", "CWE-478": "ast", "CWE-479": "ast",
    "CWE-480": "ast", "CWE-481": "ast", "CWE-482": "ast", "CWE-483": "ast",
    "CWE-484": "ast", "CWE-486": "ast", "CWE-487": "ast", "CWE-488": "ast",
    "CWE-489": "ast", "CWE-491": "ast", "CWE-493": "ast", "CWE-495": "ast",
    "CWE-496": "ast", "CWE-497": "ast", "CWE-498": "ast", "CWE-499": "ast",
    "CWE-500": "ast", "CWE-501": "ast", "CWE-506": "ast", "CWE-510": "ast",
    "CWE-561": "ast", "CWE-563": "ast", "CWE-570": "ast", "CWE-571": "ast",
    "CWE-572": "ast", "CWE-579": "ast", "CWE-580": "ast", "CWE-581": "ast",
    "CWE-582": "ast", "CWE-583": "ast", "CWE-584": "ast", "CWE-585": "ast",
    "CWE-586": "ast", "CWE-587": "ast", "CWE-588": "ast", "CWE-589": "ast",
    "CWE-590": "ast", "CWE-591": "ast", "CWE-592": "ast", "CWE-593": "ast",
    "CWE-594": "ast", "CWE-595": "ast", "CWE-596": "ast", "CWE-597": "ast",
    "CWE-599": "ast", "CWE-600": "ast", "CWE-601": "taint",  # conflict
    "CWE-605": "ast", "CWE-606": "ast", "CWE-607": "ast", "CWE-608": "ast",
    "CWE-609": "ast", "CWE-610": "ast", "CWE-611": "ast", "CWE-612": "ast",
    "CWE-613": "ast", "CWE-614": "ast", "CWE-617": "ast", "CWE-618": "ast",
    "CWE-619": "ast", "CWE-620": "ast", "CWE-621": "ast", "CWE-622": "ast",
    "CWE-623": "ast", "CWE-624": "ast", "CWE-625": "ast", "CWE-626": "ast",
    "CWE-627": "ast", "CWE-628": "ast", "CWE-636": "ast", "CWE-637": "ast",
    "CWE-638": "ast", "CWE-640": "ast", "CWE-641": "ast", "CWE-642": "ast",
    "CWE-644": "ast", "CWE-646": "ast", "CWE-647": "ast", "CWE-648": "ast",
    "CWE-649": "ast", "CWE-650": "ast", "CWE-651": "ast", "CWE-652": "ast",
    "CWE-653": "ast", "CWE-654": "ast", "CWE-655": "ast", "CWE-656": "ast",
    "CWE-657": "ast", "CWE-658": "ast", "CWE-659": "ast", "CWE-660": "ast",
    "CWE-661": "ast", "CWE-662": "ast", "CWE-663": "ast", "CWE-664": "ast",
    "CWE-666": "ast", "CWE-667": "ast", "CWE-668": "ast", "CWE-669": "ast",
    "CWE-670": "ast", "CWE-671": "ast", "CWE-672": "ast", "CWE-673": "ast",
    "CWE-674": "ast", "CWE-675": "ast", "CWE-676": "ast", "CWE-677": "ast",
    "CWE-678": "ast", "CWE-679": "ast", "CWE-680": "ast", "CWE-681": "ast",
    "CWE-682": "ast", "CWE-683": "ast", "CWE-684": "ast", "CWE-685": "ast",
    "CWE-686": "ast", "CWE-687": "ast", "CWE-688": "ast", "CWE-689": "ast",
    "CWE-690": "ast", "CWE-691": "ast", "CWE-692": "ast", "CWE-693": "ast",
    "CWE-694": "ast", "CWE-695": "ast", "CWE-696": "ast", "CWE-697": "ast",
    "CWE-698": "ast", "CWE-699": "ast",
    # style
    "CWE-1006": "style", "CWE-1059": "style", "CWE-1061": "style",
    # naming
    "CWE-1006": "naming",  # conflict; naming wins for naming-related
    "CWE-1059": "naming",  # conflict
    "CWE-1093": "naming",
}

# Domain detection by rule id prefix / pattern
def detect_domain(rid: str) -> tuple[str, str]:
    """Returns (domain, reason). reason is human-readable explanation."""
    # 1. dfa.cweXXX-* (DFA loose)
    m = re.match(r"^dfa\.cwe(\d+)", rid)
    if m:
        cwe = f"CWE-{m.group(1)}"
        return CWE_TO_DOMAIN.get(cwe, "ast"), f"dfa.cwe prefix + CWE-{m.group(1)}"
    if rid.startswith("dfa."):
        return "ast", "dfa.* generic fallback"

    # 2. plumb.X.Y or plumb.X.Y.Z
    if rid.startswith("plumb."):
        parts = rid.split(".")
        if len(parts) == 3:
            # plumb.X.Y — collapse X into the new domain
            old_domain = parts[1]
            # Map old sub-domains to new
            mapping = {
                "taint": "taint", "dfa": "dfa", "boundary": "boundary",
                "ast": "ast", "style": "style", "numeric": "boundary",
                "pointer": "dfa", "sensitive": "info", "ai": "ast",
                "unchecked": "ast", "unused": "ast", "insecure": "ast",
            }
            return mapping.get(old_domain, "ast"), f"plumb.{old_domain}.* → mapped to plumb.{mapping.get(old_domain, 'ast')}.*"
        elif len(parts) == 4:
            # plumb.X.Y.Z — Y becomes the new domain hint
            old_domain = parts[1]
            old_sub = parts[2]
            mapping = {
                "taint": "taint", "dfa": "dfa", "boundary": "boundary",
                "ast": "ast", "style": "style", "numeric": "boundary",
            }
            return mapping.get(old_sub, old_sub if old_sub in ("taint", "dfa", "boundary", "ast", "style", "concurrency", "resource", "crypto", "naming", "info") else "ast"), f"plumb.{old_domain}.{old_sub}.Z"

    # 3. loose.numeric.* → boundary
    if rid.startswith("loose."):
        return "boundary", "loose.* prefix"

    # 4. pl-XXXX-* (PMD-style)
    if rid.startswith("pl-"):
        # try CWE in remainder
        m = re.search(r"cwe-?(\d+)", rid, re.IGNORECASE)
        if m:
            cwe = f"CWE-{m.group(1)}"
            return CWE_TO_DOMAIN.get(cwe, "ast"), f"pl- CWE-{m.group(1)} detected"
        return "ast", "pl- default"

    # 5. bu-XXXX-* (SpotBugs-style)
    if rid.startswith("bu-"):
        # try CWE
        m = re.search(r"cwe-?(\d+)", rid, re.IGNORECASE)
        if m:
            cwe = f"CWE-{m.group(1)}"
            return CWE_TO_DOMAIN.get(cwe, "ast"), f"bu- CWE-{m.group(1)} detected"
        # Heuristics on the slug
        slug = rid[6:]
        if any(k in slug for k in ("taint", "sql", "cmd", "ldap", "xss", "path", "deserial", "reflect", "url", "xpath", "nosql", "ognl", "regex", "redirect")):
            return "taint", "bu- slug taint"
        if any(k in slug for k in ("style", "naming", "comment", "constant", "method", "class")):
            return "style", "bu- slug style"
        if "empty" in slug or "unreachable" in slug or "assignment" in slug:
            return "ast", "bu- slug ast"
        return "ast", "bu- default"

    # 6. cov-XXXX-* (Coverity)
    if rid.startswith("cov-"):
        return "ast", "cov- default (need manual review)"

    # 7. UPPER_SNAKE (PMD rule name)
    if re.match(r"^[A-Z][A-Z0-9_]+$", rid):
        # PMD naming convention
        # Pure style/naming → style/naming; semantic → ast
        if any(k in rid for k in ("NAMING", "CASE", "STYLE")):
            return "naming" if "NAMING" in rid or "CASE" in rid else "style", "UPPER_SNAKE naming/style"
        return "ast", "UPPER_SNAKE default"

    # 8. PascalCase (ClassName)
    if re.match(r"^[A-Z][a-zA-Z]+$", rid):
        return "ast", "PascalCase default"

    # 9. lowercase-kebab (misc)
    if re.match(r"^[a-z][a-z0-9-]+$", rid):
        return "ast", "lowercase-kebab default"

    # 10. CweXXX* (malformed)
    if rid.startswith("Cwe"):
        return "ast", "CweXXX malformed"

    return "ast", "fallback"


def to_new_id(rid: str, domain: str) -> str:
    """Convert old rid to new `plumb.<domain>.<name>` form."""
    # If it's already plumb.X.Y, just remap the X
    if rid.startswith("plumb."):
        parts = rid.split(".")
        if len(parts) == 3:
            old_name = parts[2]
        elif len(parts) == 4:
            old_name = parts[3]
        else:
            old_name = parts[-1]
        return f"plumb.{domain}.{old_name}"

    # dfa.cweXXX-* → cwe becomes name; strip prefix
    if rid.startswith("dfa."):
        return f"plumb.{domain}.{rid[4:]}"

    # loose.X.Y → plumb.boundary.Y
    if rid.startswith("loose."):
        parts = rid.split(".")
        return f"plumb.{domain}.{parts[-1]}"

    # pl-NNNN-name → plumb.ast.name
    if rid.startswith("pl-"):
        m = re.match(r"^pl-(\d{4})-(.+)$", rid)
        if m:
            return f"plumb.{domain}.{m.group(2)}"
        return f"plumb.{domain}.{rid[3:]}"

    # bu-NNNN-name
    if rid.startswith("bu-"):
        m = re.match(r"^bu-(\d{4})-(.+)$", rid)
        if m:
            return f"plumb.{domain}.{m.group(2)}"
        return f"plumb.{domain}.{rid[3:]}"

    # cov-NNNN-name
    if rid.startswith("cov-"):
        m = re.match(r"^cov-(\d{4})?-(.+)$", rid)
        if m:
            return f"plumb.{domain}.{m.group(2)}"
        return f"plumb.{domain}.{rid[4:]}"

    # UPPER_SNAKE → lowercase-kebab
    if re.match(r"^[A-Z][A-Z0-9_]+$", rid):
        kebab = rid.lower().replace("_", "-")
        return f"plumb.{domain}.{kebab}"

    # PascalCase → lowercase-kebab
    if re.match(r"^[A-Z][a-zA-Z]+$", rid):
        # AvoidClassNameCheck → avoid-class-name-check
        kebab = re.sub(r"([A-Z])", r"-\1", rid).lower().lstrip("-")
        # Cwe674Recursion → cwe674-recursion
        kebab = re.sub(r"^cwe(\d+)-", r"cwe\1-", kebab)
        return f"plumb.{domain}.{kebab}"

    # lowercase-kebab → just add domain
    if re.match(r"^[a-z][a-z0-9-]+$", rid):
        return f"plumb.{domain}.{rid}"

    return f"plumb.{domain}.{rid.lower().replace('_', '-')}"


# Read inventory
inventory = list(csv.DictReader(INVENTORY_CSV.open(encoding="utf-8")))

migrations: list[dict] = []
review_needed: list[dict] = []

for row in inventory:
    old_id = row["rule_id"]
    sources = row["sources"]

    # Skip junk
    if "+" in old_id or " " in old_id or old_id == "x":
        review_needed.append({
            "old_id": old_id, "reason": "junk/typo",
            "suggested": "REVIEW: skip or remove"
        })
        continue

    domain, reason = detect_domain(old_id)
    new_id = to_new_id(old_id, domain)

    # Validate
    if not re.match(r"^plumb\.(taint|dfa|boundary|ast|style|concurrency|resource|crypto|naming|info)\.[a-z0-9-]+$", new_id):
        review_needed.append({
            "old_id": old_id, "reason": f"generated '{new_id}' does not match schema",
            "suggested": "MANUAL EDIT REQUIRED"
        })
        continue

    # Check for potential new-id collisions
    migrations.append({
        "old_id": old_id,
        "new_id": new_id,
        "domain": domain,
        "detect_reason": reason,
        "source_count": row["source_count"],
        "in_yaml": "yaml" in sources,
        "in_finding": "finding" in sources,
        "in_registry": "RuleRegistry" in sources,
        "in_dfa_hardcoded": any("hardcoded" in s or "violation" in s or "name:" in s or "taint-rule" in s for s in [sources]),
        "in_ast": "ast-" in sources,
    })

# Detect collisions in new_ids
from collections import Counter
new_id_counts = Counter(m["new_id"] for m in migrations)
collisions = {nid: c for nid, c in new_id_counts.items() if c > 1}

# Write migration
with OUT_MIGRATION.open("w", encoding="utf-8", newline="") as fh:
    wr = csv.writer(fh)
    wr.writerow(["old_id", "new_id", "domain", "detect_reason", "source_count",
                 "in_yaml", "in_finding", "in_registry", "in_dfa_hardcoded", "in_ast",
                 "collision"])
    for m in migrations:
        wr.writerow([
            m["old_id"], m["new_id"], m["domain"], m["detect_reason"], m["source_count"],
            m["in_yaml"], m["in_finding"], m["in_registry"], m["in_dfa_hardcoded"], m["in_ast"],
            "YES" if m["new_id"] in collisions else ""
        ])

# Write review needed
with OUT_REVIEW.open("w", encoding="utf-8", newline="") as fh:
    wr = csv.writer(fh)
    wr.writerow(["old_id", "reason", "suggested"])
    for r in review_needed:
        wr.writerow([r["old_id"], r["reason"], r["suggested"]])

print(f"Migration entries: {len(migrations)}")
print(f"Review needed: {len(review_needed)}")
print(f"Collisions: {len(collisions)}")
for nid, c in list(collisions.items())[:20]:
    print(f"  {c}x  {nid}")
print(f"\nWritten to {OUT_MIGRATION}")
print(f"Written to {OUT_REVIEW}")

# Summary by domain
from collections import defaultdict
by_domain = defaultdict(int)
for m in migrations:
    by_domain[m["domain"]] += 1
print(f"\nDistribution by domain:")
for d, c in sorted(by_domain.items(), key=lambda x: -x[1]):
    print(f"  {d:<12} {c}")