"""Build the character LM asset from transcripts.

usage: python export_char_lm.py out.txt 6 2 transcripts1.tsv [transcripts2.tsv ...]
(order=6, contexts seen fewer than 2 times are pruned). Last tab column of each line = Latin text.
Add any extra Uyghur Latin text (one sentence per line) to make the LM better.
"""
# Export char n-gram counts: one line per context: ctx \t total \t types \t ch:count ... (pruned)
import sys, collections
from lm import CharLM
order=int(sys.argv[2]); minc=int(sys.argv[3]); out=sys.argv[1]; files=sys.argv[4:]
VOC=set("abcdefghijklmnopqrstuvwxyz éöü’")
sents=set()
for f in files:
    for l in open(f,encoding='utf-8-sig'):
        p=l.rstrip('\n').split('\t'); s=' '.join(''.join(ch for ch in p[-1].lower().replace("'",'’') if ch in VOC).split())
        if s: sents.add(s)
lm=CharLM(sorted(sents), order=order)
n=0
with open(out,'w',encoding='utf-8') as o:
    o.write(f'#charlm order={order} d=0.75 V={lm.V}\n')
    for k in range(order):
        for h,c in lm.cnt[k].items():
            tot=sum(c.values())
            if k>=2 and tot<minc: continue
            o.write(h.replace('\t',' ')+'\t'+str(tot)+'\t'+str(len(c))+'\t'+'|'.join(f'{ch}{v}' for ch,v in c.items())+'\n'); n+=1
print('sentences',len(sents),'contexts',n)
