"""Quantize the fp32 ONNX model to int8 (static, per-channel, Conv layers only).

The mel front-end (MatMul on the power spectrum) must stay in float: quantizing it destroys the
model (WER ~99%). Conv-only static QDQ keeps accuracy (WER 11.6% -> 11.7% on THUYG-20 test)
while shrinking the file 279 MB -> 80 MB and making inference ~3x faster.

usage: python quantize_int8.py model_fp32.onnx model_int8.onnx calib_dir_with_wavs
"""
import sys, glob, random
import numpy as np, librosa
from onnxruntime.quantization import (quantize_static, CalibrationDataReader, QuantFormat,
                                      QuantType, CalibrationMethod)
from onnxruntime.quantization.shape_inference import quant_pre_process

src, dst, calib_dir = sys.argv[1:4]
wavs = sorted(glob.glob(calib_dir + '/**/*.wav', recursive=True))
random.Random(0).shuffle(wavs)

class Reader(CalibrationDataReader):
    def __init__(self): self.it = iter(wavs[:40])
    def get_next(self):
        f = next(self.it, None)
        if f is None: return None
        y, _ = librosa.load(f, sr=22050)
        return {'input': y.astype(np.float32)}

quant_pre_process(src, dst + '.pre.onnx', skip_symbolic_shape=True)
quantize_static(dst + '.pre.onnx', dst, Reader(), quant_format=QuantFormat.QDQ, per_channel=True,
                activation_type=QuantType.QUInt8, weight_type=QuantType.QInt8,
                op_types_to_quantize=['Conv'], calibrate_method=CalibrationMethod.MinMax)
print('written', dst)
