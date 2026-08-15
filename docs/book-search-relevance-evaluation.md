# Book title search relevance evaluation

## Purpose

This evaluation measures whether a user who has a particular book in mind can recover it from
the title search. It is separate from the Gatling latency/throughput tests: a query can be fast
and return the wrong books, or relevant and too slow.

The former 21-title hand-written fixture is not used as evidence. It was useful only as a smoke
test and was vulnerable to author selection and test-set overfitting.

## Dataset

- Source catalogue: production Cloud SQL `book` table.
- Sampling: a read-only transaction, a 10-second statement timeout, and PostgreSQL
  `TABLESAMPLE BERNOULLI (5) REPEATABLE (20260814)`.
- Raw sample: 250 real production titles, written only to the local build reports directory.
- Frozen evaluation sample: 60 titles, with 20 titles from each length stratum:
  short (up to 30 characters), medium (31-60), and long (over 60).
- Retrieval corpus: the complete production seed catalogue of about 103,000 books in a local
  Testcontainers PostgreSQL instance. The evaluation never sends repeated search traffic to
  production.

The frozen sample lives at
`src/test/resources/search-quality/production-book-title-sample.tsv`. A deterministic 80/20
development/holdout assignment is kept stable across ranking experiments. Ranking rules may be
tuned against development cases; the holdout is for the final comparison, not repeated tuning.

## Queries and judgments

The automatic set contains only navigational queries with one objectively known target:

| Type | Cases | Construction |
|---|---:|---|
| Exact title | 60 | The production title without modification |
| One-character typo | 60 | Deterministically delete one character from a word of length 5+ |
| Punctuation normalized | 37 | Replace punctuation with spaces and collapse whitespace |

This produces 157 cases. The source title is the relevant result. The evaluator accepts any row
with that title, rather than requiring a particular UUID edition.

Broad queries such as `java`, `history`, or `cooking` are deliberately excluded from automatic
judgment because many books may be relevant. Those require a separate qrels workflow:

1. collect real queries from privacy-reviewed search logs when traffic exists;
2. pool the top results from the baseline and every candidate ranker;
3. hide the producing ranker and result order from assessors;
4. assign graded relevance (0 = irrelevant through 3 = exact intent);
5. use NDCG@10 as the primary offline metric and report assessor agreement.

