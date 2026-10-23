import re, os

DIR = 'src/main/java/org/plumb/style/style'
for f in sorted(os.listdir(DIR)):
    if not f.endswith('Check.java'): continue
    p = os.path.join(DIR, f)
    t = open(p, errors='ignore').read()
    m = re.search(r'public void visit\(([^,]+?),\s*([a-zA-Z]+)\)\s*\{', t)
    if m: print(f, repr(m.group(1).strip()), repr(m.group(2)))