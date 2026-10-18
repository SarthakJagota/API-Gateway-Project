# API Gateway — self-service rate limiting (UCS503P)

Put this gateway in front of any API. Register the API in the dashboard, get a
gateway URL + key, and clients over their budget get `429 Too Many Requests`.

## Run locally

Needs Java 17+ and Docker.

Everything in Docker (simplest):

```bash
docker compose up --build     # Postgres + Redis + app -> http://localhost:8080
```

Or run the app yourself (faster while coding):

```bash
docker compose up -d postgres redis
./mvnw spring-boot:run -Dspring-boot.run.profiles=local   # Windows: .\mvnw.cmd ...
```

Open http://localhost:8080, create an account, register an API, then call it:

```bash
curl http://localhost:8080/g/<short-name>/any/path -H "X-API-Key: <key>"
# over the limit -> HTTP 429 + Retry-After
```

Tables are created automatically (`ddl-auto=update`).

## Tests

```bash
./mvnw verify   # needs Docker running (Testcontainers starts Redis + Postgres)
```

- `RateLimitConcurrencyTest` – 400 concurrent requests through **two** limiter
  instances must admit exactly the limit (both algorithms), plus fail-open /
  fail-closed when Redis is down.
- `LogCompletenessIntegrationTest` – ≥ 99.9 % of 2000 async log writes land in Postgres.
- `RequestLogRepositoryFilterTest` – log filters.

CI runs the same command on every push (`.github/workflows/ci.yml`).

## Deploy (Render / Railway)

Deploy with the `Dockerfile` and set these environment variables:

| Variable | Example |
|---|---|
| `DB_URL` | `jdbc:postgresql://host:5432/dbname` (note the `jdbc:` prefix) |
| `DB_USERNAME`, `DB_PASSWORD` | from the managed Postgres |
| `REDIS_URL` | `redis://default:pass@host:6379` (or `rediss://` for TLS) |
| `JWT_SECRET` | **required** – random string, 32+ characters (the app won't start without it) |
| `FAIL_POLICY` | `open` (default) or `closed` |
| `CLIENT_IP_HEADER` | `CF-Connecting-IP` when behind Cloudflare (Render); leave unset otherwise |

The dashboard is served by the same app, so no separate frontend deploy is
needed. To host `index.html` elsewhere (e.g. Vercel), set `CFG.BASE` at the top
of its script to the backend URL.

## How a request flows

`GatewayController` → `RouteCache` (API + policy, cached 30 s) → `RateLimiter`
(Redis) → forward to the target with Java's `HttpClient` → `RequestLogService`
writes the log row asynchronously.

- **Token bucket** – Bucket4j on Redis. Burst up to `burstCapacity` (defaults to
  the limit), refills at `limit / windowSeconds`.
- **Sliding window** – a Redis sorted set of request timestamps, checked and
  updated in one Lua script (atomic across instances, uses Redis's clock).
- **Redis down** – `open` lets traffic through, `closed` rejects it. After a
  failure Redis is skipped for 5 s so requests don't each wait on a timeout.
- **Client identity** – client IP. Behind Cloudflare, set `CLIENT_IP_HEADER=CF-Connecting-IP`
  so the real client IP is used instead of the edge server's.

## Known limits (worth stating in the report)

- The fail policy is set only by `FAIL_POLICY` (the System page shows it read-only),
  because it applies to every API on the gateway.
- Log writes are fire-and-forget (`@Async`), so a crash can lose the last few rows.
- The Test lab fires requests from one browser, so it measures one client.
