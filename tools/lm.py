# Character n-gram LM (interpolated Kneser-Ney-ish absolute discounting) + CTC prefix beam search
import math, collections, numpy as np
BOS, EOS = '^', '$'
class CharLM:
    def __init__(self, sentences, order=6, d=0.75):
        self.n, self.d = order, d
        self.cnt = [collections.defaultdict(collections.Counter) for _ in range(order)]
        chars=set()
        for s in sentences:
            t = BOS*(order-1) + s + EOS
            chars.update(s)
            for i in range(order-1, len(t)):
                for k in range(order):
                    self.cnt[k][t[i-k:i]][t[i]] += 1
        self.V = len(chars)+1
        self.tot = [{h: sum(c.values()) for h,c in lvl.items()} for lvl in self.cnt]
        self.cache = {}
    def prob(self, hist, ch):
        hist = (BOS*(self.n-1) + hist)[-(self.n-1):] if self.n>1 else ''
        key=(hist,ch)
        if key in self.cache: return self.cache[key]
        p = 1.0/self.V
        for k in range(self.n):
            h = hist[len(hist)-k:] if k else ''
            c = self.cnt[k].get(h)
            if not c: continue
            tot = self.tot[k][h]; typ = len(c)
            p = max(c.get(ch,0)-self.d,0)/tot + (self.d*typ/tot)*p
        self.cache[key]=p
        return p
    def logp(self, hist, ch): return math.log(self.prob(hist, ch))

def lse(a,b):
    if a==-math.inf: return b
    if b==-math.inf: return a
    m=max(a,b); return m+math.log(math.exp(a-m)+math.exp(b-m))

def beam_decode(lp, vocab, lm=None, alpha=0.5, beta=1.0, beam=16, prune=-12.0, blank=0, skip=(1,2)):
    # lp: [T, C] log probs
    beams = {'': (0.0, -math.inf)}  # prefix -> (p_blank, p_nonblank)
    lmscore = {'': 0.0}
    T,C = lp.shape
    for t in range(T):
        row = lp[t]
        cand = [c for c in range(C) if row[c] > prune and c not in skip and c!=blank]
        nb = collections.defaultdict(lambda: [-math.inf,-math.inf])
        for pre,(pb,pnb) in beams.items():
            ptot = lse(pb,pnb)
            # blank
            e = nb[pre]; e[0] = lse(e[0], ptot + row[blank])
            last = pre[-1] if pre else None
            for c in cand:
                ch = vocab[c]
                if ch == ' ' and (not pre or last==' '):
                    # leading/double space: treat as stay
                    e = nb[pre]; e[1] = lse(e[1], ptot + row[c]); continue
                if ch == last:
                    e = nb[pre]; e[1] = lse(e[1], pnb + row[c])          # repeat collapse
                    newp = pre+ch; e2 = nb[newp]; e2[1] = lse(e2[1], pb + row[c])
                else:
                    newp = pre+ch; e2 = nb[newp]; e2[1] = lse(e2[1], ptot + row[c])
                if newp not in lmscore:
                    lmscore[newp] = lmscore[pre] + (alpha*lm.logp(pre, ch) if lm else 0) + beta
        scored = sorted(nb.items(), key=lambda kv: -(lse(*kv[1]) + lmscore[kv[0]]))[:beam]
        beams = {k: tuple(v) for k,v in scored}
    def final(k):
        s = lse(*beams[k]) + lmscore[k]
        if lm: s += alpha*lm.logp(k.rstrip(), '$')
        return s
    best = max(beams, key=final)
    return ' '.join(best.split())
