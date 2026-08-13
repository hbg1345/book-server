# Load tests

Gatling runs from `src/gatling`. The endpoint-only breakpoint simulations ramp from one to
`maxUsers` concurrent requests with no think time. Always pass `baseUrl`; omitting it targets the
deployed Cloud Run service.

## Endpoint capacity

Run these from a separate load-generator machine for measurements. Running Gatling, the app, and
PostgreSQL on one laptop is useful only as a script smoke test.

```bash
# Title search
JAVA_HOME=$(/usr/libexec/java_home -v 17) bash gradlew \
  -PbaseUrl=http://localhost:8080 -PmaxUsers=500 -Pduration=120 \
  gatlingRun \
  --simulation com.example.bookserver.loadtest.BookTitleSearchBreakPointSimulation \
  --run-description "book-title-search_max-500_120s"

# Book detail
JAVA_HOME=$(/usr/libexec/java_home -v 17) bash gradlew \
  -PbaseUrl=http://localhost:8080 -PmaxUsers=500 -Pduration=120 \
  gatlingRun \
  --simulation com.example.bookserver.loadtest.BookDetailBreakPointSimulation \
  --run-description "book-detail_max-500_120s"
```

If latency and error rate are still flat at 500, raise `-PmaxUsers`. If they bend or fail before
500, the existing range is sufficient; report the throughput and p95/p99 at the bend rather than
only the virtual-user count.

## Mixed browsing profiles

`BreakPointSimulation`, `LoadSimulation`, `StressSimulation`, `SpikeSimulation`, and
`EnduranceSimulation` use the same configurable browsing mix:

- 50% `GET /api/books?title=…`
- 50% `GET /api/books/{bookUuid}`

Override the search percentage with `-PtitleSearchPct`; detail receives the remainder.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) bash gradlew \
  -PbaseUrl=http://localhost:8080 \
  -PtitleSearchPct=50 \
  -PmaxUsers=500 -Pduration=120 \
  gatlingRun \
  --simulation com.example.bookserver.loadtest.BreakPointSimulation \
  --run-description "catalog-mixed_50-search_50-detail_max-500"
```

HTML reports are written below `build/reports/gatling`.

## Search feeder

`data/book_search_terms.csv` contains varied title tokens guaranteed to exist in the seeded
catalogue. Regenerate it after changing either the seed or `book_uuids.csv`:

```bash
python3 scripts/generate_loadtest_search_terms.py
```
