"""
Fine-tuning helpers for the Uyghur keyboard speech model (see TRAINING.md).

The model is the app's own ONNX model, rebuilt in PyTorch by model_from_onnx.UyghurASR, so training
continues from the current weights. The exported ONNX keeps the app's contract:
    input  "input":  float32 waveform at 22050 Hz, shape [length]
    output "output": log-softmax, shape [1, 34, time]
"""
import random, re, time
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

SAMPLE_RATE = 22050
VOCAB = ['<pad>', '<sos>', '<eos>'] + list("abcdefghijklmnopqrstuvwxyz éöü’")   # index 0 = CTC blank
CHAR2IDX = {c: i for i, c in enumerate(VOCAB) if i > 2}


# ─────────────────────────── text: Uyghur Arabic (UEY) → Latin (ULY) ───────────────────────────
_UEY2ULY = {
    'ا': 'a', 'ە': 'e', 'ې': 'é', 'ى': 'i', 'و': 'o', 'ۆ': 'ö', 'ۇ': 'u', 'ۈ': 'ü',
    'ب': 'b', 'پ': 'p', 'ت': 't', 'ج': 'j', 'چ': 'ch', 'خ': 'x', 'د': 'd', 'ر': 'r', 'ز': 'z',
    'ژ': 'zh', 'س': 's', 'ش': 'sh', 'غ': 'gh', 'ف': 'f', 'ق': 'q', 'ك': 'k', 'گ': 'g', 'ڭ': 'ng',
    'ل': 'l', 'م': 'm', 'ن': 'n', 'ھ': 'h', 'ۋ': 'w', 'ي': 'y', 'ه': 'h', 'ی': 'y',
}


def uey_to_uly(text: str) -> str:
    """Arabic-script Uyghur (Common Voice) → ULY Latin, the style of the model's training data.
    Tested: ULY→UEY→ULY round-trip is exact for 97% of THUYG-20 sentences (rest: optional apostrophes)."""
    out = []
    for word in re.split(r'\s+', text.strip()):
        w = ''
        for ch in word:
            if ch == 'ئ':
                if w:                      # word-internal hemze → apostrophe (mu’ellim)
                    w += '’'
                continue
            lat = _UEY2ULY.get(ch)
            if lat is None:
                continue                   # punctuation, digits, foreign letters
            if w and lat[0] in 'gh' and (w[-1] + lat[0]) in ('ng', 'sh', 'zh', 'gh', 'ch'):
                w += '’'                   # n’g, s’h … so they are not read as one letter
            w += lat
        if w:
            out.append(w)
    return ' '.join(out)


def normalize_uly(text: str) -> str:
    text = text.lower().replace("'", '’').replace('`', '’')
    text = re.sub(r'[-,.!?;:"“”«»()]', ' ', text)
    text = ''.join(c for c in text if c in CHAR2IDX)
    return ' '.join(text.split())


def encode(text: str):
    return [CHAR2IDX[c] for c in normalize_uly(text)]


# ─────────────────────────── augmentation ───────────────────────────
def augment_wave(wav: np.ndarray) -> np.ndarray:
    """Phone-like conditions: background noise, speed. Important for real microphone input."""
    if random.random() < 0.5:
        rms = np.sqrt((wav ** 2).mean() + 1e-9)
        snr_db = random.uniform(8, 30)
        wav = wav + np.random.randn(len(wav)).astype(np.float32) * rms / (10 ** (snr_db / 20))
    if random.random() < 0.3:
        factor = random.uniform(0.9, 1.1)
        idx = np.arange(0, len(wav) - 1, factor)
        wav = np.interp(idx, np.arange(len(wav)), wav).astype(np.float32)
    return wav


