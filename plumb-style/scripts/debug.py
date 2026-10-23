#!/usr/bin/env python3
import re
text = open('src/main/java/org/plumb/style/style/EnumAbstractMethodCheck.java').read()
visit_m = re.search(r'public\s+void\s+visit\([^,]+?,\s*(\w+)\s*\)', text)
print('visit:', visit_m.group(1) if visit_m else None)
loop_m = re.search(r'\bfor\s*\(([^)]+?)\s+(\w+)\s*:', text)
print('loop:', loop_m.group(0) if loop_m else None, 'type=', loop_m.group(1) if loop_m else None, 'var=', loop_m.group(2) if loop_m else None)