"""Build the keyboard's word-prediction dictionaries.

Outputs (app/src/main/assets/):
  uyghur_dict.txt     word<TAB>frequency, sorted by word (Uyghur Arabic script)
  uyghur_bigrams.txt  word1 word2<TAB>count   (next-word prediction)
  english_dict.txt    word<TAB>frequency, sorted by word
  turkish_dict.txt    word<TAB>frequency, sorted by word

Sources:
  - Uyghur-LRs/Uyghur-Wordlist (CC BY 4.0, https://github.com/Uyghur-LRs/Uyghur-Wordlist):
      wordlist-Internet (2.3M words with web frequencies, contains misspellings)
      Sozler_all_in_one.txt + lexicon.dic (spelling word lists) -> used to filter misspellings
  - THUYG-20 transcripts (Latin, converted to Arabic script) -> bigrams
  - wordfreq (Apache-2.0 code; data CC BY-SA 4.0) -> English, Turkish

usage: python build_dictionary.py <Uyghur-Wordlist dir> <thuyg csv> [<thuyg csv> ...]
"""
import sys, os, re, zipfile, io, collections, math

OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets')
N_UYGHUR = 150_000
N_ENGLISH = 30_000
N_TURKISH = 50_000

LETTERS = set('ابپتجچخدرزژسشغفقكگڭلمنھوۇۆۈۋېىيەئ')
VOWELS = set('اەېىوۇۆۈ')


def well_formed(w):
    """Spelling sanity checks for Uyghur Arabic script."""
    if not (2 <= len(w) <= 30) or any(c not in LETTERS for c in w):
        return False
    if not any(c in VOWELS for c in w):      # abbreviations / junk without any vowel
        return False
    if w[0] in VOWELS:                       # a word never starts with a bare vowel letter
        return False
    for i, c in enumerate(w):
        if c == 'ئ':                          # hemze must carry a vowel ...
            if i + 1 >= len(w) or w[i + 1] not in VOWELS:
                return False
            if i > 0 and w[i - 1] not in VOWELS:   # ... and inside a word follows a vowel
                return False
    if re.search(r'(.)\1\1', w):             # no letter three times in a row
        return False
    return True


# ── Latin (ULY) → Arabic (UEY), same rules as the app's UyghurScript.kt ──
L2A = {"a": "ا", "e": "ە", "é": "ې", "i": "ى", "o": "و", "ö": "ۆ", "u": "ۇ", "ü": "ۈ", "b": "ب", "p": "پ",
       "t": "ت", "j": "ج", "ch": "چ", "x": "خ", "d": "د", "r": "ر", "z": "ز", "zh": "ژ", "s": "س", "sh": "ش",
       "gh": "غ", "g": "گ", "f": "ف", "q": "ق", "k": "ك", "ng": "ڭ", "l": "ل", "m": "م", "n": "ن", "h": "ھ",
       "w": "ۋ", "v": "ۋ", "y": "ي"}
DIG = {"ch", "gh", "sh", "zh", "ng"}
LV = set("a e é i o ö u ü".split())


def uly_to_uey(text):
    out = []
    for w in text.lower().replace("'", "’").split():
        s, i, pv, br = '', 0, False, True
        while i < len(w):
            if w[i] == '’':
                br = True; i += 1; continue
            tok = w[i:i + 2] if w[i:i + 2] in DIG else w[i]
            i += len(tok)
            v = tok in LV
            if v and (br or pv): s += 'ئ'
            s += L2A.get(tok, '')
            pv, br = v, False
        if s: out.append(s)
    return out


def main(wl_dir, thuyg_csvs):
    # 1. spelling word lists
    valid = set()
    for name in ('Sozler_all_in_one.txt', 'lexicon.dic'):
        with open(os.path.join(wl_dir, name), encoding='utf-8-sig', errors='ignore') as f:
            for line in f:
                w = line.split('\t')[0].strip().lstrip('﻿')
                if w: valid.add(w)
    # 2. THUYG sentences (unique) → Arabic
    sents = set()
    for csv in thuyg_csvs:
        for line in open(csv, encoding='utf-8-sig'):
            sents.add(line.rstrip('\n').split('\t')[-1])
    thuyg_words, bigrams = collections.Counter(), collections.Counter()
    for s in sents:
        ws = uly_to_uey(s)
        thuyg_words.update(ws)
        bigrams.update(zip(ws, ws[1:]))
    # 3. web frequencies
    with zipfile.ZipFile(os.path.join(wl_dir, 'wordlist-Internet-v0.1.zip')) as z:
        data = z.read('Unique_word_List_Internet.txt').decode('utf-8', errors='ignore')
    freq = {}
    for line in data.splitlines():
        p = line.split(',')
        if len(p) != 3: continue
        w, f, df = p[0].strip().lstrip('﻿'), int(p[1] or 0), int(p[2] or 0)
        if not well_formed(w): continue
        if w in valid or w in thuyg_words or df >= 30:
            freq[w] = f
    for w, c in thuyg_words.items():
        if well_formed(w):
            freq[w] = freq.get(w, 0) + c
    top = sorted(freq.items(), key=lambda kv: -kv[1])[:N_UYGHUR]
    with open(os.path.join(OUT, 'uyghur_dict.txt'), 'w', encoding='utf-8') as o:
        for w, f in sorted(top):
            o.write(f'{w}\t{max(f, 1)}\n')
    kept = {w for w, _ in top}
    with open(os.path.join(OUT, 'uyghur_bigrams.txt'), 'w', encoding='utf-8') as o:
        for (a, b), c in sorted(bigrams.items()):
            if a in kept and b in kept:
                o.write(f'{a} {b}\t{c}\n')
    print('uyghur words', len(top), 'min freq', top[-1][1], '| bigrams', sum(1 for (a, b) in bigrams if a in kept and b in kept))

    # 4. English
    import wordfreq
    words = [w for w in wordfreq.top_n_list('en', N_ENGLISH * 2) if re.fullmatch(r"[a-z]+(?:'[a-z]+)?", w)][:N_ENGLISH]
    with open(os.path.join(OUT, 'english_dict.txt'), 'w', encoding='utf-8') as o:
        for w in sorted(words):
            o.write(f'{w}\t{max(1, int(round(10 ** wordfreq.zipf_frequency(w, "en"))))}\n')
    print('english words', len(words))

    # 5. Turkish (lower-case with Turkish rules: I → ı, İ → i)
    tr_words = [w for w in wordfreq.top_n_list('tr', N_TURKISH * 2) if re.fullmatch(r"[abcçdefgğhıijklmnoöprsştuüvyzâîû]+", w)][:N_TURKISH]
    with open(os.path.join(OUT, 'turkish_dict.txt'), 'w', encoding='utf-8') as o:
        for w in sorted(tr_words):
            o.write(f'{w}\t{max(1, int(round(10 ** wordfreq.zipf_frequency(w, "tr"))))}\n')
    print('turkish words', len(tr_words))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2:])
