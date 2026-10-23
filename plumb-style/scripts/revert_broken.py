#!/usr/bin/env python3
"""Revert the 25 broken-style-migrated files back to visit(CompilationUnit)."""
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

    # If param is a single lowercase word (like "e", "m", "v"), it's a migrated one-letter
    # name. Real visit params were "n" or "ctxName".
    if bad_param != 'n' and bad_param != 'ctx':
        # Look for the for-loop var and revert BOTH names
        # The for-loop was changed to "for (Type var0 :" if var == param
        # Find the for-loop's loop var
        lm = re.search(r'for\s*\([^)]*?(\b\w+)\s*:\s*n\.findAll\(', text)
        if lm:
            loop_var = lm.group(1)
            # Revert loop var back to its original name (drop the trailing 0)
            if loop_var.endswith('0'):
                orig_loop_var = loop_var[:-1]
                text = re.sub(r'\b' + re.escape(loop_var) + r'\b', orig_loop_var, text)
            # Revert visit param to n
            text = re.sub(
                rf'public\s+void\s+visit\([^,]+?,\s*\w+\)\s*\{{',
                f'public void visit(CompilationUnit n, StyleContext ctx) {{',
                text,
                count=1,
            )
        else:
            return False
    if text != orig:
        with open(path, 'w', encoding='utf-8') as f:
            f.write(text)
        print(f"Reverted {os.path.basename(path)}")
        return True
    return False


fixed = 0
for entry in sorted(os.listdir(DIR)):
    if not entry.endswith('Check.java'):
        continue
    if fix(os.path.join(DIR, entry)):
        fixed += 1
print(f'\nReverted: {fixed}')
print('Run: mvn -o test')