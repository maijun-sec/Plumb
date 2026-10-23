import os, re, csv

def load_hits(d, cwe_filter='CWE191'):
    hits = set()
    for f in os.listdir(d):
        if not f.startswith(cwe_filter): continue
        try:
            with open(os.path.join(d, f), 'r', encoding='utf-8', errors='replace') as fp:
                for line in fp:
                    m2 = re.search(r'"file"\s*:\s*"([^"]+)"', line)
                    if m2: hits.add(os.path.basename(m2.group(1)))
        except: pass
    return hits

multi_re = re.compile(r'[;,]')
cross_re = re.compile(r'_(\d{2,3})[a-z]\.java$')
pos = set()
with open(r'D:\tmp\plumb_test\plumb_testcases_detail.csv') as f:
    r = csv.DictReader(f)
    for row in r:
        if row['POSITIVE'] != 'True': continue
        if row['CWE_ID'] != 'CWE191': continue
        fs = row['FILES']
        if multi_re.search(fs) or cross_re.search(fs): continue
        for x in fs.split(';'):
            x = x.strip()
            if x: pos.add(x)

o = load_hits(r'D:\work\workspace\idea\engine\Plumb\.aethercode\r758_bulk')
n = load_hits(r'D:\tmp\plumb_test\r759d_eval')

print(f'Pos={len(pos)} R758 hits={len(o)} R759-D hits={len(n)}')
print(f'R758 TP: {len(pos & o)} = {len(pos & o)/len(pos)*100:.2f}%')
print(f'R759-D TP: {len(pos & n)} = {len(pos & n)/len(pos)*100:.2f}%')
print(f'New TP: {len((pos & n) - (pos & o))}')
print(f'Lost TP: {len((pos & o) - (pos & n))}')

# Per-CWE-all (loop)
total_pos = 0; total_r758 = 0; total_r759 = 0
new_tp_global = 0; lost_tp_global = 0
for cwe_id in ['CWE191', 'CWE90', 'CWE606', 'CWE566', 'CWE113', 'CWE89', 'CWE78', 'CWE80', 'CWE15', 'CWE23', 'CWE36', 'CWE470', 'CWE601', 'CWE563', 'CWE190', 'CWE506', 'CWE510', 'CWE400', 'CWE476']:
    p_set = set()
    with open(r'D:\tmp\plumb_test\plumb_testcases_detail.csv') as f:
        r = csv.DictReader(f)
        for row in r:
            if row['POSITIVE'] != 'True': continue
            if row['CWE_ID'] != cwe_id: continue
            fs = row['FILES']
            if multi_re.search(fs) or cross_re.search(fs): continue
            for x in fs.split(';'):
                x = x.strip()
                if x: p_set.add(x)
    o = load_hits(r'D:\work\workspace\idea\engine\Plumb\.aethercode\r758_bulk', cwe_id)
    n = load_hits(r'D:\tmp\plumb_test\r759d_eval', cwe_id)
    r758 = p_set & o
    r759 = p_set & n
    delta = len(r759) - len(r758)
    print(f'  {cwe_id}: Pos={len(p_set)} R758={len(r758)} R759-D={len(r759)} Δ={delta:+d}')
    total_pos += len(p_set)
    total_r758 += len(r758)
    total_r759 += len(r759)
    new_tp_global += delta if delta > 0 else 0
    lost_tp_global += -delta if delta < 0 else 0

print(f'\nSubset total Pos={total_pos}')
print(f'Recall R758: {total_r758/total_pos*100:.2f}% ({total_r758}/{total_pos})')
print(f'Recall R759-D: {total_r759/total_pos*100:.2f}% ({total_r759}/{total_pos})')
print(f'Δ: +{total_r759 - total_r758} TP, new={new_tp_global}, lost={lost_tp_global}')