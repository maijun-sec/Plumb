#!/usr/bin/env python3
"""Final fix: rename loop var to avoid shadowing visit parameter."""
import os, re

DIR = 'src/main/java/org/plumb/style/style'

VISIT_RE = re.compile(r'public\s+void\s+visit\(([^,]+?),\s*(\w+)\s*\)')
# Look for "for (<Type> var :" anywhere in the file
LOOP_RE = re.compile(r'for\s*\([^)]*?(\b\w+)\s*:')

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
    visit_type = m.group(1).strip().split('.')[-1]  # simple name
    visit_param = m.group(2)

    # Find for-loop var
    lm = LOOP_RE.search(text)
    if not lm:
        return False
    loop_var = lm.group(1)

    if visit_param != loop_var:
        return False

    new_var = loop_var + '0'
    # Rename
    text = re.sub(r'\b' + re.escape(loop_var) + r'\b', new_var, text)
    if text != orig:
        with open(path, 'w', encoding='utf-8') as f:
            f.write(text)
        print(f"{os.path.basename(path)}: {loop_var} -> {new_var}")
        return True
    return False


fixed = 0
for entry in sorted(os.listdir(DIR)):
    if not entry.endswith('Check.java'):
        continue
    if fix(os.path.join(DIR, entry)):
        fixed += 1

print(f'\nFixed: {fixed}')
print('Run: mvn -o test')