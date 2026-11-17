# Assets

- `uyghur_asr_int8.onnx` — Uyghur CTC speech model (gheyret/uyghur-asr-ctc, THUYG-20), int8-quantized
  with `tools/quantize_int8.py`. Stored in Git LFS. Input: raw 22050 Hz float waveform. Output: [1, 34, T] log-probs.
- `uyghur_char_lm.txt` — character 6-gram LM over Uyghur Latin text, built with `tools/export_char_lm.py`.
