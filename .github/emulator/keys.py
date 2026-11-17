"""Helpers for the emulator test.
keys.py pos <keys.txt> <text>   → one "x y" line per character (space = SPACE key)
keys.py one <keys.txt> <id>     → "x y" of one key id (e.g. LANGUAGE, SLOT1, ".")
keys.py field <ui.xml> <desc>   → "x y" centre of the view with that content-desc
keys.py text <ui.xml> <desc>    → text of that view
"""
import re, sys

def load_keys(path):
    line = open(path, encoding='utf-8').read().strip().splitlines()[-1]
    line = line[line.index('layout='):]
    out = {}
    for part in line.split('|')[1:]:
        k, v = part.rsplit('=', 1)
        out[k] = v.replace(',', ' ')
    return out

def node(path, desc):
    xml = open(path, encoding='utf-8').read()
    for m in re.finditer(r'<node [^>]*>', xml):
        n = m.group(0)
        if f'content-desc="{desc}"' in n:
            return n
    return None

mode, path, arg = sys.argv[1], sys.argv[2], sys.argv[3]
if mode == 'pos':
    k = load_keys(path)
    for ch in arg:
        print(k['SPACE' if ch == ' ' else ch])
elif mode == 'one':
    print(load_keys(path)[arg])
elif mode == 'field':
    n = node(path, arg)
    x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
elif mode == 'text':
    n = node(path, arg)
    print(re.search(r' text="([^"]*)"', n).group(1) if n else '(not found)')