def spec_augment(feats, lens, max_t=30, max_f=12, n_t=2, n_f=2):
    feats = feats.clone()
    for b in range(feats.shape[0]):
        T = int(lens[b])
        fill = feats[b, :, :T].mean()
        for _ in range(n_f):
            f = random.randint(0, max_f); f0 = random.randint(0, 128 - f)
            feats[b, f0:f0 + f, :T] = fill
        for _ in range(n_t):
            t = random.randint(0, min(max_t, T // 6)); t0 = random.randint(0, max(0, T - t))
            feats[b, :, t0:t0 + t] = fill
    return feats


# ─────────────────────────── data ───────────────────────────
class SpeechSet(torch.utils.data.Dataset):
    """items: list of (audio_path, uly_text). Any audio format/rate (mp3 ok); resampled to 22050."""

    def __init__(self, items, train=True, max_sec=12.0):
        self.items = [(p, t) for p, t in items if encode(t)]
        self.train, self.max_len = train, int(max_sec * SAMPLE_RATE)

    def __len__(self):
        return len(self.items)

    def __getitem__(self, i):
        import librosa
        path, text = self.items[i]
        try:
            wav, _ = librosa.load(path, sr=SAMPLE_RATE)
        except Exception:
            wav, text = np.zeros(SAMPLE_RATE, dtype=np.float32), ''
        wav = wav[:self.max_len].astype(np.float32)
        if self.train:
            wav = augment_wave(wav)
        return torch.from_numpy(wav), torch.tensor(encode(text), dtype=torch.long)


def collate(batch):
    batch = [(w, l) for w, l in batch if len(l) > 0 and len(w) > SAMPLE_RATE // 4]
    wavs, labels = zip(*batch)
    return list(wavs), torch.cat(labels), torch.tensor([len(l) for l in labels])


def batch_features(model, wavs, device):
    """Per-utterance log-mel (normalisation is per utterance), padded to [B, 128, T]."""
    feats = [model.logmel(w.to(device)) for w in wavs]
    lens = torch.tensor([f.shape[1] for f in feats])
    out = torch.empty(len(feats), 128, int(lens.max()), device=device)
    for i, f in enumerate(feats):
        out[i, :, :f.shape[1]] = f
        out[i, :, f.shape[1]:] = f.min()
    return out, lens


# ─────────────────────────── metrics ───────────────────────────
def greedy_decode(logp, lens):
    res, ids = [], logp.argmax(1).cpu().numpy()
    for b, n in enumerate(lens.tolist()):
        prev, s = -1, []
        for i in ids[b, :n]:
            if i > 2 and i != prev:
                s.append(VOCAB[i])
            prev = i
        res.append(' '.join(''.join(s).split()))
    return res


def edit_distance(a, b):
    d = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        prev, d[0] = d[:], i
        for j, y in enumerate(b, 1):
            d[j] = min(prev[j] + 1, d[j - 1] + 1, prev[j - 1] + (x != y))
    return d[-1]


@torch.no_grad()
def evaluate(model, loader, device, max_batches=None):
    was_training = model.training
    model.eval()
    ce = cn = we = wn = 0
    for k, (wavs, labels, llen) in enumerate(loader):
        if max_batches and k >= max_batches:
            break
        feats, flen = batch_features(model, wavs, device)
        out, olen = model.acoustic(feats, flen)
        off = 0
        for h, n in zip(greedy_decode(out, olen), llen.tolist()):
            ref = ''.join(VOCAB[i] for i in labels[off:off + n].tolist()); off += n
            ce += edit_distance(h, ref); cn += len(ref)
            we += edit_distance(h.split(), ref.split()); wn += len(ref.split())
    model.train(was_training)
    return ce / max(cn, 1), we / max(wn, 1)


# ─────────────────────────── training ───────────────────────────
def train(model, train_loader, dev_loader, device, epochs=5, lr=5e-5, out_path='finetuned.pt',
          eval_batches=60, log=print, max_minutes=None):
    """Fine-tunes all layers with a small learning rate. Keeps the best (dev CER) weights."""
    model.to(device).train()
    opt = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=1e-6)
    steps = max(1, epochs * len(train_loader))
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=lr, total_steps=steps, pct_start=0.05)
    use_amp = device.type == 'cuda'
    scaler = torch.amp.GradScaler(enabled=use_amp)
    cer, wer = evaluate(model, dev_loader, device, eval_batches)
    log(f'before training: dev CER {cer:.2%}  WER {wer:.2%}')
    best, t0 = cer, time.time()
    torch.save(model.state_dict(), out_path)
    for ep in range(epochs):
        tot, n = 0.0, 0
        for it, (wavs, labels, llen) in enumerate(train_loader):
            with torch.no_grad():
                feats, flen = batch_features(model, wavs, device)
                feats = spec_augment(feats, flen)
            with torch.autocast(device.type, enabled=use_amp):
                out, olen = model.acoustic(feats, flen)
            loss = F.ctc_loss(out.float().permute(2, 0, 1), labels.to(device), olen.to(device),
                              llen.to(device), blank=0, reduction='mean', zero_infinity=True)
            opt.zero_grad(set_to_none=True)
            scaler.scale(loss).backward()
            scaler.unscale_(opt)
            nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            scaler.step(opt); scaler.update(); sched.step()
            tot += loss.item(); n += 1
            if it % 100 == 0:
                log(f'epoch {ep + 1} step {it}/{len(train_loader)} loss {loss.item():.3f}')
            if max_minutes and time.time() - t0 > max_minutes * 60:
                break
        cer, wer = evaluate(model, dev_loader, device, eval_batches)
        log(f'== epoch {ep + 1}: loss {tot / max(n, 1):.3f}  dev CER {cer:.2%}  WER {wer:.2%}')
        if cer < best:
            best = cer
            torch.save(model.state_dict(), out_path)
            log(f'   saved {out_path}')
        if max_minutes and time.time() - t0 > max_minutes * 60:
            log('time limit reached'); break
    model.load_state_dict(torch.load(out_path, map_location=device, weights_only=True))
    return best