This follows the standard pooling-and-relevance-judgment shape described by
[NIST TREC](https://trec.nist.gov/howto.html). Elasticsearch's
[rank-evaluation API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-rank-eval)
similarly evaluates a set of rated query/result pairs rather than deriving ground truth from the
ranker being tested.

## Metrics

- **Top-1**: proportion whose target is the first result.
- **Hit@10**: proportion whose target occurs in the first ten results.
- **MRR@10**: mean reciprocal target rank, with a miss scored as zero.
- **95% confidence interval**: non-parametric cluster bootstrap with 5,000 deterministic
  resamples. The source title is the cluster, so exact, typo, and punctuation cases derived from
  the same book are resampled together instead of being treated as independent observations.

## Current baseline

Measured on the existing `ILIKE '%query%' ORDER BY book_uuid DESC` implementation:

| Segment | n | Top-1 | Hit@10 (95% CI) | MRR@10 (95% CI) |
|---|---:|---:|---:|---:|
| All | 157 | 33.8% | 38.2% (36.0-40.6) | 0.354 (0.329-0.381) |
| Development | 125 | 35.2% | 39.2% (36.9-41.9) | 0.366 (0.340-0.396) |
| Holdout | 32 | 28.1% | 34.4% (29.0-40.0) | 0.307 (0.244-0.366) |
| Exact title | 60 | 86.7% | 98.3% (95.0-100.0) | 0.911 (0.846-0.966) |
| One-character typo | 60 | 0.0% | 0.0% (0.0-0.0) | 0.000 (0.000-0.000) |
| Punctuation normalized | 37 | 2.7% | 2.7% (0.0-8.1) | 0.027 (0.000-0.081) |

The baseline demonstrates a specific failure, not a general claim about every possible search:
exact titles usually work, but a one-character typo never recovers the intended book, and a user
who omits title punctuation almost never recovers it.

## Trigram relevance candidate

The first candidate keeps literal substring matching, adds pg_trgm word/whole-title candidates,
and sorts by exact-title equality, word similarity, whole-title similarity, title-start position,
title length, and finally UUID as a deterministic tie-breaker.

| Segment | n | Top-1 | Hit@10 (95% CI) | MRR@10 (95% CI) |
|---|---:|---:|---:|---:|
| All | 157 | 96.8% | 99.4% (98.0-100.0) | 0.977 (0.953-0.995) |
| Development | 125 | 96.8% | 99.2% (97.5-100.0) | 0.978 (0.951-1.000) |
| Holdout | 32 | 96.9% | 100.0% (100.0-100.0) | 0.974 (0.914-1.000) |
| Exact title | 60 | 100.0% | 100.0% (100.0-100.0) | 1.000 (1.000-1.000) |
| One-character typo | 60 | 93.3% | 98.3% (95.0-100.0) | 0.949 (0.896-0.992) |
| Punctuation normalized | 37 | 97.3% | 100.0% (100.0-100.0) | 0.986 (0.959-1.000) |

Compared with the baseline, overall Hit@10 rises by 61.2 percentage points and MRR@10 by
0.623. The bounded GiST KNN retrieval described below reproduces these values exactly; its test
also fails when the established overall, exact-title, typo, or punctuation floors regress.

## Performance failure of the unbounded ranker

The first deployed relevance query generated candidates with GIN but evaluated the complete
matching set before applying its multi-signal ordering. The same 500-user, 120-second Gatling
profiles used for the preceding page-depth comparison showed that this design was not viable:

| Page | Version | Throughput | p50 | p95 | Failed requests |
|---:|---|---:|---:|---:|---:|
| 0 | substring baseline | 437.07 req/s | 172 ms | 1,298 ms | 1 / 65,123 |
| 0 | unbounded relevance ranker | 12.24 req/s | 20,213 ms | 43,184 ms | 103 / 1,799 |
| 100 | substring baseline | 157.33 req/s | 1,164 ms | 4,702 ms | 0 / 19,351 |
| 100 | unbounded relevance ranker | 10.28 req/s | 27,001 ms | 54,031 ms | 194 / 1,563 |

Cloud Run logs tied the 500 responses to connection starvation rather than client networking:
all ten Hikari connections were active, none were idle, as many as 59 requests were waiting, and
connection acquisition timed out after 30 seconds. Increasing the pool would only admit more
expensive sorts to Cloud SQL, so the query plan had to change.

## Bounded GiST KNN candidate

The optimized query retains the same candidate predicates. It adds a GiST trigram index and
obtains a bounded prefix from each literal, word-similarity, and whole-title-similarity source in
word-distance and whole-title-distance order. `WITH TIES` includes the complete score group at
the boundary before the three sources are deduplicated and deterministically ranked. This avoids
adjacent-page overlap when different page requests otherwise cut through the same score group.
The existing GIN index remains useful for the separate bounded navigation probe, which needs
filtering but no similarity ordering.

The local pre-merge benchmark used the complete 103,055-row seed catalogue and the same 17 broad
terms as both Gatling page-depth simulations:

| Index configuration | Page | Total p50 | Total p95 |
|---|---:|---:|---:|
| GIN only | 0 | 268.9 ms | 6,049.9 ms |
| GiST only | 0 | 143.1 ms | 193.7 ms |
| GIN + GiST | 0 | 81.1 ms | 133.3 ms |
| GIN only | 100 | 409.6 ms | 2,195.1 ms |
| GiST only | 100 | 312.5 ms | 393.0 ms |
| GIN + GiST | 100 | 315.3 ms | 460.0 ms |

For the representative broad term `The`, the GIN-only plan scanned and sorted the matching set in
504.9 ms. The two-distance GiST nearest-neighbour plan returned the stable top score group in
44.7-46.4 ms. Index sizes were 14 MB for GIN and 19 MB for GiST.

An earlier word-distance-only KNN variant produced a lower page-0 total p50/p95 of 28.2/45.7 ms,
but it was rejected: equal word-distance rows were truncated before the deterministic business
tie-breakers, so adjacent pages could overlap. The figures in the table include the correctness
fix. These single-threaded local measurements establish the plan change and catch gross
regressions; they do not replace a fresh deployed Gatling run.

## Run

Docker Desktop must be running. This is an offline relevance evaluation, not a load test.

```bash
./gradlew searchRelevanceEvaluation
```

The local full-catalogue index and query-plan comparison is separate:

```bash
./gradlew searchPerformanceEvaluation
```

`sampleProductionBookTitles` is a separate explicit production-read task. It requires
`PRODUCTION_DATABASE_URL`, `PRODUCTION_DATABASE_USERNAME`, and
`PRODUCTION_DATABASE_PASSWORD` in the process environment and never prints or stores those
values.

## Limitations and next gate

- The queries are deterministic perturbations, not observed user language.
- The source catalogue is English-only, so the score says nothing about Korean search.
- The holdout has only 32 cases; its confidence interval is correspondingly wide.
- Offline relevance and local query-plan improvement are insufficient by themselves. The bounded
  GiST candidate still has to pass the same first/deep-page deployed latency guardrails, followed
  by online CTR, reformulation, abandonment, and purchase-conversion measurement when real
  traffic exists.

No relevance implementation should be merged solely because it scores well on these synthetic
navigational transformations. It must also improve a blind, human-judged keyword pool and retain
acceptable latency on the complete catalogue.
