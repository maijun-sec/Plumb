import os, re

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

o = load_hits(r'D:\work\workspace\idea\engine\Plumb\.aethercode\r758_bulk', 'CWE191')
n = load_hits(r'D:\tmp\plumb_test\r759d_eval', 'CWE191')
print(f'CWE191 files: R758={len(o)} R759-D={len(n)} Δ={len(n)-len(o):+d}')
new = n - o
lost = o - n
print(f'New files: {len(new)}')
print(f'Lost files: {len(lost)}')
if lost:
    print('Sample lost:')
    for f in sorted(lost)[:10]:
        print(f'  - {f}')