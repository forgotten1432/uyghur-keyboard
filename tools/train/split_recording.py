"""Split one long recording (many sentences read in order, with pauses) into one file per sentence.

The recording is cut at short silences; each piece is transcribed with the app's current model and
the pieces are matched to the expected sentences by text (dynamic programming), so a sentence split
by a pause, a restart or an unread sentence does not shift the rest. Prints each sentence's CER:
high values (> ~15%) usually mean a restart inside the sentence, so such files are not written.

usage: python split_recording.py long.wav|m4a FIRST LAST OKU.txt OUT_DIR
  (m4a is converted with macOS afconvert)
"""
import sys, os, subprocess, tempfile, numpy as np, librosa, onnxruntime as ort, soundfile as sf
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uyghur_train import uey_to_uly, normalize_uly, edit_distance
SRC, FIRST, LAST, OKU, OUT = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4], sys.argv[5]
MODEL = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'app', 'src', 'main', 'assets', 'uyghur_asr_int8.onnx')
MAX_CER = 0.15
sr = 22050
V = ['', '', ''] + list("abcdefghijklmnopqrstuvwxyz éöü’")
sess = ort.InferenceSession(MODEL)
def asr(y):
    lp = sess.run(None, {'input': y.astype(np.float32)})[0][0].T
    out, prev = [], -1
    for i in lp.argmax(1):
        if i > 2 and i != prev: out.append(V[i])
        prev = i
    return ' '.join(''.join(out).split())
refs = {}
for line in open(OKU, encoding='utf-8'):
    n, t = line.strip().split(None, 1); refs[int(n)] = t
uly = {k: normalize_uly(uey_to_uly(v)) for k, v in refs.items()}
cer = lambda h, r: edit_distance(h, r) / max(1, len(r))
if SRC.endswith('.m4a'):
    tmp = tempfile.mktemp(suffix='.wav')
    subprocess.run(['afconvert', '-f', 'WAVE', '-d', 'LEI16@22050', '-c', '1', SRC, tmp], check=True)
    SRC = tmp
y, _ = librosa.load(SRC, sr=sr)
iv = librosa.effects.split(y, top_db=30, frame_length=2048, hop_length=512)
m = []
for a, b in iv:
    if m and (a - m[-1][1]) / sr < 0.35: m[-1][1] = b
    else: m.append([a, b])
m = [x for x in m if (x[1] - x[0]) / sr > 0.25]
pad = int(0.15 * sr)
pieces = [(max(0, a - pad), min(len(y), b + pad)) for a, b in m]
texts = [asr(y[a:b]) for a, b in pieces]
print('pieces', len(pieces))


def reread(y, a, b, ref):
    """A sentence read twice (restart without a long pause): try each pause inside it as the start
    of the last reading and keep the start whose text matches the sentence best."""
    best = (a, '', 9.9)
    gaps = librosa.effects.split(y[a:b], top_db=30, frame_length=1024, hop_length=256)
    for s0, _ in gaps[1:]:
        c = a + max(0, s0 - int(0.1 * sr))
        h = asr(y[c:b]); e = cer(h, ref)
        if e < best[2]: best = (c, h, e)
    return best


sent = list(range(FIRST, LAST + 1))
P, N, INF = len(pieces), len(sent), 1e9
dp = [[INF] * (N + 1) for _ in range(P + 1)]; bk = {}
dp[0][0] = 0
for i in range(P + 1):
    for j in range(N + 1):
        c = dp[i][j]
        if c >= INF: continue
        if i < P and c + len(texts[i]) + 2 < dp[i + 1][j]:          # piece is junk / a restart
            dp[i + 1][j] = c + len(texts[i]) + 2; bk[(i + 1, j)] = ('junk', i, j)
        if j < N and c + len(uly[sent[j]]) < dp[i][j + 1]:            # sentence not read
            dp[i][j + 1] = c + len(uly[sent[j]]); bk[(i, j + 1)] = ('skip', i, j)
        for k in range(i + 1, min(P, i + 4) + 1):                    # pieces i..k-1 = sentence j
            if j < N:
                h = ' '.join(texts[i:k]); r = uly[sent[j]]
                v = c + edit_distance(h, r)
                if v < dp[k][j + 1]: dp[k][j + 1] = v; bk[(k, j + 1)] = ('use', i, j)
st, res = (P, N), []
while st != (0, 0):
    kind, i, j = bk[st]; res.append((kind, i, st[0], j)); st = (i, j)
res.reverse()
os.makedirs(OUT, exist_ok=True)
print(f'== {FIRST}-{LAST}')
for kind, i, k, j in res:
    if kind == 'junk': print(f'     junk piece {i}: {texts[i]}'); continue
    n = sent[j]
    if kind == 'skip': print(f'{n:03d} NOT FOUND'); continue
    h = ' '.join(texts[i:k]); a, b = pieces[i][0], pieces[k - 1][1]
    e = cer(h, uly[n])
    if e > MAX_CER:
        a2, h2, e2 = reread(y, a, b, uly[n])
        if e2 <= MAX_CER:
            print(f'{n:03d} re-read: kept the last reading from {a2/sr:.1f}s (was CER {e:.0%})')
            a, h, e = a2, h2, e2
    if e <= MAX_CER: sf.write(os.path.join(OUT, f'{n:03d}.wav'), y[a:b], sr)
    print(f'{n:03d} CER {e:.0%} {a/sr:6.1f}-{b/sr:6.1f}s {"" if e <= MAX_CER else "LEFT OUT "}| {h}')
