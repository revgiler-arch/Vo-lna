"""Найти элемент на экране по тексту/описанию в дампе uiautomator и вывести координаты центра."""
import re, sys, xml.etree.ElementTree as ET
path, needle = sys.argv[1], sys.argv[2]
idx = int(sys.argv[3]) if len(sys.argv) > 3 else 0
hits = []
for n in ET.parse(path).iter('node'):
    t = (n.get('text') or '') + '|' + (n.get('content-desc') or '')
    cls = n.get('class') or ''
    if (needle == '@edit' and 'EditText' in cls) or (needle != '@edit' and needle.lower() in t.lower()):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        hits.append(((x1 + x2) // 2, (y1 + y2) // 2))
if len(hits) > idx:
    print(*hits[idx])
