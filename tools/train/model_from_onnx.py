"""
PyTorch re-implementation of the app's ONNX speech model, with weights loaded from the ONNX file.
This makes the *current* model trainable (fine-tuning), instead of starting from scratch.

Architecture (read from the ONNX graph, ~70M params):
  front-end : loudness → -18 dBFS, z-norm, STFT(1024, hop 200, 22050 Hz), 128 mel (fmax 8 kHz), 10·log10
  conv2d    : 1→32 (41×11, stride 2×2) → 32→32 (21×11, stride 2×1), Hardtanh(0, 20)    [BN folded into bias]
  gru1      : BiGRU 1024→256, directions summed
  cnn1      : Conv1d 256 (k11) + 4× residual blocks
  gru2      : BiGRU 256→384, directions summed
  cnn2      : residual conv blocks 384(k13) → 512(k17) → 640(k21) → 768(k25) → 1024(k1)
  out       : Conv1d 1024→34, LogSoftmax

usage:
  model = UyghurASR.from_onnx('uyghur_asr.onnx')   # fp32 model (git LFS object), not the int8 one
  logp = model(wav_22050)                          # [1, 34, T]   (same as the ONNX)
  model.export_onnx('new.onnx')
"""
import numpy as np
import onnx
import torch
import torch.nn as nn
from onnx import numpy_helper

ACT = lambda: nn.Hardtanh(0.0, 20.0)


