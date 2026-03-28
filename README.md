# FinTech API Aggregation Platform

A backend system that aggregates financial data from multiple providers with parallel fan-out, Redis caching, sliding window rate limiting, and a hand-rolled circuit breaker. Built to answer one question: *what happens when things go wrong?*

---

## The problem it solves

Every financial data provider has rate limits, outages, and inconsistent data quality. A naive system picks one provider and hopes for the best. This system fans out to all available providers simultaneously, applies quality-based selection logic, and degrades gracefully when providers fail — without the caller ever knowing something went wrong.

---

## Architecture

```
Client
  └── GET /price?symbol=BTC&type=crypto
        │
        ▼
  PriceController
        │
        ▼
  AggregatorService
        ├── 1. Redis cache check (free hit — no provider calls)
        ├── 2. DeduplicationService (coalesce concurrent identical requests)
        └── 3. Parallel fan-out via CompletableFuture
              ├── CircuitBreakerRegistry (skip OPEN providers)
              ├── CoinGeckoProvider     ──┐
              ├── AlphaVantageProvider  ──┼── provider-pool threads
              └── MockProvider          ──┘
                        │
                        ▼
                ResponseSelector (pick best: real > mock, then fastest)
                        │
                        ▼
                CacheService (write to Redis if clean response)
                        │
                        ▼
                PriceResponse { symbol, price, source, fallbackUsed, cachedResponse }
```

---

## Components

### Provider abstraction layer

All providers implement a single interface:

```java
public interface PriceProvider {
    PriceResponse getPrice(PriceRequest request);
    String getName();
    boolean supports(String type);
}
```

Providers never catch their own exceptions — they throw and let the aggregator decide. This was a deliberate architectural decision: each provider's job is to fetch or fail, not to recover.

| Provider | Asset type | Notes |
|---|---|---|
| `CoinGeckoProvider` | crypto | Real HTTP call to api.coingecko.com |
| `AlphaVantageProvider` | stock | Real HTTP call, free tier = 25 calls/day |
| `MockProvider` | all | Configurable failure modes for resilience testing |

Provider priority is set via `@Order` — `MockProvider` is always `@Order(99)`, making it the guaranteed last resort.

### Parallel fan-out and response selection

The aggregator calls all eligible providers simultaneously using `CompletableFuture` on a dedicated `ThreadPoolExecutor` (never the common pool — provider calls are blocking I/O).

All results are collected within a configurable timeout, then passed to `ResponseSelector`, which ranks them:

1. Successful responses only (failures discarded)
2. Prefer `fallbackUsed=false` (real data beats hardcoded mock data)
3. Among equal-quality responses, prefer the fastest provider

This is what makes it an aggregator rather than a fallback chain. A slow real price beats a fast mock price every time.

### Circuit breaker

Hand-rolled 3-state machine per provider — deliberately not using Resilience4j.

```
CLOSED ──[3 consecutive failures]──▶ OPEN
OPEN   ──[10s recovery timeout]───▶ HALF_OPEN
HALF_OPEN ──[probe succeeds]──────▶ CLOSED
HALF_OPEN ──[probe fails]─────────▶ OPEN (reset timeout)
```

State is stored in a `ConcurrentHashMap` of `AtomicReference` fields — safe for concurrent fan-out without locks. The HALF_OPEN probe uses a CAS on `probeDispatched` to ensure exactly one thread acts as probe even under concurrent load.

Thresholds are externalized to `application.yml` — no recompile needed to tune them.

### Redis caching

Cache key format: `price:{type}:{symbol}` — e.g. `price:crypto:BTC`.

Stored as human-readable JSON using `StringRedisTemplate` + Jackson. You can inspect live cache state during a demo:

```bash
docker exec -it fintech-redis redis-cli
> GET price:crypto:BTC
> TTL price:crypto:BTC
> KEYS price:*
```

**Cache correctness decision**: only clean responses (`fallbackUsed=false`) are written to cache. Caching degraded responses would serve stale mock data for 10 seconds even after the real provider recovers.

### Request deduplication

Prevents the thundering herd problem: if 500 concurrent requests for `BTC` arrive simultaneously, only one fan-out is dispatched. The remaining 499 attach to the same `CompletableFuture` and receive the same result.

Deduplication window is 2 seconds — intentionally longer than just "while in-flight" to absorb bursts that arrive slightly after the first response completes but before the cache is populated.

### MockProvider failure modes

The MockProvider simulates real-world failure conditions and is the primary tool for demonstrating resilience:

