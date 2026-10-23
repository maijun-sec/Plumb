import csv
from pathlib import Path
for p in [Path(r'D:\tmp\plumb_test\plumb_rule_to_cwe.csv'), Path(r'D:\work\workspace\idea\engine\Plumb\plumb-dfa\src\main\resources\scripts\plumb_rule_to_cwe.csv')]:
    print(f'=== {p} ===')
    with p.open('r', encoding='utf-8') as f:
        rd = csv.DictReader(f)
        for r in rd:
            cwes = r.get('cwes', '')
            if '789' in cwes:
                rid = r.get('rule_id')
                ncwe = r.get('n_cwe')
                print(f'  {rid} | cwes={cwes} | n_cwe={ncwe}')