class Res(nn.Module):
    """y = act(BN(conv(x) + x))  — conv's own BN is folded into its bias in the exported model."""

    def __init__(self, ch, k, drop=0.1):
        super().__init__()
        self.conv = nn.Conv1d(ch, ch, k, padding=k // 2)
        self.bn = nn.BatchNorm1d(ch)
        self.act = ACT()
        self.drop = nn.Dropout(drop)

    def forward(self, x):
        return self.drop(self.act(self.bn(self.conv(x) + x)))


def conv_act(cin, cout, k, drop=0.1):
    return nn.Sequential(nn.Conv1d(cin, cout, k, padding=k // 2), ACT(), nn.Dropout(drop))


class UyghurASR(nn.Module):
    def __init__(self, n_classes=34):
        super().__init__()
        self.register_buffer('mel_filters', torch.zeros(128, 513))
        self.register_buffer('window', torch.hann_window(1024))
        self.conv = nn.Sequential(
            nn.Conv2d(1, 32, (41, 11), stride=(2, 2), padding=(20, 5)), ACT(),
            nn.Conv2d(32, 32, (21, 11), stride=(2, 1), padding=(10, 5)), ACT(),
        )
        self.gru1 = nn.GRU(1024, 256, batch_first=True, bidirectional=True)
        self.cnn1 = nn.Sequential(conv_act(256, 256, 11), *[Res(256, 11) for _ in range(4)])
        self.gru2 = nn.GRU(256, 384, batch_first=True, bidirectional=True)
        self.cnn2 = nn.Sequential(
            *[Res(384, 13) for _ in range(3)],
            conv_act(384, 512, 17), Res(512, 17), Res(512, 17),
            conv_act(512, 640, 21), Res(640, 21),
            conv_act(640, 768, 25), Res(768, 25),
            conv_act(768, 1024, 1), Res(1024, 1),
        )
        self.outlayer = nn.Conv1d(1024, n_classes, 1)

    # ───────────── front-end (identical to the ONNX graph) ─────────────
    def logmel(self, wav):
        """wav [N] at 22050 Hz → log-mel [128, T]"""
        rms = torch.sqrt(torch.mean(wav * wav))
        db = 20.0 * torch.log10(rms)
        x = wav * torch.pow(10.0, (-18.0 - db) / 20.0)
        x = (x - x.mean()) / x.std()                                  # unbiased std, as in the graph
        spec = torch.stft(x, 1024, 200, 1024, self.window, center=True, pad_mode='reflect',
                          return_complex=False)                       # [513, T, 2]
        power = spec.pow(2).sum(-1)
        mel = torch.matmul(self.mel_filters, power)
        return 10.0 * torch.log10(torch.clamp(mel, min=1e-10))

    # ───────────── acoustic model ─────────────
    @staticmethod
    def _gru(gru, x, lens):
        if lens is None:
            y, _ = gru(x)
        else:
            packed = nn.utils.rnn.pack_padded_sequence(x, lens.cpu(), batch_first=True, enforce_sorted=False)
            y, _ = gru(packed)
            y, _ = nn.utils.rnn.pad_packed_sequence(y, batch_first=True, total_length=x.shape[1])
        h = gru.hidden_size
        return y[:, :, :h] + y[:, :, h:]

    def acoustic(self, feats, lens=None):
        """feats [B, 128, T] → log-probs [B, 34, T'], lens' (T' = ceil(T/2))"""
        x = self.conv(feats.unsqueeze(1))                       # [B, 32, 32, T']
        B, C, Fq, T = x.shape
        x = x.reshape(B, C * Fq, T).transpose(1, 2)             # [B, T', 1024]
        olens = None if lens is None else (lens + 1) // 2
        x = self._gru(self.gru1, x, olens).transpose(1, 2)
        x = self.cnn1(x).transpose(1, 2)
        x = self._gru(self.gru2, x, olens).transpose(1, 2)
        x = self.outlayer(self.cnn2(x))
        return torch.log_softmax(x, dim=1), olens

    def forward(self, wav):
        """[N] waveform → [1, 34, T]  (the app's ONNX contract)"""
        out, _ = self.acoustic(self.logmel(wav).unsqueeze(0))
        return out

    # ───────────── ONNX import / export ─────────────
    @classmethod
    def from_onnx(cls, path):
        g = onnx.load(path).graph
        W = {t.name: torch.from_numpy(numpy_helper.to_array(t).copy()) for t in g.initializer}
        convs = [n for n in g.node if n.op_type == 'Conv']
        grus = [n for n in g.node if n.op_type == 'GRU']
        bns = [n for n in g.node if n.op_type == 'BatchNormalization']
        stft = next(n for n in g.node if n.op_type == 'STFT')

        m = cls()
        m.mel_filters.copy_(W['mel_spectrogram.mel_filters'])
        m.window.copy_(W[stft.input[2]])

        tconvs = [mod for mod in m.modules() if isinstance(mod, (nn.Conv1d, nn.Conv2d))]
        assert len(tconvs) == len(convs), (len(tconvs), len(convs))
        for mod, node in zip(tconvs, convs):
            mod.weight.data.copy_(W[node.input[1]].reshape(mod.weight.shape))
            mod.bias.data.copy_(W[node.input[2]])

        tbns = [mod for mod in m.modules() if isinstance(mod, nn.BatchNorm1d)]
        assert len(tbns) == len(bns)
        for mod, node in zip(tbns, bns):
            mod.weight.data.copy_(W[node.input[1]]); mod.bias.data.copy_(W[node.input[2]])
            mod.running_mean.copy_(W[node.input[3]]); mod.running_var.copy_(W[node.input[4]])

        for gru, node in zip([m.gru1, m.gru2], grus):
            Wx, Wh, Bb = W[node.input[1]], W[node.input[2]], W[node.input[3]]   # ONNX gate order z,r,h
            H = gru.hidden_size

            def reorder(t):            # z,r,h → r,z,n (PyTorch)
                z, r, h = t[:H], t[H:2 * H], t[2 * H:]
                return torch.cat([r, z, h])

            for d, sfx in enumerate(['', '_reverse']):
                getattr(gru, 'weight_ih_l0' + sfx).data.copy_(reorder(Wx[d]))
                getattr(gru, 'weight_hh_l0' + sfx).data.copy_(reorder(Wh[d]))
                getattr(gru, 'bias_ih_l0' + sfx).data.copy_(reorder(Bb[d, :3 * H]))
                getattr(gru, 'bias_hh_l0' + sfx).data.copy_(reorder(Bb[d, 3 * H:]))
        return m.eval()

    def export_onnx(self, path):
        self.eval()
        dummy = torch.randn(22050 * 3) * 0.1
        torch.onnx.export(self, (dummy,), path, input_names=['input'], output_names=['output'],
                          dynamic_axes={'input': {0: 'length'}, 'output': {2: 'time'}},
                          opset_version=17, dynamo=False)
