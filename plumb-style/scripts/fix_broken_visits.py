#!/usr/bin/env python3
"""Repair checks whose visit signature lost the variable name.

For each broken file: find the type name in the visit signature, then find the
matching for-loop variable in the body, then string-replace the visit signature
to include the variable name.

Run from plumb-style directory: python scripts/fix_broken_visits.py
"""
import os
import re
import sys

DIR = 'src/main/java/org/plumb/style/style'
FILES = [
    'EnumAbstractMethodCheck', 'EnumIdiomCheck',
    'ExplicitInitializationCheck', 'FallThroughCheck',
    'IdempotentOperationsCheck', 'IncompatibleMaskCheck',
    'InstantiationToGetClassCheck', 'NoEnumTrailingCommaCheck',
    'ObjectsEqualityCheck', 'OperatorWrapCheck',
    'ParenPadCheck', 'PublicProtectedFieldInEnumCheck',
    'QuestionableBooleanAssignmentCheck',
    'ReachableAssertionCheck', 'RelationalOperatorSymmetryCheck',
    'RequireThisCheck', 'SeparatorWrapCheck',
    'SerializableSuperConstructorCheck', 'SignOfAdditionCheck',
    'SimpleDateFormatWithoutLocaleCheck',
    'SingleSpaceSeparatorCheck', 'StartWhereGreaterThanEndCheck',
    'SuspiciousIntegerDivisionCheck',
    'UnclearedSensitiveInfoCheck', 'UnnecessaryEnumCaseCheck',
    'UpperEllCheck', 'WhitespaceAfterCheck',
    'EmptyStaticInitializerCheck', 'EmptyZipEntryCheck',
    'ModifierOrderCheck', 'RvRemoveRepeatCallsCheck',
]

# Matches "public void visit(<Type>, ctx) {" or with StyleContext before ctx
VISIT_RE = re.compile(
    r'public\s+void\s+visit\(\s*(?P<type>[^,]+?)\s*,\s*(?:StyleContext\s+)?\w+\s*\)\s*\{'
)

# Matches "for (<FQTypeOrSimple> var :" within ~15 lines below
LOOP_RE = re.compile(r'for\s*\(\s*([^,)]+?)\s+(?P<var>\w+)\s*:\s*n\.findAll\(')


def fix(path: str) -> bool:
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    orig = content

    visit_match = VISIT_RE.search(content)
    if not visit_match:
        return False

    visit_type = visit_match.group('type').strip()

    # Find loop variable
    after = content[visit_match.end():]
    loop_match = LOOP_RE.search(after[:5000])  # search first 5KB after visit
    if not loop_match:
        sys.stderr.write(f"WARN: {os.path.basename(path)}: no loop var found for {visit_type}\n")
        return False
    loop_var = loop_match.group('var')

    old_sig = f'visit({visit_type}, ctx)'
    new_sig = f'visit({visit_type}, {loop_var})'
    new_content = content.replace(old_sig, new_sig)
    if new_content == content:
        return False

    with open(path, 'w', encoding='utf-8') as f:
        f.write(new_content)
    print(f"Fixed {os.path.basename(path)}: visit({visit_type}, {loop_var})")
    return True


def main() -> int:
    fixed = 0
    for name in FILES:
        path = os.path.join(DIR, f'{name}.java')
        if os.path.isfile(path) and fix(path):
            fixed += 1
    print(f'\nTotal fixed: {fixed}')
    print('Now run: mvn -o test')
    return 0


if __name__ == '__main__':
    sys.exit(main())