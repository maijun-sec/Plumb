import os, re

# Check CWE190 didn't lose any files (R759-D shouldn't affect overflow)
def load_hits(d, prefix):
    hits = set()
    for f in os.listdir(d):
        if not f.startswith(prefix): continue
        try:
            with open(os.path.join(d, f), 'r', encoding='utf-8', errors='replace') as fp:
                for line in fp:
                    m2 = re.search(r'"file"\s*:\s*"([^"]+)"', line)
                    if m2: hits.add(os.path.basename(m2.group(1)))
        except: pass
    return hits

# CWE190 regression check
for cwe_id in ['CWE190','CWE89','CWE78','CWE80','CWE113','CWE15','CWE23','CWE36','CWE470','CWE601','CWE400','CWE476']:
    o = load_hits(r'D:\work\workspace\idea\engine\Plumb\.aethercode\r758_bulk', cwe_id)
    n = load_hits(r'D:\tmp\plumb_test\r759d_eval', cwe_id)
    delta = len(n) - len(o)
    if delta != 0:
        print(f'{cwe_id}: R758={len(o)} R759-D={len(n)} Δ={delta:+d}')
    else:
        print(f'{cwe_id}: NO CHANGE ({len(o)} files)')