#!/usr/bin/env python3
"""Final revert: find files with broken visit signature and revert to visit(CompilationUnit)."""
import os
import re

DIR = 'src/main/java/org/plumb/style/style'

def read_text(path):
    try:
        with open(path, 'r', encoding='utf-8') as f:
            return f.read(), 'utf-8'
    except UnicodeDecodeError:
        with open(path, 'r', encoding='latin-1') as f:
            return f.read(), 'latin-1'

# Find files with the broken signature pattern
fixed = 0
for entry in sorted(os.listdir(DIR)):
    if not entry.endswith('Check.java'):
        continue
    path = os.path.join(DIR, entry)
    text, enc = read_text(path)
    orig = text

    # Match: visit(<Type>, <param>) {
    m = re.search(r'public\s+void\s+visit\(([^,]+?),\s*([a-zA-Z][a-zA-Z0-9]*)\)\s*\{', text)
    if not m:
        continue

    type_name = m.group(1).strip()
    bad_param = m.group(2)

    # Skip if looks correct (param name "n")
    if bad_param == 'n':
        continue

    # Find for-loop variable
    lm = re.search(r'for\s*\(([^)]*?)\s+(' + re.escape(bad_param) + r'|' + re.escape(bad_param) + r'0)\s*:\s*n\.findAll\(',
                  text)
    if not lm:
        continue
    loop_var = lm.group(2)

    # Compute new loop var (strip trailing 0 if present)
    new_loop_var = loop_var[:-1] if loop_var.endswith('0') else loop_var

    # Revert visit signature
    new_text = re.sub(
        r'public\s+void\s+visit\([^,]+?,\s*' + re.escape(bad_param) + r'\)\s*\{',
        'public void visit(CompilationUnit n, StyleContext ctx) {',
        text,
        count=1,
    )
    # Revert loop var name
    if new_loop_var != loop_var:
        new_text = re.sub(r'\b' + re.escape(loop_var) + r'\b', new_loop_var, new_text)

    if new_text != orig:
        with open(path, 'w', encoding=enc) as f:
            f.write(new_text)
        fixed += 1
        print(f"{entry}: visit({type_name}, n), loop var {loop_var} -> {new_loop_var}")

print(f'\nTotal fixed: {fixed}')