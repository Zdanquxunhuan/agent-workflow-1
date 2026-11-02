"""Deterministic Chinese segmentation; no model output is interpreted as tsquery syntax."""
import hashlib
import logging
import re
import unicodedata
from functools import lru_cache
from pathlib import Path

import jieba

WORDS = Path(__file__).with_name("domain_words.txt")
# Changes to vocabulary/normalization require a new profile and a backfill.
PROFILE = "jieba-0.42.1-search-nfkc-v2-" + hashlib.sha256(WORDS.read_bytes()).hexdigest()[:12]
TERM = re.compile(r"^[\w]+$", re.UNICODE)
IDENTIFIER = re.compile(r"(?<![a-z0-9_-])[a-z0-9]+(?:[-_][a-z0-9]+)*(?![a-z0-9_-])")
STOP = frozenset("的 了 呢 吗 啊 呀 是 和 与 及 或 在 我 你 请 请问 怎么 什么 如何 是否 可以 需要".split())


def normalize(text):
    return unicodedata.normalize("NFKC", text).casefold().strip()


@lru_cache(maxsize=1)
def tokenizer():
    jieba.setLogLevel(logging.WARNING)
    value = jieba.Tokenizer()
    value.initialize()
    value.load_userdict(str(WORDS))
    return value


def terms(text):
    return [word for word in tokenizer().cut_for_search(normalize(text), HMM=False)
            if TERM.fullmatch(word) and word not in STOP and len(word.encode()) < 512]


def identifiers(text):
    return sorted({match.group() for match in IDENTIFIER.finditer(normalize(text))
                   if any(c.isdigit() for c in match.group()) and any(c.isalpha() for c in match.group())
                   and len(match.group()) <= 80})


def index_payload(text):
    return " ".join(terms(text)), identifiers(text)


def query_expression(text):
    # OR improves recall; ts_rank_cd and the reranker decide relevance. Each term
    # is quoted and parameter-bound. Operators in user input cannot become SQL/tsquery.
    values = list(dict.fromkeys(terms(text)))[:64]
    return " | ".join("'" + word.replace("'", "''").replace("\\", "\\\\") + "'" for word in values)
