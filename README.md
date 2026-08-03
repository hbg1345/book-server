# book-server

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
| Books | `GET /api/books` · `GET /api/books/{uuid}` (public) · `POST/PUT/DELETE` | reads public; writes **admin** |
| Authors | `GET /api/authors?name=` (public) · `POST /api/authors` | reads public; writes **admin** |
| Cart | `GET /api/cart` · `POST /api/cart/items` · `PUT/DELETE /api/cart/items/{bookUuid}` | bearer |
| Orders | `POST /api/orders` · `GET /api/orders` · `GET /api/orders/{uuid}` · `POST .../pay` · `POST .../cancel` | bearer |

Auth is a short-lived access JWT (`Authorization: Bearer <accessToken>`) plus a rotating
opaque refresh token. Send the access token on every protected call; use `/api/auth/refresh`
to swap a refresh token for a fresh pair.

Access control is role-based (`USER` / `ADMIN`). Self-registration always yields a `USER`;
catalog **writes** (create/update/delete books and authors) require `ADMIN`. Unauthenticated
requests to a protected route get `401`, authenticated-but-not-admin get `403`. The public
deployment has no admin, so the live catalog is effectively read-only. To exercise the write
endpoints, run locally and seed an admin (see [Trying the admin endpoints](#trying-the-admin-endpoints)).

## Run with Docker

Brings up the app and a PostgreSQL instance together. On first start Flyway builds the
schema and seeds the catalog (V1 → V4), so the first boot takes a bit longer.

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

Everything has a working default for local use; override via a `.env` file or the shell.

| Variable | Default | Purpose |
|----------|---------|---------|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `bookdb` / `bookuser` / `bookpass` | Database name & credentials |
| `JWT_SECRET` | dev-only placeholder | HS256 signing secret — **must** be set to a real value (≥ 32 bytes) in production |

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

- `V1__init.sql` — core tables (users, books, authors, cart, orders).
- `V2__add_category_tree.sql` — normalized category tree (`category(uuid, parent_uuid, name)`,
  self-FK) plus `book.category_uuid`.
- `V3__add_user_role.sql` — `book_user.role` (`USER`/`ADMIN`) for role-based access control.
- `V4__Seed_book_catalog.java` — streams four gzipped, pre-normalized files from
  `src/main/resources/db/seed/` into Postgres via `COPY` (categories → authors → books →
  book_authors).

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
schema from Flyway too (`spring.flyway.target=2`, so the heavy V3 catalog seed is skipped),
keeping tests in sync with prod migrations. Per-test isolation is a `@Sql("/reset.sql")`
truncate.

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
