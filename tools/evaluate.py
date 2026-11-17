"""Measure CER/WER of an ONNX model on a tab-separated list "wav_path<TAB>latin text".

usage: python evaluate.py model.onnx test.tsv [--lm uyghur_char_lm.txt] [--sr 22050] [--n 300]

Use --sr 16000 to simulate the old app behaviour (16 kHz audio fed to a 22050 Hz model).
"""
import argparse, time
import numpy as np, onnxruntime as ort, librosa
from lm import beam_decode
from lm_file import FileLM

ap = argparse.ArgumentParser()
ap.add_argument('model'); ap.add_argument('tsv')
ap.add_argument('--lm'); ap.add_argument('--sr', type=int, default=22050)
ap.add_argument('--n', type=int, default=300)
ap.add_argument('--alpha', type=float, default=0.3); ap.add_argument('--beta', type=float, default=1.0)
a = ap.parse_args()

V = ['', '', ''] + list("abcdefghijklmnopqrstuvwxyz éöü’")
rows = [l.rstrip('\n').split('\t') for l in open(a.tsv, encoding='utf-8-sig') if '\t' in l][:a.n]
sess = ort.InferenceSession(a.model)
lm = FileLM(a.lm) if a.lm else None

def ed(x, y):
    d = list(range(len(y) + 1))
    for i, p in enumerate(x, 1):
        prev, d[0] = d[:], i
        for j, q in enumerate(y, 1):
            d[j] = min(prev[j] + 1, d[j - 1] + 1, prev[j - 1] + (p != q))
    return d[-1]

def greedy(lp):
    out, prev = [], -1
    for i in lp.argmax(1):
        if i > 2 and i != prev: out.append(V[i])
        prev = i
    return ' '.join(''.join(out).split())

stats = {'greedy': [0, 0, 0, 0], 'beam': [0, 0, 0, 0]}
t = 0
for path, ref in rows:
    y, _ = librosa.load(path, sr=a.sr)
    t0 = time.time(); lp = sess.run(None, {'input': y.astype(np.float32)})[0][0].T; t += time.time() - t0
    for k, hyp in (('greedy', greedy(lp)), ('beam', beam_decode(lp, V, lm, a.alpha, a.beta))):
        s = stats[k]; s[0] += ed(hyp, ref); s[1] += len(ref); s[2] += ed(hyp.split(), ref.split()); s[3] += len(ref.split())
print(f'n={len(rows)} avg inference {t / len(rows):.3f}s')
for k, s in stats.items():
    print(f'{k:7s} CER {100 * s[0] / s[1]:5.2f}%  WER {100 * s[2] / s[3]:5.2f}%')
