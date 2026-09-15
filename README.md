# AWS Advanced JDBC Wrapper, Valkey Cache Demo

A minimal Java demo showing how the AWS Advanced JDBC Wrapper intercepts JDBC calls and caches query results in Valkey, so that repeated reads never hit the database.

## Components

```
Your Java app  (standard JDBC API — Connection, PreparedStatement, ResultSet)
     │
     ▼
AWS Advanced JDBC Wrapper   ← jdbc:aws-wrapper:mysql://localhost:3306/testdb
     │
     ├── remoteQueryCache plugin
     │        │  cache HIT  → reads from Valkey (MySQL not touched)
     │        │  cache MISS → reads from MySQL, writes result to Valkey async
     │        ▼
     │   [ Valkey :6379 ]
     │
     └── MySQL JDBC Driver  (only reached on cache miss)
              ▼
         [ MySQL :3306 ]
```

The only two things that change in your code:
1. URL prefix: `jdbc:aws-wrapper:mysql://` instead of `jdbc:mysql://`
2. SQL hint on queries you want cached: `/* CACHE_PARAM(ttl=60s) */`

Everything else — `Connection`, `PreparedStatement`, `ResultSet` — is standard JDBC, unchanged.

## Prerequisites

- Docker and Docker Compose
- Java 11+
- Maven 3.6+

## Step 1 — Start the infrastructure

```bash
docker compose up -d
docker compose ps        # wait until both services show "healthy"
```

This starts:
- `demo-mysql` on port 3306 (database: `testdb`, root password: `secret`)
- `demo-valkey` on port 6379

## Step 2 — Build

```bash
mvn package -q
```

## Step 3 — Run

```bash
mvn exec:java -q
```

## What you will see

### Driver chain (printed at startup)
```
Driver : software.amazon.jdbc.Driver
Wraps  : com.mysql.cj.jdbc.Driver
Cache  : localhost:6379 (Valkey)
```

### Use Case 1 — Product catalog, cold start
```
[USE CASE 1] Product catalog — first page load
  Query : /* CACHE_PARAM(ttl=60s) */ SELECT * FROM products WHERE category = 'electronics'
  Valkey: MISS  →  MySQL queried  →  3 rows returned
  Result written to Valkey (TTL 60s, async)
  Time  : ~15ms
```

### Use Case 2 — Same page, 10 more users arrive
```
[USE CASE 2] Product catalog — 10 repeated reads (cache warm)
  Query : /* CACHE_PARAM(ttl=60s) */ SELECT * FROM products WHERE category = 'electronics'
  Valkey: HIT × 10  →  MySQL not touched
  Total : ~20ms   (vs ~150ms if all 10 hit MySQL)
```

Round 2 is the key moment: 10 reads, zero database calls.

### Use Case 3 — Real-time stock check, must not be cached
```
[USE CASE 3] Stock level check — cache bypassed (no hint)
  Query : SELECT stock FROM products WHERE id = 1
  Valkey: bypassed  →  MySQL queried every time
  Time  : ~15ms per call
```

This shows when NOT to use caching: real-time or write-sensitive data should never carry the hint.

## How caching is opted in

Opt-in per query via a SQL comment hint:

```sql
-- cached for 60 seconds
/* CACHE_PARAM(ttl=60s) */ SELECT * FROM products WHERE category = 'electronics'

-- never cached (no hint) — always goes to MySQL
SELECT stock FROM products WHERE id = 1
```

## Stop the infrastructure

```bash
docker compose down
```
