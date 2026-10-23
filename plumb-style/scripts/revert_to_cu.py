import os, re

DIR = 'src/main/java/org/plumb/style/style'
PAT = re.compile(r'public void visit\(([^,]+?),\s*([a-zA-Z]+)\)\s*\{')

fixed = 0
for f in sorted(os.listdir(DIR)):
    if not f.endswith('Check.java'): continue
    p = os.path.join(DIR, f)
    t = open(p, errors='ignore').read()
    m = PAT.search(t)
    if not m: continue
    bad = m.group(2)
    if bad == 'n': continue
    # Revert to visit(CompilationUnit n, StyleContext ctx)
    new_t = re.sub(
        r'public void visit\([^,]+?,\s*' + re.escape(bad) + r'\)\s*\{',
        'public void visit(CompilationUnit n, StyleContext ctx) {',
        t, count=1,
    )
    # Also revert the loop var name from var0 back to var (drop the 0)
    # Find loop vars with the same shadow pattern
    for vm in re.finditer(r'for\s*\([^)]*?(\w+)0\s*:', t):
        loopvar_orig = vm.group(1)
        # Replace loopvar0 with loopvar in the file
        new_t = re.sub(r'\b' + re.escape(loopvar_orig + '0') + r'\b', loopvar_orig, new_t)
    if new_t != t:
        open(p, 'w', errors='ignore').write(new_t)
        fixed += 1
        print(f, bad)
print(f'\nReverted: {fixed}')