| Mode | Behaviour | Resilience path demonstrated |
|---|---|---|
| `FAIL` | Throws immediately | Circuit breaker accumulation, fallback |
| `SLOW` | Sleeps 3–5 seconds | Fan-out timeout handling |
| `BAD_DATA` | Returns null price | Response validation, fallback |
| `RANDOM` | Randomly picks one of the above | General resilience under uncertainty |

Set via `providers.mock.mode` in `application.yml` — no recompile needed.

---

## How to run

**Prerequisites:** Java 21, Maven, Docker

**1. Start Redis**
```bash
docker run -d --name fintech-redis -p 6379:6379 redis:7-alpine
```

**2. Add your AlphaVantage key**

Get a free key at [alphavantage.co](https://www.alphavantage.co/support/#api-key), then set it in `application.yml`:
```yaml
providers:
  alphavantage:
    api-key: YOUR_KEY_HERE
```

**3. Run the app**
```bash
mvn spring-boot:run
```

---

## API reference

### `GET /price`

| Parameter | Type | Values |
|---|---|---|
| `symbol` | string | `BTC`, `ETH`, `SOL` (crypto) · `AAPL`, `MSFT`, `GOOGL` (stock) |
| `type` | string | `crypto` · `stock` |

**Example request:**
```bash
curl "http://localhost:8080/price?symbol=BTC&type=crypto"
```

**Example response:**
```json
{
  "symbol": "BTC",
  "price": 64500.00,
  "currency": "USD",
  "type": "crypto",
  "source": "CoinGecko",
  "fallbackUsed": false,
  "cachedResponse": false,
  "timestamp": "2024-03-01T10:00:00Z"
}
```

**Response fields:**

| Field | Meaning |
|---|---|
| `source` | Which provider answered |
| `fallbackUsed` | True if any provider in the chain failed |
| `cachedResponse` | True if served from Redis — no provider was called |

### `GET /health`

Shows live circuit breaker state per provider.

```bash
curl http://localhost:8080/health
```

```json
{
  "status": "DEGRADED",
  "timestamp": "2024-03-01T10:00:00Z",
  "circuitBreakers": [
    {
      "provider": "CoinGecko",
      "state": "OPEN",
      "consecutiveFailures": 3,
      "openedAt": "2024-03-01T09:59:50Z"
    },
    {
      "provider": "MockProvider",
      "state": "CLOSED",
      "consecutiveFailures": 0,
      "openedAt": null
    }
  ]
}
```

`status` is `UP` when all breakers are CLOSED, `DEGRADED` when any is OPEN.

---

## Demo scenarios

**Normal request — cache miss then hit**
```bash
curl "http://localhost:8080/price?symbol=BTC&type=crypto"
# cachedResponse: false — provider was called

curl "http://localhost:8080/price?symbol=BTC&type=crypto"
# cachedResponse: true — served from Redis, zero provider calls
```

**Trip the circuit breaker**

Set `mock.mode: FAIL` in `application.yml` and make CoinGecko throw (or exhaust your AlphaVantage daily quota). Hit the endpoint 3 times:

```bash
for i in 1 2 3 4; do curl -s "http://localhost:8080/price?symbol=AAPL&type=stock" | python3 -m json.tool; done
```

On the 4th request, check `/health` — AlphaVantage should show `state: OPEN`. Subsequent requests skip it entirely and go straight to MockProvider.

**Thundering herd protection**
```bash
for i in 1 2 3 4 5; do
  curl -s "http://localhost:8080/price?symbol=ETH&type=crypto" &
done
wait
```

Check logs — you should see exactly one `Fanning out to` line and multiple `Dedup HIT: waiting on in-flight future` lines. All 5 requests return the same result.

**Inspect Redis directly**
```bash
docker exec -it fintech-redis redis-cli
127.0.0.1:6379> KEYS price:*
127.0.0.1:6379> GET price:crypto:BTC
127.0.0.1:6379> TTL price:crypto:BTC
```

---

## Configuration reference

```yaml
providers:
  mock:
    mode: RANDOM           # FAIL | SLOW | BAD_DATA | RANDOM

cache:
  ttl-seconds: 10          # Redis TTL per price entry

circuit-breaker:
  failure-threshold: 3     # Consecutive failures before OPEN
  recovery-timeout-seconds: 10

async:
  provider-pool:
    core-size: 5
    max-size: 10
    timeout-seconds: 5     # Max wait for all provider futures

deduplication:
  ttl-seconds: 2           # Dedup window for burst absorption
```

