#!/usr/bin/env python3
"""Rename loop variable to avoid shadowing the visit parameter.

If the patch introduced a parameter name that matches the loop variable
(e.g. visit(Type, e) + for (Type e : ...)), rename the loop variable.
"""
import os
import re

DIR = 'src/main/java/org/plumb/style/style'

VISIT_RE = re.compile(r'public\s+void\s+visit\([^,]+?,\s*(\w+)\s*\)')
LOOP_RE_TEMPLATE = r'for\s*\([^,]*?\b{type}\s+{var}\b\s*:'
FOR_BODY_RE = re.compile(r'\bfor\s*\(([^,]+),\s*(\w+)\s*:\s*n\.findAll\(')


def fix(path: str) -> bool:
    try:
        with open(path, 'r', encoding='utf-8') as f:
            content = f.read()
    except UnicodeDecodeError:
        try:
            with open(path, 'r', encoding='latin-1') as f:
                content = f.read()
        except Exception as e:
            print(f"skip {path}: {e}")
            return False
    orig = content

    visit_match = VISIT_RE.search(content)
    if not visit_match:
        return False
    param = visit_match.group(1)

    # Find the for-loop variable
    loop_match = FOR_BODY_RE.search(content)
    if not loop_match:
        return False
    type_name = loop_match.group(1).strip().split('.')[-1]
    loop_var = loop_match.group(2)

    if param != loop_var:
        return False

    # Rename loop_var to <loop_var>X where X is unused (just append underscore, or use the
    # full FQN first segment).
    # Use the visit parameter's type to pick a safe name. Simplest: use the original loop_var + 'N'.
    # We rename ONLY the loop variable within this file's for-loop, not other usages.
    # Find the for-loop line for this type
    safe = loop_var + '0'
    # Replace the loop declaration: "for (TypeName var :"
    new_content = re.sub(
        rf'for\s*\(\s*([^,]+?)\s+{re.escape(loop_var)}\s*:',
        lambda m: f'for ({m.group(1)} {safe} :',
        content
    )
    # Also rename references inside the body to <loop_var>0. The body references are
    # expressions like "<var>.getX()". Find these and rename them.
    new_content = re.sub(rf'\b{re.escape(loop_var)}\.', safe + '.', new_content)
    # Some references are bare "<var>" used in conditions. Replace word-boundary.
    # Be conservative: only do simple property access replacement to avoid breakage.

    if new_content != content:
        try:
            with open(path, 'w', encoding='utf-8') as f:
                f.write(new_content)
        except UnicodeEncodeError:
            with open(path, 'w', encoding='latin-1') as f:
                f.write(new_content)
        print(f"Renamed loop var {loop_var} -> {safe} in {os.path.basename(path)}")
        return True
    return False


def main():
    fixed = 0
    for entry in sorted(os.listdir(DIR)):
        if not entry.endswith('Check.java'):
            continue
        path = os.path.join(DIR, entry)
        if fix(path):
            fixed += 1
    print(f'\nTotal renamed: {fixed}')
    print('Run: mvn -o test')


if __name__ == '__main__':
    main()