#!/usr/bin/env python3
"""Build deterministic title-search terms for the Gatling catalogue feeder."""

import csv
import gzip
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BOOK_UUIDS = ROOT / "src/gatling/resources/data/book_uuids.csv"
BOOK_SEED = ROOT / "src/main/resources/db/seed/books.csv.gz"
OUTPUT = ROOT / "src/gatling/resources/data/book_search_terms.csv"

WORD = re.compile(r"[A-Za-z0-9]+(?:['’][A-Za-z0-9]+)?")
STOP_WORDS = {
    "about", "after", "against", "before", "from", "into", "over", "than",
    "that", "their", "then", "these", "this", "through", "under", "what",
    "when", "where", "which", "with", "your",
}


def search_term(title: str, book_uuid: str) -> str:
    candidates = [
        word
        for word in WORD.findall(title)
        if len(word) >= 4 and word.lower() not in STOP_WORDS
    ]
    if not candidates:
        candidates = WORD.findall(title)
    if not candidates:
        raise ValueError(f"book {book_uuid} has no searchable title token: {title!r}")

    # UUID-derived selection keeps output stable while avoiding a feeder made entirely of the
    # first word in every title (which is commonly just "The").
    return candidates[int(book_uuid.replace("-", "")[-8:], 16) % len(candidates)]


def main() -> None:
    with BOOK_UUIDS.open(newline="", encoding="utf-8") as source:
        ordered_uuids = [row["bookUuid"] for row in csv.DictReader(source)]

    wanted = set(ordered_uuids)
    titles: dict[str, str] = {}
    with gzip.open(BOOK_SEED, mode="rt", newline="", encoding="utf-8") as source:
        for row in csv.DictReader(source):
            book_uuid = row["book_uuid"]
            if book_uuid in wanted:
                titles[book_uuid] = row["book_title"]

    missing = wanted.difference(titles)
    if missing:
        raise ValueError(f"{len(missing)} Gatling book UUID(s) are absent from the seed")

    with OUTPUT.open(mode="w", newline="", encoding="utf-8") as target:
        writer = csv.DictWriter(target, fieldnames=["bookSearchTerm"])
        writer.writeheader()
        for book_uuid in ordered_uuids:
            writer.writerow({"bookSearchTerm": search_term(titles[book_uuid], book_uuid)})

    print(f"wrote {len(ordered_uuids)} title search terms to {OUTPUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
