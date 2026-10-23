import os, re
d758 = r'D:\work\workspace\idea\engine\Plumb\.aethercode\r758_bulk'
d759 = r'D:\tmp\plumb_test\r759d_eval'
hits_758 = set(); hits_759 = set()
for d, hits_set in [(d758, hits_758), (d759, hits_759)]:
    for f in os.listdir(d):
        if not f.startswith('CWE191'): continue
        try:
            with open(os.path.join(d, f), 'r', encoding='utf-8', errors='replace') as fp:
                for line in fp:
                    m2 = re.search(r'"file"\s*:\s*"([^"]+)"', line)
                    if m2: hits_set.add(os.path.basename(m2.group(1)))
        except: pass
lost = hits_758 - hits_759
new = hits_759 - hits_758
print(f'Lost (R758 -> R759-D): {len(lost)}')
for f in sorted(lost):
    print(f'  - {f}')
print(f'\nNew: {len(new)}')
for f in sorted(new)[:20]:
    print(f'  + {f}')