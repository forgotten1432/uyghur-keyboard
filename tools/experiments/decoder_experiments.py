"""Decoder experiments (LM / lexicon bonus / beam) on cached model outputs of THUYG-20 test sentences.
Results (300 held-out sentences, WER): app LM 12.01% → + lexicon bonus 11.64% (shipped);
bigger LM with dictionary words, word-boundary bonus, beam 32: no further gain beyond noise.
Paths are from the original experiment machine; adjust before running.
"""
import sys, math, collections, numpy as np, time
sys.path.insert(0,'/home/claude/scripts'); sys.path.insert(0,'/home/claude/uyghur-keyboard/tools/train')
from lm import CharLM, lse
from uyghur_train import uey_to_uly, normalize_uly
V = ['','',''] + list("abcdefghijklmnopqrstuvwxyz éöü’")
d = np.load('/tmp/asrtest/lp_cache.npz'); LPS = [d[f'arr_{i}'] for i in range(len(d.files))]
REFS = open('/tmp/asrtest/lp_refs.txt').read().split('\n')
DEV, TEST = list(range(60)), list(range(60, 360))

# ---------- LM with weighted word entries ----------
class WordAugLM(CharLM):
    def __init__(self, sentences, words, order=6, d=0.75):
        super().__init__(sentences, order, d)
        for w, wt in words:
            t = ' ' + w + ' '
            for i in range(1, len(t)):
                for k in range(order):
                    if k > i: break
                    self.cnt[k][t[i-k:i]][t[i]] += wt
        self.tot = [{h: sum(c.values()) for h,c in lvl.items()} for lvl in self.cnt]
        self.cache = {}

tr = [l.rstrip('\n').split('\t')[1] for l in open('/tmp/ext/uyghur-asr-ctc/thuyg20_train.csv', encoding='utf-8-sig')]
sents = sorted(set(' '.join(normalize_uly(s).split()) for s in tr))
words = []
for line in open('/home/claude/uyghur-keyboard/app/src/main/assets/uyghur_dict.txt', encoding='utf-8'):
    w, f = line.rstrip('\n').split('\t')
    u = normalize_uly(uey_to_uly(w))
    if u and ' ' not in u: words.append((u, int(f)))
print('dict words', len(words))
LEX = set(w for w, _ in words) | set(w for s in sents for w in s.split())

def ed(a, b):
    dd = list(range(len(b)+1))
    for i, x in enumerate(a, 1):
        p, dd[0] = dd[:], i
        for j, y in enumerate(b, 1): dd[j] = min(p[j]+1, dd[j-1]+1, p[j-1]+(x != y))
    return dd[-1]

def beam(lp, lm, alpha, beta, lexb=0.0, oovp=0.0, beam=16, prune=-12.0):
    beams = {'': (0.0, -math.inf)}; ls = {'': 0.0}
    def wordscore(pre, ch):
        if not (lexb or oovp) or ch != ' ': return 0.0
        w = pre.rsplit(' ', 1)[-1]
        return lexb if w in LEX else -oovp
    for row in lp:
        cand = [c for c in range(3, 34) if row[c] > prune]
        nb = collections.defaultdict(lambda: [-math.inf, -math.inf])
        for pre, (pb, pnb) in beams.items():
            pt = lse(pb, pnb); e = nb[pre]; e[0] = lse(e[0], pt + row[0])
            last = pre[-1] if pre else None
            for c in cand:
                ch = V[c]
                if ch == ' ' and (not pre or last == ' '):
                    e = nb[pre]; e[1] = lse(e[1], pt + row[c]); continue
                np_ = pre + ch
                if ch == last:
                    e = nb[pre]; e[1] = lse(e[1], pnb + row[c]); e2 = nb[np_]; e2[1] = lse(e2[1], pb + row[c])
                else:
                    e2 = nb[np_]; e2[1] = lse(e2[1], pt + row[c])
                if np_ not in ls:
                    ls[np_] = ls[pre] + (alpha * lm.logp(pre, ch) if lm else 0) + beta + wordscore(pre, ch)
        beams = {k: tuple(v) for k, v in sorted(nb.items(), key=lambda kv: -(lse(*kv[1]) + ls[kv[0]]))[:beam]}
    def fin(k):
        s = lse(*beams[k]) + ls[k] + (alpha * lm.logp(k.rstrip(), '$') if lm else 0)
        if lexb or oovp:
            w = k.rstrip().rsplit(' ', 1)[-1]
            s += lexb if w in LEX else -oovp
        return s
    return ' '.join(max(beams, key=fin).split())

def wer(idx, **kw):
    ce = cn = we = wn = 0
    for i in idx:
        h = beam(LPS[i], **kw); r = REFS[i]
        ce += ed(h, r); cn += len(r); we += ed(h.split(), r.split()); wn += len(r.split())
    return 100*ce/cn, 100*we/wn

if __name__ == '__main__':
    t0 = time.time()
    base = CharLM(sents, 6)
    def wt(f, mode): return 1 if mode == 'one' else max(1, round(math.log10(f)))
    aug = {m: WordAugLM(sents, [(w, wt(f, m)) for w, f in words], 6) for m in ('one', 'log')}
    print('LMs built %.0fs' % (time.time()-t0))
    print('dev: base a.3 b1      ', wer(DEV, lm=base, alpha=0.3, beta=1.0))
    for m, lm in aug.items():
        for a, b in ((0.3, 1.0), (0.5, 1.5), (0.5, 2.0), (0.7, 2.0)):
            print(f'dev: words-{m} a{a} b{b}', wer(DEV, lm=lm, alpha=a, beta=b))
    for lb, op in ((1.0, 0.0), (2.0, 1.0), (3.0, 2.0)):
        print(f'dev: base + lexicon bonus {lb}/{op}', wer(DEV, lm=base, alpha=0.3, beta=1.0, lexb=lb, oovp=op))
