# Book Server

[日本語](#日本語) | [English](#english)

## 日本語

### 概要

Book Serverは、実際のサービス要件をデータモデル、API、トランザクション、非同期処理、テスト、デプロイへ落とし込む一連のサーバー設計を学ぶため、2026年7月26日から個人で開発しているオンライン書店REST APIです。

103,055冊のカタログを対象に、会員・住所管理、書籍検索、カート、注文、決済、キャンセル、部分返金、購入確定を提供します。要件とfailure scenarioの定義から、DB/API設計、実装、テスト、Google Cloudへのデプロイ、性能・検索品質評価まで一人で担当しました。

- [API documentation / Swagger UI](https://hbg1345.github.io/book-server/)
- Production: Spring Boot on Cloud Run + PostgreSQL on Cloud SQL

### 技術的に注力した点

#### 1. 検索品質と応答性能の両立

単純な`ILIKE`検索では、完全一致、1文字のtypo、句読点を省略した検索を適切に順位付けできませんでした。trigramを利用した複数の関連度signalを導入し、157 queryの評価でTop-1 **96.8%**、Hit@10 **99.4%**を記録しました。

最初の関連度検索はGINで候補を抽出した後、該当する全件をsortしていました。500 virtual users・120秒のGatling testではpage 0/100のp95が43,184/54,031 msまで悪化し、Hikariの10 connectionがすべて使用され、最大59 requestがconnectionを待ちました。pool拡大は高コストqueryをCloud SQLへさらに流すだけだと判断し、query planを変更しました。

現在はliteral substring、word similarity、whole-title similarityごとに必要範囲だけをGiST KNNで取得し、重複排除後に決定論的に順位付けします。GINはsimilarity sortが不要なbounded navigation probeに残しました。103,055行のlocal benchmarkでは、page 0/100のp95をGIN-onlyの6,049.9/2,195.1 msから、GIN+GiSTの133.3/460.0 msへ短縮しました。

より速いword-distance-only案も試しましたが、同点結果がpage boundaryで切られ、隣接pageに同じ書籍が重複する可能性がありました。そこで`WITH TIES`で境界の同点groupを含め、whole-title distanceとbusiness tie-breakerを適用する案を採用しました。

詳細: [Book title search relevance evaluation](docs/book-search-relevance-evaluation.md)

#### 2. 連続クリックとretryに対する冪等性

カート行は`(user_uuid, book_uuid)`を複合主キーとし、`INSERT ... ON CONFLICT DO UPDATE`で数量を原子的に加算します。checkoutではcart rowを`FOR UPDATE OF ci`でlockし、同じカートから2件の注文が作られることを防ぎます。

決済は注文UUIDから同じidempotency keyを生成し、内部`payment` tableのUNIQUE制約とStripe requestの両方に同じ重複排除単位を適用します。Testcontainersを用いた同時実行testで、カート数量の消失、二重checkout、在庫超過、二重refundが発生しないことを検証しています。

#### 3. 現在状態と監査履歴を両立する注文モデル

履歴だけを蓄積すると注文一覧のたびに最新行を計算する必要があり、現在状態だけを上書きすると変更過程を追跡できません。そこで、注文状態を`purchase_history`、各時点の商品・数量・価格snapshotを`purchase_book_history`へappend-onlyで保存し、`purchase_current`には現在状態と最新`history_uuid`をhead pointerとして保持します。

注文一覧は`purchase_current`のuser indexから直接読み、詳細画面はhead pointerと履歴をたどります。読み取り目的を分けながら、決済待ち、配送、キャンセル、返金までの監査可能性を維持しています。

### 信頼性と運用

- Flywayをschema変更とcatalog seedの単一基準として使用
- Testcontainers PostgreSQLで実migration、SQL、lock、競合状態を検証
- Spring REST Docs testからOpenAPI 3を生成し、Swagger UIと共にGitHub Pagesへ公開
- GitHub Actionsでtest、API document生成、Docker image build、Cloud Run deployを自動化
- GitHub OIDC + Workload Identity Federationを利用し、長期GCP keyを保存しない構成
- 未決済注文はorderごとのCloud Taskで失効し、Cloud Scheduler sweepを補正処理として利用

### 技術スタック

- Language: Java 17
- Backend: Spring Boot 4, Spring Security, MyBatis
- Database: PostgreSQL, Flyway
- Payment: Stripe
- Infrastructure: Google Cloud Run, Cloud SQL, Cloud Tasks, Cloud Scheduler, Docker
- Test and documentation: JUnit, Testcontainers, Gatling, Spring REST Docs, OpenAPI

---

## English

Online bookstore REST API — Spring Boot 4 + Java 17, MyBatis + PostgreSQL.

JWT-authenticated user accounts, a browsable ~103k-book catalog, cart, and orders.
The whole schema **and** the catalog seed are owned by Flyway, so local, Docker, and
production (Cloud SQL) all come up with identical data.

## API

Interactive docs (Swagger UI, generated from the tests and published on every push to
`master`):

**https://hbg1345.github.io/book-server/** → served against the live Cloud Run API.

| Area | Endpoints | Auth |
|------|-----------|------|
| Auth | `POST /api/auth/login` · `refresh` · `logout` | public |
| Users | `POST /api/users` (sign up) · `GET/PUT /api/users/me` · `PUT /api/users/me/password` · `DELETE /api/users/me` | bearer (except sign-up) |
| Books | `GET /api/books?title=…&page=0` (20/search page) · `GET /api/books/{uuid}` (public) · `POST/PUT/DELETE` | reads public; writes **admin** |
| Authors | `GET /api/authors?name=` (public) · `POST /api/authors` | reads public; writes **admin** |
| Cart | `GET /api/cart` · `POST /api/cart/items` · `PUT/DELETE /api/cart/items/{bookUuid}` | bearer |
| Orders | `POST /api/orders` · `GET /api/orders` · `GET /api/orders/{uuid}` · `POST .../payment-intent` · `POST .../cancel` | bearer |

Auth is a short-lived access JWT (`Authorization: Bearer <accessToken>`) plus a rotating
opaque refresh token. Send the access token on every protected call; use `/api/auth/refresh`
to swap a refresh token for a fresh pair.

Access control is role-based (`USER` / `ADMIN`). Self-registration always yields a `USER`;
catalog **writes** (create/update/delete books and authors) require `ADMIN`. Unauthenticated
requests to a protected route get `401`, authenticated-but-not-admin get `403`. The public
deployment has no admin, so the live catalog is effectively read-only. To exercise the write
endpoints, run locally and seed an admin (see [Trying the admin endpoints](#trying-the-admin-endpoints)).

## Run with Docker

Brings up the app and a PostgreSQL instance together. On first start Flyway applies every
versioned migration and seeds the catalog, so the first boot takes a bit longer.

```bash
docker compose up --build
```

- API: http://localhost:8080
- PostgreSQL: localhost:5432 (db `bookdb`, user `bookuser`)

Stop and remove containers (keep the data volume):

```bash
docker compose down
```

Wipe the database too (drops the `pgdata` volume, so the next start re-runs Flyway from
scratch):

```bash
docker compose down -v
```

### Configuration

Most things have a working default for local use; override via a `.env` file or the shell.
Copy `.env.example` to `.env` to get started — the Stripe keys have no default and the app
will not start without them.

| Variable | Default | Purpose |
|----------|---------|---------|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `bookdb` / `bookuser` / `bookpass` | Database name & credentials |
| `JWT_SECRET` | dev-only placeholder | HS256 signing secret — **must** be set to a real value (≥ 32 bytes) in production |
| `STRIPE_SECRET_KEY` | *(none — required)* | Stripe API key. There is no fallback payment gateway: without this the app refuses to start rather than booting unable to charge |
| `STRIPE_WEBHOOK_SECRET` | *(none)* | Signing secret used to authenticate Stripe webhooks |
| `STRIPE_CURRENCY` | `usd` | Currency charges are made in. Zero-decimal currencies (`krw`, `jpy`) are charged in whole units |

The app reads its DB connection from `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD`,
which `docker-compose.yml` wires to the `postgres` service.

### Trying the admin endpoints

Catalog writes require the `ADMIN` role, and self-registration only grants `USER`, so an
admin has to be seeded. A config-driven bootstrap does this on startup — **off by default**,
enable it for a local run:

```bash
ADMIN_BOOTSTRAP_ENABLED=true \
ADMIN_BOOTSTRAP_USER_ID=admin \
ADMIN_BOOTSTRAP_PASSWORD='choose-a-password' \
docker compose up --build
```

Then log in and use the returned `accessToken` as `Authorization: Bearer <token>` on the
write calls:

```bash
curl -s localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"userId":"admin","password":"choose-a-password"}'
```

The bootstrap is idempotent (re-asserts the role on restart) and is intentionally left
disabled on the public deployment, so the live catalog has no admin.

## Database & catalog seed

Schema and data are migration-owned; there is no hand-written `schema.sql`.

- `V1__init.sql` — core tables for users, books, authors, carts, and current/history order state.
- `V2__add_category_tree.sql` — normalized category tree plus `book.category_uuid`.
- `V3__Seed_book_catalog.java` — streams four gzipped, pre-normalized files from
  `src/main/resources/db/seed/` into Postgres via `COPY` (categories → authors → books →
  book_authors).
- `V4`–`V7` — user roles, address book, immutable order delivery addresses, and shipment tracking.
- `V8`–`V11` — payments, refund-failure state, provider transaction uniqueness, and partial cancellation.
- `V12`–`V13` — one normalized address per user and book ISBNs.
- `V14`–`V15` — GIN and GiST trigram indexes for title search.

The seed files are generated offline by `scripts/generate_book_seed.py` from
`archive/BooksDatasetClean.csv` (the raw CSV is gitignored). UUIDs are baked into the seed,
so if you regenerate, regenerate **all four files together**.

## Build the image only

```bash
docker build -t book-server .
```

The image is a multi-stage build: a JDK stage produces the boot jar (`bootJar`), and a
slim JRE stage runs it as a non-root user. Tests are **not** run during the image build
(they need a Docker daemon for Testcontainers) — they run in CI.

## Run tests

```bash
./gradlew test
```

Testcontainers starts a throwaway PostgreSQL, so Docker must be available. Tests get their
schema from Flyway too — all migrations run, but the V3 catalog-seed migration copies
nothing when the `catalogSeed.skip` system property is set (Gradle `test` task), so the
schema is built without the heavy seed while staying in sync with prod migrations. Per-test
isolation is a `@Sql("/reset.sql")` truncate.

The heavy UUID v4-vs-v7 benchmark is excluded from the normal run:

```bash
./gradlew benchmark
```

## API docs

The OpenAPI 3 spec is generated from the RestDocs tests, not hand-maintained:

```bash
./gradlew openapi3   # → build/api-spec/openapi3.yaml
```

## CI/CD

`.github/workflows/ci.yml` runs on every push/PR to `master`:

1. **test** — full suite via Testcontainers, and assembles the API docs.
2. **deploy-docs** (push only) — publishes Swagger UI + the generated spec to GitHub Pages.
3. **deploy** (push only) — builds & pushes the image to Artifact Registry and rolls out a
   new Cloud Run revision. GCP auth is via Workload Identity Federation (no long-lived
   keys); the DB password and `JWT_SECRET` come from GCP Secret Manager. Cloud SQL is
   reached through the Postgres socket factory.

## Order expiry (releasing reserved stock)

Orders reserve stock when placed; unpaid ones past `order.payment-timeout` (default 30m)
must be cancelled to release it. The service runs on Cloud Run with scale-to-zero, so this
is **not** an in-process timer (which wouldn't fire while no instance is up). It uses a
**hybrid** of two internal endpoints, both outside the JWT surface and guarded by the shared
`INTERNAL_SWEEP_TOKEN` secret in an `X-Internal-Token` header (fails closed if unset):

| Endpoint | Trigger | Role |
|----------|---------|------|
| `POST /internal/orders/{id}/expire` | Cloud Tasks, one task per order scheduled at placement | precise per-order expiry at ~exactly the timeout |
| `POST /internal/orders/expire-unpaid` | Cloud Scheduler cron | safety net — catches orders whose task enqueue failed (the order DB commit and the enqueue are separate systems) |

Both call the same idempotent expiry (`expireUnpaidOrder`), which no-ops unless the order is
still `PAYMENT_PENDING` — so at-least-once delivery and an order paid before the task fires
are both harmless.

### Prod setup

Per-order Cloud Tasks is enabled by config (off locally, so no GCP is needed for dev/tests):

```
ORDER_EXPIRY_CLOUD_TASKS_ENABLED=true
GCP_PROJECT_ID=<project>
ORDER_EXPIRY_QUEUE_LOCATION=asia-northeast3
ORDER_EXPIRY_QUEUE=<queue-name>
ORDER_EXPIRY_TARGET_BASE_URL=https://<cloud-run-url>
INTERNAL_SWEEP_TOKEN=<secret>       # from Secret Manager; also on the two jobs below
```

Create the queue once, and the safety-net Cloud Scheduler job:

```bash
gcloud tasks queues create <queue-name> --location=asia-northeast3

gcloud scheduler jobs create http expire-unpaid-orders \
  --location=asia-northeast3 \
  --schedule="*/10 * * * *" \
  --uri="https://<cloud-run-url>/internal/orders/expire-unpaid" \
  --http-method=POST \
  --headers="X-Internal-Token=<secret>"
```

(With per-order tasks doing the precise work, the safety-net sweep can run infrequently,
e.g. every 10 minutes.)
