import os, re
d758 = r'D:\work\workspace\idea\engine\Plumb\.aethercode\r758_bulk'
d759 = r'D:\tmp\plumb_test\r759d_eval'

# Count findings of plumb.boundary.integer-underflow per CWE191 dir
for d, label in [(d758, 'R758'), (d759, 'R759-D')]:
    total = 0
    for sub in ['s01','s02','s03','s04','s05']:
        p = os.path.join(d, f'CWE191.{sub}.ndjson')
        if not os.path.exists(p): continue
        with open(p, 'r', encoding='utf-8', errors='replace') as fp:
            for line in fp:
                if 'plumb.boundary.integer-underflow' in line:
                    total += 1
    print(f'{label}: plumb.boundary.integer-underflow findings (all subdirs): {total}')

# Check goodB2G behavior in particular
for d, label in [(d758, 'R758'), (d759, 'R759-D')]:
    gbg = 0
    for sub in ['s01','s02','s03','s04','s05']:
        p = os.path.join(d, f'CWE191.{sub}.ndjson')
        if not os.path.exists(p): continue
        with open(p, 'r', encoding='utf-8', errors='replace') as fp:
            for line in fp:
                if 'plumb.boundary.integer-underflow' in line and 'goodB2G' in line:
                    gbg += 1
    print(f'{label}: goodB2G + boundary findings: {gbg}')