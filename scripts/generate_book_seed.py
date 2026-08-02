#!/usr/bin/env python3
"""Generate the gzipped, normalized seed files loaded by the V3 Flyway migration.

Input : archive/BooksDatasetClean.csv  (Kaggle books dataset; not tracked in git)
Output: src/main/resources/db/seed/{categories,authors,books,book_authors}.csv.gz

Run from the repo root:  python scripts/generate_book_seed.py

All four files share pre-generated UUIDs, so ALWAYS regenerate them together.
See memory/book-catalog-seed-pipeline.md for the rationale behind the data decisions.
"""
import csv, re, gzip, uuid
from collections import Counter

SRC = "archive/BooksDatasetClean.csv"
OUT = "src/main/resources/db/seed"

MONTHS = {m.lower(): i for i, m in enumerate(
    ["January", "February", "March", "April", "May", "June", "July", "August",
     "September", "October", "November", "December"], 1)}
ROLE = re.compile(r'\s*\((?:[A-Z]{2,4})\)\s*$')          # trailing role code: (COM),(EDT),(ILT)...

# Required fields — a row missing any of these is dropped (description/category may be empty).
REQUIRED = ["Title", "Authors", "Publisher",
            "Price Starting With ($)", "Publish Date (Month)", "Publish Date (Year)"]
DEFAULT_INVENTORY = "100"


def parse_authors(field):
    """'By Last, First (COM) and Last2, First2' -> ['Last, First', 'Last2, First2'].

    Each author is a 'Last, First' pair (one comma), so we split on comma and pair
    tokens two-by-two after normalizing the ' and '/' , and ' separators.
    """
    s = field.strip()
    if s.lower().startswith("by "):
        s = s[3:]
    s = s.replace(", and ", ", ").replace(" and ", ", ")
    toks = [t.strip() for t in s.split(",") if t.strip()]
    out, i = [], 0
    while i < len(toks):
        name = (toks[i] + ", " + toks[i + 1]) if i + 1 < len(toks) else toks[i]
        i += 2 if i + 1 < len(toks) else 1
        name = ROLE.sub("", name).strip()
        if name:
            out.append(name)
    return out


def parse_category_path(field):
    """Split a BISAC-style path on the ' , ' level delimiter (space-comma-space) so
    intra-label commas ('Herbs, Spices, Condiments') survive as one leaf."""
    c = field.strip()
    return [s.strip() for s in c.split(" , ") if s.strip()] if c else []


def main():
    author_id = {}     # name -> uuid
    cat_id = {}        # (parent_uuid, name) -> uuid
    cat_rows = []      # (uuid, parent_uuid_or_'', name, depth) in insertion order (parents first)
    books, links = [], []
    kept = drop_null = drop_date = trunc_title = trunc_pub = cat_trunc = with_cat = 0

    def resolve_leaf(path):
        nonlocal cat_trunc
        parent = None
        for depth, name in enumerate(path):
            if len(name) > 255:
                name = name[:255]; cat_trunc += 1
            key = (parent, name)
            cid = cat_id.get(key)
            if cid is None:
                cid = str(uuid.uuid4()); cat_id[key] = cid
                cat_rows.append((cid, parent or "", name, str(depth)))
            parent = cid
        return parent      # leaf uuid, or None for an empty path

    with open(SRC, encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            if not all((row.get(c) or "").strip() for c in REQUIRED):
                drop_null += 1; continue
            mon = MONTHS.get(row["Publish Date (Month)"].strip().lower())
            yr = row["Publish Date (Year)"].strip()
            if not mon or not yr.isdigit():
                drop_date += 1; continue
            price = round(float(row["Price Starting With ($)"].strip()), 2)
            title = row["Title"].strip()
            if len(title) > 255:
                title = title[:255]; trunc_title += 1
            pub = row["Publisher"].strip()
            if len(pub) > 100:
                pub = pub[:100]; trunc_pub += 1
            desc = (row.get("Description") or "").strip()
            leaf = resolve_leaf(parse_category_path(row.get("Category") or ""))
            if leaf:
                with_cat += 1
            buuid = str(uuid.uuid4())
            books.append((buuid, title, desc, leaf or "", f"{price:.2f}",
                          f"{int(yr):04d}-{mon:02d}-01", pub, DEFAULT_INVENTORY))
            for name in parse_authors(row["Authors"]):
                aid = author_id.get(name)
                if aid is None:
                    aid = str(uuid.uuid4()); author_id[name] = aid
                links.append((buuid, aid))
            kept += 1

    def write_gz(path, header, rows):
        with gzip.open(path, "wt", encoding="utf-8", newline="") as gz:
            w = csv.writer(gz); w.writerow(header); w.writerows(rows)

    write_gz(f"{OUT}/categories.csv.gz", ["category_uuid", "parent_uuid", "name", "depth"], cat_rows)
    write_gz(f"{OUT}/authors.csv.gz", ["author_uuid", "author_name"],
             [(u, n) for n, u in author_id.items()])
    write_gz(f"{OUT}/books.csv.gz",
             ["book_uuid", "book_title", "book_description", "category_uuid",
              "price", "publish_date", "publisher", "inventory"], books)
    write_gz(f"{OUT}/book_authors.csv.gz", ["book_uuid", "author_uuid"], links)

    depths = Counter(int(r[3]) for r in cat_rows)
    roots = sum(1 for r in cat_rows if r[1] == "")
    print(f"kept books: {kept} | with category: {with_cat}")
    print(f"category nodes: {len(cat_rows)} | roots: {roots} | depth dist: {dict(sorted(depths.items()))}")
    print(f"authors: {len(author_id)} | book-author links: {len(links)}")
    print(f"dropped null: {drop_null} date: {drop_date} | trunc title: {trunc_title} "
          f"pub: {trunc_pub} cat: {cat_trunc}")


if __name__ == "__main__":
    main()
