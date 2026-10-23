import os, re, csv, json
from collections import Counter, defaultdict

def load_hits(d):
    # Per (basename, cwe_id) -> list of findings
    hits = defaultdict(lambda: defaultdict(list))
    for f in os.listdir(d):
        if not f.endswith('.ndjson'): continue
        # Extract CWE number from filename
        m = re.match(r'CWE(\d+)', f)
        if not m: continue
        cwe_id = f'CWE-{m.group(1)}'
        try:
            with open(os.path.join(d, f), 'r', encoding='utf-8', errors='replace') as fp:
                for line in fp:
                    m2 = re.search(r'"file"\s*:\s*"([^"]+)"', line)
                    m3 = re.search(r'"cwe"\s*:\s*\[\s*"([^"]+)"', line)
                    if not (m2 and m3): continue
                    bn = os.path.basename(m2.group(1))
                    cwe_in_record = m3.group(1)
                    hits[bn][cwe_in_record].append(line)
        except: pass
    return hits

def load_pos():
    multi_re = re.compile(r'[;,]')
    cross_re = re.compile(r'_(\d{2,3})[a-z]\.java$')
    pos = defaultdict(set)  # cwe_id -> set of basenames
    with open(r'D:\tmp\plumb_test\plumb_testcases_detail.csv') as f:
        r = csv.DictReader(f)
        for row in r:
            if row['POSITIVE'] != 'True': continue
            fs = row['FILES']
            if multi_re.search(fs) or cross_re.search(fs): continue
            for x in fs.split(';'):
                x = x.strip()
                if x:
                    pos[row['CWE_ID']].add(x)
    return pos

old_h = load_hits(r'D:\work\workspace\idea\engine\Plumb\.aethercode\r758_bulk')
new_h = load_hits(r'D:\tmp\plumb_test\r759d_eval')
pos = load_pos()

print("Per-CWE Recall (R758 vs R759-D):")
print(f"{'CWE':<8} {'Pos':<10} {'R758 TP':<10} {'R759-D TP':<10} {'R758 Rcl':<10} {'R759-D Rcl':<10} {'Δ TP'}")
total_pos = 0; total_r758 = 0; total_r759 = 0
for cwe_id in sorted(pos.keys()):
    p_set = pos[cwe_id]
    r758_tp = sum(1 for bn in p_set if cwe_id in old_h.get(bn, {}))
    r759_tp = sum(1 for bn in p_set if cwe_id in new_h.get(bn, {}))
    if len(p_set) == 0: continue
    delta = r759_tp - r758_tp
    print(f'{cwe_id:<8} {len(p_set):<10} {r758_tp:<10} {r759_tp:<10} {r758_tp/len(p_set)*100:<10.2f} {r759_tp/len(p_set)*100:<10.2f} {delta:+d}')
    total_pos += len(p_set)
    total_r758 += r758_tp
    total_r759 += r759_tp

print(f'\nTotal Pos={total_pos}')
print(f'R758 Recall: {total_r758/total_pos*100:.2f}% ({total_r758}/{total_pos})')
print(f'R759-D Recall: {total_r759/total_pos*100:.2f}% ({total_r759}/{total_pos})')
print(f'Δ: +{total_r759 - total_r758} TP')