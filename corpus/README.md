# Language-model text pool

Latin-script Uyghur (ULY) text used to build `app/src/main/assets/uyghur_char_lm.txt`. One line per sentence/word.

| File | Source | Lines |
|---|---|---|
| `thuyg20_train_text.txt` | THUYG-20 training transcripts | 5,735 |
| `dictionary_sentences.txt` | Definitions and example sentences of the Uyghur explanatory dictionary, [uyghur-language/data](https://github.com/uyghur-language/data) (Okyanus Uyghur Dictionary Data) | 44,606 |
| `dictionary_words.txt` | Head words and synonyms from the same repo | 81,980 |

Regenerate the dictionary files: `python tools/build_dictionary_corpus.py <path to uyghur-language/data> corpus_out`

Rebuild the LM asset:

```
cd tools
python export_char_lm.py ../app/src/main/assets/uyghur_char_lm.txt 6 2 \
  ../corpus/thuyg20_train_text.txt ../corpus/dictionary_sentences.txt ../corpus/dictionary_words.txt
```

Add any further Latin Uyghur text (one sentence per line) the same way.
