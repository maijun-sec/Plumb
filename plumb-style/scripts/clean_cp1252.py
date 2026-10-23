#!/usr/bin/env python3
"""Final cleanup: convert CP1252 high-byte sequences to UTF-8-friendly ASCII."""
import os

DIR = 'src/main/java/org/plumb/style/style'

# Common 3-byte UTF-8 sequences javac misreads when file was edited in CP1252.
# The byte sequence shown by od as "M-i M-^H ?" etc. = 0xE2 0x88 0x?? = element-of family.
MAP = {
    # "∈" 0xE2 0x88 0x98
    b'\xe2\x88\x98': b' in ',
    # "∋" 0xE2 0x88 0x9B (contains as)
    b'\xe2\x88\x9b': b' contains ',
    # "→" 0xE2 0x86 0x92
    b'\xe2\x86\x92': b'->',
    # "↗" 0xE2 0x86 0x97
    b'\xe2\x86\x97': b'->',
    # Smart quotes from MS Word pastes
    b'\xe2\x80\x99': b"'",
    b'\xe2\x80\x98': b"'",
    b'\xe2\x80\x9c': b'"',
    b'\xe2\x80\x9d': b'"',
    # Em/en dash
    b'\xe2\x80\x94': b'--',
    b'\xe2\x80\x93': b'-',
    # Ellipsis
    b'\xe2\x80\xa6': b'...',
    # Other common math/symbol
    b'\xc2\xb0': b' deg ',
    b'\xc2\xb1': b'+/-',
    b'\xc3\x97': b'x',
}

fixed = 0
for f in sorted(os.listdir(DIR)):
    if not f.endswith('Check.java'):
        continue
    p = os.path.join(DIR, f)
    with open(p, 'rb') as fh:
        d = fh.read()
    orig = d
    for bad, good in MAP.items():
        d = d.replace(bad, good)
    # Strip remaining stray lead bytes (incomplete sequences from CP1252 save)
    import re
    d = re.sub(rb'[\xe2\xe3][\x00-\x7f]', b'', d)
    d = re.sub(rb'[\xc2\xc3][\x00-\x7f]', b'', d)
    if d != orig:
        with open(p, 'wb') as fh:
            fh.write(d)
        fixed += 1
        print(f)

print(f'\nCleaned: {fixed}')
print('Run: mvn -o test')