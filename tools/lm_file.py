# Reference loader for exported LM file (mirrors the Kotlin CharLanguageModel exactly)
import math
class FileLM:
    def __init__(self, path):
        self.ctx = {}
        with open(path, encoding='utf-8') as f:
            hdr = f.readline()
            kv = dict(x.split('=') for x in hdr[1:].split()[1:])
            self.n, self.d, self.V = int(kv['order']), float(kv['d']), int(kv['V'])
            for line in f:
                h, tot, typ, rest = line.rstrip('\n').split('\t')
                self.ctx[h] = (int(tot), int(typ), {e[0]: int(e[1:]) for e in rest.split('|')})
        self.cache = {}
    def logp(self, hist, ch):
        hist = ('^'*(self.n-1) + hist)[-(self.n-1):]
        key = (hist, ch)
        if key in self.cache: return self.cache[key]
        p = 1.0/self.V
        for k in range(self.n):
            h = hist[len(hist)-k:] if k else ''
            c = self.ctx.get(h)
            if c is None: continue
            tot, typ, m = c
            p = max(m.get(ch,0)-self.d, 0)/tot + (self.d*typ/tot)*p
        r = math.log(p); self.cache[key] = r; return r
