#!/usr/bin/env python3
"""Revert ALL files with broken visit(<Type>, ctx) signature back to visit(CompilationUnit n, StyleContext ctx)."""
import os, re

DIR = 'src/main/java/org/plumb/style/style'
RE = re.compile(r'public\s+void\s+visit\(([^,]+?),\s*(\w+)\)\s*\{')

def fix(path):
    try:
        with open(path, 'r', encoding='utf-8') as f:
            text = f.read()
    except UnicodeDecodeError:
        with open(path, 'r', encoding='latin-1') as f:
            text = f.read()
    orig = text

    m = RE.search(text)
    if not m:
        return False
    type_name = m.group(1).strip()
    bad_param = m.group(2)
    # Real visit params were "n" or "ctxName" — anything else is a migrated one-letter name
    if bad_param in ('n', 'ctx', 'cu', 'unit'):
        return False  # already correct

    # Look for for-loop var with the same bad name (shadowed)
    lm = re.search(r'for\s*\([^)]*?(\b' + re.escape(bad_param) + r')\s*:\s*n\.findAll\(', text)
    if not lm:
        return False
    loop_var = lm.group(1)

    # Revert the visit signature back to the canonical form
    text = re.sub(
        rf'public\s+void\s+visit\([^,]+?,\s*\{re.escape(bad_param)}\)\s*\{{',
        'public void visit(CompilationUnit n, StyleContext ctx) {',
        text,
        count=1,
    )
    # Revert the loop var back to its original name
    if loop_var.endswith('0') or loop_var != bad_param:
        # Rename back, e.g. e0 -> e, ae0 -> ae
        orig_loop_var = loop_var.rstrip('0') if loop_var.endswith('0') else loop_var
        text = re.sub(r'\b' + re.escape(loop_var) + r'\b', orig_loop_var, text)

    if text != orig:
        with open(path, 'w', encoding='utf-8') as f:
            f.write(text)
        print(f"Reverted {os.path.basename(path)} (was {bad_param} / loop_var={loop_var})")
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