#!/usr/bin/env python3
"""R-815 v3: definitive classification.

Approach:
  1. Detect any `visit(X param, StyleContext ctx)` overloads.
     If a per-node visit exists (other than CompilationUnit), pattern = D
     (dispatcher walk, single-pass).
  2. Otherwise, has visit(CompilationUnit) → check findAll/walk inside.
       - 0 findAll, 0 walk → pattern = E (no-op, but maybe legit).
       - 1 findAll → pattern = A.
       - N findAll (N>1) → pattern = B (merge candidates).
       - 0 findAll but M walk → pattern = C.

  3. If a checker has BOTH visit(CU) AND per-node visit overloads, it can
     be simplified to per-node only (or vice versa) — the dispatcher will
     call both. We mark these as "double-dispatch" warnings.
"""
import os, re

ROOT = r'D:/work/workspace/idea/engine/Plumb/plumb-style/src/main/java/org/plumb/style'

DISPATCH_TYPES = [
    'CompilationUnit', 'ImportDeclaration',
    'ClassOrInterfaceDeclaration', 'EnumDeclaration',
    'FieldDeclaration', 'MethodDeclaration', 'ConstructorDeclaration',
    'IfStmt', 'SwitchStmt', 'SwitchEntry', 'SynchronizedStmt',
    'WhileStmt', 'ForStmt', 'ForEachStmt', 'DoStmt',
    'BlockStmt', 'EmptyStmt', 'ExpressionStmt', 'ReturnStmt', 'TryStmt',
    'BinaryExpr', 'CatchClause', 'LabeledStmt', 'BreakStmt', 'ContinueStmt',
    'MethodCallExpr', 'ObjectCreationExpr', 'FieldAccessExpr', 'CastExpr',
    'ClassOrInterfaceType', 'VariableDeclarationExpr', 'NameExpr',
    'IntegerLiteralExpr', 'LongLiteralExpr', 'DoubleLiteralExpr', 'CharLiteralExpr',
    'BooleanLiteralExpr', 'TextBlockLiteralExpr', 'StringLiteralExpr',
    'NullLiteralExpr', 'ConditionalExpr', 'ArrayCreationExpr', 'UnaryExpr',
    'InstanceOfExpr',
]

# visit(X param, StyleContext ctx) signature — param name = arg1, ctx name = arg2.
VISIT_OVERLOAD = re.compile(
    r'public\s+void\s+visit\(\s*(?:com\.github\.javaparser\.ast\.(?:expr\.|stmt\.|body\.|type\.|))?(\w+)\s+(\w+)\s*,\s*StyleContext\s+(\w+)\s*\)'
)
FINDALL = re.compile(r'\.findAll\(\s*([\w.$]+)\.class\s*\)')
WALK = re.compile(r'\.walk\(\s*([\w.$]+)\.class\s*,')

def simplify(s: str) -> str:
    return s.split('.')[-1]

results = []

for subdir, dirs, files in os.walk(ROOT):
    for f in files:
        if not f.endswith('Check.java') or f.startswith('Abstract'):
            continue
        path = os.path.join(subdir, f)
        with open(path, 'rb') as fh:
            raw = fh.read()
        if raw.startswith(b'\xef\xbb\xbf'):
            raw = raw[3:]
        text = raw.decode('utf-8', errors='replace')
        if 'implements AstChecker' not in text and 'extends AstChecker' not in text:
            continue

        # All visit overloads.
        overloads = []
        for m in VISIT_OVERLOAD.finditer(text):
            arg_type = m.group(1)
            if arg_type in DISPATCH_TYPES:
                overloads.append(arg_type)
        overloads = list(set(overloads))

        cu_visit = 'CompilationUnit' in overloads
        per_node_visits = [t for t in overloads if t != 'CompilationUnit']

        findall_calls = FINDALL.findall(text)
        walk_calls = WALK.findall(text)
        findall_types = [simplify(t) for t in findall_calls]

        # In-loop findAll (in source line order).
        lines = text.splitlines()
        in_loop_depth = 0
        nested_findall = 0
        for ln in lines:
            stripped = ln.lstrip()
            if re.match(r'^(for|while)\s*\(', stripped) or stripped.startswith('do {'):
                in_loop_depth += 1
            if in_loop_depth > 0 and ('.findAll(' in ln or '.walk(' in ln):
                nested_findall += 1
            if re.match(r'^\s*\}', ln):
                in_loop_depth = max(0, in_loop_depth - 1)

        # Pattern.
        if per_node_visits:
            # Uses per-node visit. Single walk by AstDispatcher.
            # Special case: also has visit(CU) — that means BOTH fire.
            if cu_visit:
                pattern = 'D+'  # double dispatch
            else:
                pattern = 'D'
        elif cu_visit:
            if len(findall_calls) == 0 and len(walk_calls) == 0:
                pattern = 'E'
            elif len(findall_calls) == 1:
                pattern = 'A'
            elif len(findall_calls) > 1:
                pattern = 'B'
            else:
                pattern = 'C'
        else:
            # No visit at all? init-only or broken.
            pattern = 'E0'

        # Cost proxy.
        cost = {'A': 1, 'B': len(findall_calls), 'C': len(walk_calls), 'D': 1, 'D+': 2, 'E': 0, 'E0': 0}[pattern]

        results.append({
            'name': f[:-5],
            'pattern': pattern,
            'cu_visit': cu_visit,
            'per_node_visits': sorted(per_node_visits),
            'findall_count': len(findall_calls),
            'findall_distinct': sorted(set(findall_types)),
            'walk_count': len(walk_calls),
            'nested_findall': nested_findall,
            'cost': cost,
            'detail': ','.join(sorted(set(findall_types))) if findall_types else
                      (','.join(sorted(per_node_visits)) if per_node_visits else
                       ('walk:' + ','.join(set(simplify(t) for t in walk_calls)) if walk_calls else ''))
        })

from collections import Counter
patterns = Counter(r['pattern'] for r in results)
print(f'TOTAL: {len(results)} checkers')
for k in ['A', 'B', 'C', 'D', 'D+', 'E', 'E0']:
    if patterns.get(k):
        print(f'  pattern {k}: {patterns[k]}')
print()

total_cost_now = sum(r['cost'] for r in results)
savings = sum(r['cost'] - 1 for r in results if r['pattern'] == 'B')
print(f'Total walks per file (current): {total_cost_now}')
print(f'Walk savings if all B → A: {savings}')
print()

# Order: B first.
order = {'B': 0, 'D+': 1, 'A': 2, 'C': 3, 'D': 4, 'E': 5, 'E0': 6}
results.sort(key=lambda r: (order.get(r['pattern'], 9), -r['findall_count'], r['name']))

print('=== Pattern B: multiple findAll — MERGE CANDIDATES ===')
for r in results:
    if r['pattern'] != 'B':
        continue
    print(f"  {r['name']:<55} fA={r['findall_count']} types={r['findall_distinct']} nested={r['nested_findall']}")
print()
print('=== Pattern D+: BOTH visit(CU) AND per-node visit (double dispatch) ===')
for r in results:
    if r['pattern'] != 'D+':
        continue
    print(f"  {r['name']:<55} per_node={r['per_node_visits']}")
print()
print('=== Pattern C: uses n.walk() instead of findAll ===')
for r in results:
    if r['pattern'] != 'C':
        continue
    print(f"  {r['name']:<55} walks={r['walk_count']} detail={r['detail']}")
