# book-server

Online bookstore REST API — Spring Boot 4 + Java 17, MyBatis + PostgreSQL.

## Run with Docker

Brings up the app and a PostgreSQL instance together. The database schema is created
automatically on first start.

```bash
docker compose up --build
```

- API: http://localhost:8080
- PostgreSQL: localhost:5432 (db `bookdb`, user `bookuser`)

Stop and remove containers (keep the data volume):

```bash
docker compose down
```

Wipe the database too (drops the `pgdata` volume):

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

Testcontainers starts a throwaway PostgreSQL, so Docker must be available.
