"""Build next-word (bigram) files for the Turkish and English keyboards.

Outputs (app/src/main/assets/):
  turkish_bigrams.txt  word1 word2<TAB>count
  english_bigrams.txt  word1 word2<TAB>count

Source: Tatoeba sentences (CC BY 2.0 FR, https://tatoeba.org/en/downloads)
  https://downloads.tatoeba.org/exports/per_language/tur/tur_sentences.tsv.bz2
  https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2

Words are split and lower-cased the way the app does it (TextContext.kt): a word is letters plus
apostrophes; punctuation breaks the context, so no pair spans a comma or a sentence end.
Only pairs whose two words are in the shipped dictionary (turkish_dict.txt / english_dict.txt) are kept.

usage: python build_latin_bigrams.py <tur_sentences.tsv.bz2> <eng_sentences.tsv.bz2>
"""
import bz2
import collections
import os
import re
import sys

OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets')
MIN_COUNT = 2          # pairs seen once are mostly noise
MAX_FOLLOWERS = 12     # per first word, to keep the APK small
# Tatoeba's stock example names; they would otherwise be predicted after almost every verb
CORPUS_NAMES = {'tom', 'mary', "tom's", "mary's"}

# a run of word characters, or a single character that is not a space
TOKEN = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*|\S")


def turkish_lower(s):
    return s.replace('I', 'ı').replace('İ', 'i').lower()


def english_lower(s):
    return s.lower()


def load_dictionary(name):
    with open(os.path.join(OUT, name), encoding='utf-8') as f:
        return {line.split('\t', 1)[0] for line in f if '\t' in line}


def count_pairs(path, lower, vocab):
    pairs = collections.Counter()
    with bz2.open(path, 'rt', encoding='utf-8') as f:
        for line in f:
            parts = line.rstrip('\n').split('\t')
            if len(parts) < 3:
                continue
            prev = None
            for tok in TOKEN.findall(parts[2]):
                if not tok[0].isalpha():
                    prev = None                     # punctuation / digits: no context
                    continue
                w = lower(tok.replace('’', "'"))
                if prev is not None and prev in vocab and w in vocab:
                    pairs[(prev, w)] += 1
                prev = w
    return pairs


def prune(pairs):
    by_first = collections.defaultdict(list)
    for (a, b), c in pairs.items():
        if c >= MIN_COUNT and a not in CORPUS_NAMES and b not in CORPUS_NAMES:
            by_first[a].append((c, b))
    kept = {}
    for a, followers in by_first.items():
        for c, b in sorted(followers, key=lambda cb: (-cb[0], cb[1]))[:MAX_FOLLOWERS]:
            kept[(a, b)] = c
    return kept


def write(name, pairs):
    with open(os.path.join(OUT, name), 'w', encoding='utf-8') as o:
        for (a, b), c in sorted(pairs.items()):
            o.write(f'{a} {b}\t{c}\n')
    print(name, len(pairs), 'pairs,', len({a for a, _ in pairs}), 'first words')


def main(tur_path, eng_path):
    for path, dict_name, out_name, lower in (
        (tur_path, 'turkish_dict.txt', 'turkish_bigrams.txt', turkish_lower),
        (eng_path, 'english_dict.txt', 'english_bigrams.txt', english_lower),
    ):
        vocab = load_dictionary(dict_name)
        write(out_name, prune(count_pairs(path, lower, vocab)))


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
