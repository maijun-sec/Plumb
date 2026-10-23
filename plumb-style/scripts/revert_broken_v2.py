#!/usr/bin/env python3
"""Find files with broken visit(<Type>, <shortVar>) and revert to visit(CompilationUnit)."""
import os, re

DIR = 'src/main/java/org/plumb/style/style'
# Pattern: visit(<type>, <shortVar>) where shortVar is a single short word (likely a loop var)
VISIT_RE = re.compile(r'public\s+void\s+visit\(([^,]+?),\s*(\w{1,3})\)\s*\{')
LOOP_RE = re.compile(r'for\s*\(([^)]*?)\s+(\w+)\s*:\s*n\.findAll\(')

def fix(path):
    try:
        with open(path, 'r', encoding='utf-8') as f:
            text = f.read()
    except UnicodeDecodeError:
        with open(path, 'r', encoding='latin-1') as f:
            text = f.read()
    orig = text

    m = VISIT_RE.search(text)
    if not m:
        return False
    bad_param = m.group(2)
    if bad_param not in ('e', 'm', 'v', 'a', 'b', 'c', 'd', 'f', 'l', 'n', 'o', 'p', 's', 'x', 'ae', 'be', 'bo', 'ne', 'no', 'oc', 'sw', 'ea', 'ed'):
        return False  # not a typical 1-2 letter loop var name
    if bad_param == 'n': return False  # already correct

    # Verify the loop var exists with the same name
    lm = LOOP_RE.search(text)
    if not lm:
        return False
    loop_var = lm.group(2)
    if loop_var != bad_param and loop_var != bad_param + '0':
        return False

    # Apply revert
    new_content = re.sub(
        r'public\s+void\s+visit\([^,]+?,\s*' + re.escape(bad_param) + r'\)\s*\{',
        'public void visit(CompilationUnit n, StyleContext ctx) {',
        text,
        count=1,
    )
    # Revert the loop var name if it has the appended 0
    if loop_var.endswith('0'):
        orig_name = loop_var[:-1]
        new_content = re.sub(r'\b' + re.escape(loop_var) + r'\b', orig_name, new_content)

    if new_content != text:
        with open(path, 'w', encoding='utf-8') as f:
            f.write(new_content)
        print(f"Reverted {os.path.basename(path)} (was visit({m.group(1).strip()}, {bad_param}))")
        return True
    return False


fixed = 0
for entry in sorted(os.listdir(DIR)):
    if not entry.endswith('Check.java'):
        continue
    if fix(os.path.join(DIR, entry)):
        fixed += 1
print(f'\nTotal reverted: {fixed}')
print('Run: mvn -o test')