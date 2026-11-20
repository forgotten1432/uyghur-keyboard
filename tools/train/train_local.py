"""Small fine-tune on a Mac (Apple GPU / MPS) or CPU: THUYG-20 + your own recordings.

Same recipe as finetune_colab.ipynb, without Common Voice. Your own recordings are the point of a
local run: the model gets used to your voice and your phone's microphone. 10% of them are kept
aside (never trained on) to measure honestly whether the new model is better for you.

Own recordings: a folder with audio files (m4a/mp3/wav) and kendi_sesim.tsv, one line per file:
    file_name<TAB>sentence          (sentence in Arabic or Latin script)

usage:
  python train_local.py --thuyg <dir with thuyg20_data/, thuyg20_train.csv, thuyg20_test.csv>
                        [--own <recordings dir>] [--out <work dir>] [--minutes 120] [--thuyg-n 3000]

Outputs in --out: finetuned.pt (best checkpoint, training resumes from it), uyghur_asr_fp32_new.onnx,
uyghur_asr_int8.onnx (the file the app uses) and test_*.tsv (held-out lists for evaluate.py).
"""
import argparse
import glob
import os
import random
import subprocess
import sys

import torch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from model_from_onnx import UyghurASR                      # noqa: E402
from uyghur_train import SpeechSet, collate, normalize_uly, train, uey_to_uly   # noqa: E402

REPO = os.path.normpath(os.path.join(HERE, '..', '..'))
OWN_REPEAT = 5            # own recordings count 5x in the mix (as in the notebook)
OWN_TEST_SHARE = 0.1
BATCH = 8


def thuyg_items(root, csv_name):
    out = []
    for line in open(os.path.join(root, csv_name), encoding='utf-8-sig'):
        if '\t' not in line:
            continue
        p, t = line.rstrip('\n').split('\t')[:2]
        out.append((os.path.join(root, 'thuyg20_data', p.replace('\\', '/').split('/')[-1]), normalize_uly(t)))
    return [(p, t) for p, t in out if t and os.path.exists(p)]


def own_items(folder):
    tsv = os.path.join(folder, 'kendi_sesim.tsv')
    out, missing = [], 0
    for line in open(tsv, encoding='utf-8'):
        if '\t' not in line:
            continue
        f, t = line.rstrip('\n').split('\t', 1)
        arabic = any('؀' <= c <= 'ۿ' for c in t)
        t = normalize_uly(uey_to_uly(t) if arabic else t)
        path = os.path.join(folder, f.strip())
        if not os.path.exists(path):          # 001.m4a listed but recorded as 001.mp3 / 001.wav …
            stem = os.path.splitext(path)[0]
            path = next((p for p in sorted(glob.glob(glob.escape(stem) + '.*')) if not p.endswith('.tsv')), path)
        if t and os.path.exists(path):
            out.append((path, t))
        else:
            missing += 1
    print(f'own recordings: {len(out)} found, {missing} sentences not recorded (yet)')
    return out


def write_tsv(path, items):
    with open(path, 'w', encoding='utf-8') as o:
        for p, t in items:
            o.write(f'{p}\t{t}\n')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--thuyg', required=True)
    ap.add_argument('--own')
    ap.add_argument('--out', default=os.path.join(REPO, 'model', 'local_run'))
    ap.add_argument('--minutes', type=float, default=120)
    ap.add_argument('--thuyg-n', type=int, default=3000, help='THUYG sentences mixed in (keeps old skills)')
    ap.add_argument('--epochs', type=int, default=3)
    ap.add_argument('--lr', type=float, default=3e-5)
    a = ap.parse_args()
    a.out = os.path.abspath(a.out)
    os.makedirs(a.out, exist_ok=True)

    device = torch.device('mps' if torch.backends.mps.is_available() else 'cpu')
    thuyg_train, thuyg_test = thuyg_items(a.thuyg, 'thuyg20_train.csv'), thuyg_items(a.thuyg, 'thuyg20_test.csv')
    own = own_items(a.own) if a.own else []
    random.Random(0).shuffle(own)
    n_test = max(1, int(len(own) * OWN_TEST_SHARE)) if own else 0
    own_test, own_train = own[:n_test], own[n_test:]
    print(f'device {device} | THUYG train {len(thuyg_train)} test {len(thuyg_test)} | own train {len(own_train)} test {len(own_test)}')

    train_items = random.Random(1).sample(thuyg_train, min(a.thuyg_n, len(thuyg_train))) + own_train * OWN_REPEAT
    random.Random(2).shuffle(train_items)
    dev_items = random.Random(3).sample(thuyg_test, min(200, len(thuyg_test))) + own_test * 3
    write_tsv(os.path.join(a.out, 'test_thuyg.tsv'), random.Random(9).sample(thuyg_test, min(300, len(thuyg_test))))
    if own_test:
        write_tsv(os.path.join(a.out, 'test_own.tsv'), own_test)

    model = UyghurASR.from_onnx(os.path.join(REPO, 'model', 'uyghur_asr_fp32.onnx'))
    ckpt = os.path.join(a.out, 'finetuned.pt')
    if os.path.exists(ckpt):
        model.load_state_dict(torch.load(ckpt, map_location='cpu', weights_only=True))
        print('resumed from', ckpt)
    loader = lambda items, tr: torch.utils.data.DataLoader(SpeechSet(items, train=tr), batch_size=BATCH,
                                                           shuffle=tr, collate_fn=collate, num_workers=2)
    best = train(model, loader(train_items, True), loader(dev_items, False), device, epochs=a.epochs,
                 lr=a.lr, out_path=ckpt, eval_batches=40, max_minutes=a.minutes)
    print('best dev CER', best)

    model.cpu().eval()
    fp32 = os.path.join(a.out, 'uyghur_asr_fp32_new.onnx')
    model.export_onnx(fp32)
    calib = os.path.join(a.out, 'calib')
    os.makedirs(calib, exist_ok=True)
    import librosa
    import soundfile as sf
    for i, (p, _) in enumerate(dev_items[:40]):
        y, _ = librosa.load(p, sr=22050)
        sf.write(os.path.join(calib, f'{i}.wav'), y, 22050)
    subprocess.run([sys.executable, 'quantize_int8.py', fp32, os.path.join(a.out, 'uyghur_asr_int8.onnx'), calib],
                   cwd=os.path.join(REPO, 'tools'), check=True)
    print('done:', os.path.join(a.out, 'uyghur_asr_int8.onnx'))


if __name__ == '__main__':
    main()
