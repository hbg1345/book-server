#!/usr/bin/env python3
"""Generate the gzipped, normalized seed files loaded by the V3 Flyway migration.

Input : archive/BooksDatasetClean.csv  (Kaggle books dataset; not tracked in git)
Output: src/main/resources/db/seed/{categories,authors,books,book_authors}.csv.gz

Run from the repo root:  python scripts/generate_book_seed.py

All four files share pre-generated UUIDs, so ALWAYS regenerate them together.
See memory/book-catalog-seed-pipeline.md for the rationale behind the data decisions.
"""
import csv, re, gzip, uuid

SRC = "archive/BooksDatasetClean.csv"
OUT = "src/main/resources/db/seed"

MONTHS = {m.lower(): i for i, m in enumerate(
    ["January", "February", "March", "April", "May", "June", "July", "August",
     "September", "October", "November", "December"], 1)}
ROLE_END = re.compile(r'\s*\((?:[A-Z]{2,4})\)\s*$')      # trailing role code: (COM),(EDT),(ILT),(COR)...

# Required fields — a row missing any of these is dropped (description/category may be empty).
REQUIRED = ["Title", "Authors", "Publisher",
            "Price Starting With ($)", "Publish Date (Month)", "Publish Date (Year)"]
DEFAULT_INVENTORY = "100"


def _split_top_and(s):
    """Split on ' and ' only at paren depth 0 (protects '(Birmingham, Ala.)' etc.)."""
    out, depth, last, i = [], 0, 0, 0
    while i < len(s):
        c = s[i]
        if c == '(':
            depth += 1
        elif c == ')':
            depth = max(0, depth - 1)
        elif depth == 0 and s[i:i + 5] == " and ":
            out.append(s[last:i]); last = i + 5; i += 5; continue
        i += 1
    out.append(s[last:])
    return out


def parse_authors(field):
    """Split the 'By ...' author field into individual author names.

    Personal authors are 'Last, First' (one internal comma); corporate authors
    ('University of X (COR)') carry commas of their own. We use the trailing role
    code (COR)/(EDT)/(ILT)/... as the end-of-author marker, and fall back to pairing
    two comma tokens for role-less personal names. Not perfect: an ' and ' inside an
    org name outside parens (e.g. 'Food and Nutrition') can still over-split — the
    separators are ambiguous with in-name text. Role codes are stripped from the
    stored name.
    """
    s = field.strip()
    if s.lower().startswith("by "):
        s = s[3:]
    s = s.replace(", and ", ", ")                 # oxford conjunction -> comma separator
    s = ", ".join(_split_top_and(s))              # 2-author ' and ' -> comma separator
    toks = [t.strip() for t in s.split(",") if t.strip()]

    out, buf = [], []

    def flush():
        if buf:
            name = ROLE_END.sub("", ", ".join(buf)).strip()
            if name:
                out.append(name)
        buf.clear()

    for tok in toks:
        buf.append(tok)
        if ROLE_END.search(tok):                  # role code ends an author unit
            flush()
        elif len(buf) == 2:                       # 'Last, First' personal pair
            flush()
    flush()
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
        for name in path:
            if len(name) > 255:
                name = name[:255]; cat_trunc += 1
            key = (parent, name)
            cid = cat_id.get(key)
            if cid is None:
                cid = str(uuid.uuid4()); cat_id[key] = cid
                cat_rows.append((cid, parent or "", name))   # (uuid, parent_uuid_or_'', name)
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
            seen = set()      # a book can list the same person as author & editor; (book,author) is a PK
            for name in parse_authors(row["Authors"]):
                aid = author_id.get(name)
                if aid is None:
                    aid = str(uuid.uuid4()); author_id[name] = aid
                if aid not in seen:
                    seen.add(aid)
                    links.append((buuid, aid))
            kept += 1

    def write_gz(path, header, rows):
        with gzip.open(path, "wt", encoding="utf-8", newline="") as gz:
            w = csv.writer(gz); w.writerow(header); w.writerows(rows)

    write_gz(f"{OUT}/categories.csv.gz", ["category_uuid", "parent_uuid", "name"], cat_rows)
    write_gz(f"{OUT}/authors.csv.gz", ["author_uuid", "author_name"],
             [(u, n) for n, u in author_id.items()])
    write_gz(f"{OUT}/books.csv.gz",
             ["book_uuid", "book_title", "book_description", "category_uuid",
              "price", "publish_date", "publisher", "inventory"], books)
    write_gz(f"{OUT}/book_authors.csv.gz", ["book_uuid", "author_uuid"], links)

    roots = sum(1 for r in cat_rows if r[1] == "")
    print(f"kept books: {kept} | with category: {with_cat}")
    print(f"category nodes: {len(cat_rows)} | roots (top-level): {roots}")
    print(f"authors: {len(author_id)} | book-author links: {len(links)}")
    print(f"dropped null: {drop_null} date: {drop_date} | trunc title: {trunc_title} "
          f"pub: {trunc_pub} cat: {cat_trunc}")


if __name__ == "__main__":
    main()
