"""Extract Latin-script (ULY) text lines from the uyghur-language/data dictionaries.

usage: python build_corpus.py DATA_DIR OUT_DIR
Writes:
  sentences.txt  definition + example sentences (one per line, ULY)
  words.txt      head words + synonyms (one per line, ULY)
"""
import glob, json, os, re, sys

_UEY2ULY = {
    'ا': 'a', 'ە': 'e', 'ې': 'é', 'ى': 'i', 'و': 'o', 'ۆ': 'ö', 'ۇ': 'u', 'ۈ': 'ü',
    'ب': 'b', 'پ': 'p', 'ت': 't', 'ج': 'j', 'چ': 'ch', 'خ': 'x', 'د': 'd', 'ر': 'r', 'ز': 'z',
    'ژ': 'zh', 'س': 's', 'ش': 'sh', 'غ': 'gh', 'ف': 'f', 'ق': 'q', 'ك': 'k', 'گ': 'g', 'ڭ': 'ng',
    'ل': 'l', 'م': 'm', 'ن': 'n', 'ھ': 'h', 'ۋ': 'w', 'ي': 'y', 'ه': 'h', 'ی': 'y',
}
VOC = set("abcdefghijklmnopqrstuvwxyz éöü’")


def uey_to_uly(text):
    # same rules as tools/train/uyghur_train.py::uey_to_uly
    out = []
    for word in re.split(r'\s+', text.strip()):
        w = ''
        for ch in word:
            if ch == 'ئ':
                if w:
                    w += '’'
                continue
            lat = _UEY2ULY.get(ch)
            if lat is None:
                continue
            if w and lat[0] in 'gh' and (w[-1] + lat[0]) in ('ng', 'sh', 'zh', 'gh', 'ch'):
                w += '’'
            w += lat
        if w:
            out.append(w)
    return ' '.join(out)


NONLETTER = re.compile(r'[^\u0621-\u063f\u0641-\u06ff\s]|[\u060c\u061b\u061f]')


def norm(text):
    text = uey_to_uly(NONLETTER.sub(' ', text)).lower().replace("'", '’')
    text = ''.join(c for c in text if c in VOC)
    return ' '.join(text.split())


TAG = re.compile(r'\[[^\]]*:\]')          # [يەشمىسى:] [مىسال:] [سۆز تۈركۈمى:] ...
STRUCT = re.compile(r'〕[^〔]*〔')          # 〕ئاۋۇ+بايا〔 structure notes
SPLIT = re.compile(r'[.!?؟;:\n]|\[CRLF\]|…')


def main(data, out):
    os.makedirs(out, exist_ok=True)
    sents, words = [], []
    for f in sorted(glob.glob(os.path.join(data, 'uyghur-dictionary', '*.json'))):
        for e in json.load(open(f, encoding='utf-8'))['Entries']:
            w = norm(e.get('word') or '')
            if w:
                words.append(w)
            d = e.get('definition') or ''
            d = d[len(e.get('word') or ''):] if d.startswith(e.get('word') or '\0') else d
            d = STRUCT.sub(' ', d)
            d = TAG.sub('.', d)
            for part in SPLIT.split(d):
                s = norm(part)
                if len(s.split()) >= 3:
                    sents.append(s)
    for f in sorted(glob.glob(os.path.join(data, 'uyghur-synonyms', '*.json'))):
        for e in json.load(open(f, encoding='utf-8'))['Entries']:
            for t in [e.get('word')] + list(e.get('synonym') or []):
                s = norm(t or '')
                if s:
                    words.append(s)
    # head words that still carry the Roman numeral homonym marker (ئاⅠ) were stripped to letters by norm()
    for name, lines in (('sentences.txt', sents), ('words.txt', words)):
        seen, uniq = set(), []
        for s in lines:
            if s not in seen:
                seen.add(s); uniq.append(s)
        open(os.path.join(out, name), 'w', encoding='utf-8').write('\n'.join(uniq) + '\n')
        print(name, len(lines), '->', len(uniq))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
