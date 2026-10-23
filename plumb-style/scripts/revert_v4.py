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
    # Look for for-loop
    lp = re.compile(r'for\s*\([^)]*?\b' + bad + r'0?\s*:\s*n\.findAll\(')
    lm = lp.search(t)
    if not lm: continue
    loopvar = lm.group(0).split('(')[1].split()[0].strip()
    # Fix visit signature
    nt = re.sub(r'public void visit\(' + re.escape(m.group(1)) + r',\s*' + re.escape(bad) + r'\)\s*\{',
                'public void visit(CompilationUnit n, StyleContext ctx) {',
                t, count=1)
    # Revert loop var
    if loopvar.endswith('0'):
        nt = re.sub(r'\b' + re.escape(loopvar) + r'\b', loopvar[:-1], nt)
    if nt != t:
        open(p, 'w', errors='ignore').write(nt)
        fixed += 1
        print(f, bad, '->', loopvar, '->', loopvar.rstrip('0'))

print(f'\nTotal fixed: {fixed}')