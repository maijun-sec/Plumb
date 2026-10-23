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
print(f'Unique hit files R759-D: {len(hit_files)}')

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
print(f'Unique hit files R758: {len(hit_files758)}')
print(f'Only R759-D: {len(hit_files - hit_files758)}')
print(f'Only R758: {len(hit_files758 - hit_files)}')

# Pos files from ground truth
files_pos = set()
multi_re = re.compile(r"[;,]")
with open(r'D:\tmp\plumb_test\plumb_testcases_detail.csv') as f:
    r = csv.DictReader(f)
    for row in r:
        if row['POSITIVE'] != 'True': continue
        for x in row['FILES'].split(';'):
            x = x.strip()
            if x: files_pos.add(x)
print(f'\nPos files: {len(files_pos)}')

tp_r758 = files_pos & hit_files758
tp_r759d = files_pos & hit_files
print(f'TP (R758  pos∩hit): {len(tp_r758)} -> Recall: {len(tp_r758)/len(files_pos)*100:.2f}%')
print(f'TP (R759-D pos∩hit): {len(tp_r759d)} -> Recall: {len(tp_r759d)/len(files_pos)*100:.2f}%')

# delta
new_tp = tp_r759d - tp_r758
lost_tp = tp_r758 - tp_r759d
print(f'\nNew TP (R759-D only): {len(new_tp)}')
print(f'Lost TP (R758 only): {len(lost_tp)}')