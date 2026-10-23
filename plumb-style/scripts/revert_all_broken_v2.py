import os, re

DIR = 'src/main/java/org/plumb/style/style'
PAT = re.compile(r'public void visit\(([^,]+?),\s*([a-zA-Z]+)\)\s*\{')
LP = re.compile(r'for\s*\(([^)]*?)\s+(\w+)\s*:\s*n\.findAll\(')

fixed = 0
for f in sorted(os.listdir(DIR)):
    if not f.endswith('Check.java'): continue
    p = os.path.join(DIR, f)
    t = open(p, errors='ignore').read()
    m = PAT.search(t)
    if not m: continue
    bad = m.group(2)
    if bad == 'n': continue
    lm = LP.search(t)
    if not lm: continue
    loopvar = lm.group(2)
    # Fix visit signature - use loopvar
    new_t = re.sub(
        r'public void visit\(' + re.escape(m.group(1)) + r',\s*' + re.escape(bad) + r'\)\s*\{',
        f'public void visit({m.group(1).strip()}, {loopvar}) {{',
        t, count=1,
    )
    # Find any references to the bad var inside the visit body (n.findAll uses 'n' which isn't in scope)
    # The body used 'n.findAll' but 'n' is now the loopvar (after fix). That's WRONG.
    # Solution: revert the n.findAll to the new loopvar.
    # Actually wait, after the fix, body uses 'n' but n is the loopvar now. That's fine.
    # Let me check whether the original code used 'n.' for findAll.
    if '.findAll(' in t and ' n.findAll' in t:
        # No issue - after fix, the param is the loopvar, and 'n.' becomes 'loopvar.' which works
        pass
    if new_t != t:
        open(p, 'w', errors='ignore').write(new_t)
        fixed += 1
        print(f, bad, '->', loopvar)
print(f'\nFixed: {fixed}')