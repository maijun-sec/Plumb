import os, re, csv
hit_files = set()
d = r'D:\tmp\plumb_test\r759d_eval'
for f in os.listdir(d):
    if f.endswith('.ndjson'):
        try:
            with open(os.path.join(d, f), 'r', encoding='utf-8', errors='replace') as fp:
                for line in fp:
                    if 'CWE' not in line: continue
                    m = re.search(r'"file"\s*:\s*"([^"]+)"', line)
                    if m: hit_files.add(os.path.basename(m.group(1)))
        except: pass

files_pos = set()
multi_re = re.compile(r'[;,]')
cross_re = re.compile(r'_(\d{2,3})[a-z]\.java$')
with open(r'D:\tmp\plumb_test\plumb_testcases_detail.csv') as f:
    r = csv.DictReader(f)
    for row in r:
        if row['POSITIVE'] != 'True': continue
        fs = row['FILES']
        if multi_re.search(fs) or cross_re.search(fs): continue
        for x in fs.split(';'):
            x = x.strip()
            if x: files_pos.add(x)

tp = files_pos & hit_files
print(f'Single-file Pos: {len(files_pos)}')
print(f'R759-D TP (single-file): {len(tp)}')
print(f'R759-D Recall (single-file): {len(tp)/len(files_pos)*100:.2f}%')
print(f'Unique hit files R759-D (all): {len(hit_files)}')

# Compare R758
hit_files758 = set()
d758 = r'D:\work\workspace\idea\engine\Plumb\.aethercode\r758_bulk'
for f in os.listdir(d758):
    if f.endswith('.ndjson'):
        try:
            with open(os.path.join(d758, f), 'r', encoding='utf-8', errors='replace') as fp:
                for line in fp:
                    if 'CWE' not in line: continue
                    m = re.search(r'"file"\s*:\s*"([^"]+)"', line)
                    if m: hit_files758.add(os.path.basename(m.group(1)))
        except: pass

tp758 = files_pos & hit_files758
print(f'\nR758 TP (single-file): {len(tp758)}')
print(f'R758 Recall (single-file): {len(tp758)/len(files_pos)*100:.2f}%')

print(f'\nNew TP: {len(tp - tp758)}')
print(f'Lost TP: {len(tp758 - tp)}')