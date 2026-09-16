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
2. SQL hint on queries you want cached: `/* CACHE_PARAM(ttl=3600) */`

Everything else, `Connection`, `PreparedStatement`, `ResultSet` — is standard JDBC, unchanged.

## Prerequisites

- Docker and Docker Compose
- Java 11+
- Maven 3.6+

## Step 1: Start the infrastructure

```bash
docker compose up -d
docker compose ps        # wait until both services show "healthy"
```

This starts:
- `demo-mysql` on port 3306 (database: `testdb`, root password: `secret`)
- `demo-valkey` on port 6379

## Step 2: Build

```bash
mvn package -q
```

## Step 3: Run

```bash
mvn exec:java -q
```

## What you will see

```
=================================================
  AWS Advanced JDBC Wrapper — Valkey Cache Demo  
=================================================

--- Driver chain ---
  Wrapper : software.amazon.jdbc.Driver (v4.4.0)
  Plugin  : remoteQueryCache
  Cache   : localhost:6379 (Valkey)
  Driver  : com.mysql.cj.jdbc.Driver
  DB      : MySQL 8.0.46

--- Products in MySQL ---
  ID    Name                       Price  Stock
  ----------------------------------------------
  1     Laptop Pro 15           $1299.99     42
  2     Wireless Mouse          $  29.99    150
  3     USB-C Hub               $  49.99     88
  ----------------------------------------------

=================================================
[USE CASE 1] Product catalog — first page load
=================================================
  Query : /* CACHE_PARAM(ttl=3600) */ SELECT id, name, price FROM products WHERE category = 'electronics'
  ---
  id=1   Laptop Pro 15         $1299.99
  id=2   Wireless Mouse        $  29.99
  id=3   USB-C Hub             $  49.99
  ---
  Result: 3 rows  |  Time: 3.0ms
  Cache : MISS → MySQL queried → result written to Valkey (TTL 3600s)

=================================================
[USE CASE 2] Product catalog — 10 users (cache warm)
=================================================
  Query : /* CACHE_PARAM(ttl=3600) */ SELECT id, name, price FROM products WHERE category = 'electronics'
  Execution #1: 0.74ms
  Execution #2: 0.73ms
  Execution #3: 0.97ms
  Execution #4: 1.14ms
  Execution #5: 1.06ms
  Execution #6: 0.98ms
  Execution #7: 0.87ms
  Execution #8: 0.91ms
  Execution #9: 0.91ms
  Execution #10: 0.93ms
  Cache : HIT × 10 → MySQL never touched
  Total : 9.2ms for 10 reads from Valkey

=================================================
[USE CASE 3] Real-time stock check
=================================================
  Query : SELECT stock FROM products WHERE id = 1
  Stock : 42 units
  Time: 5.6ms
  Cache : bypassed — no CACHE_PARAM hint present

  WHY: stock levels change constantly — serving stale data from
  cache would show wrong inventory. Never hint real-time queries.
```

Use Case 2 is the key moment: 10 reads, ~0.9ms each on average, zero database calls. Use Case 3 shows when NOT to cache: real-time or write-sensitive data should never carry the hint.

## How caching is opted in

Opt-in per query via a SQL comment hint:

```sql
-- cached for 3600 seconds (1 hour)
/* CACHE_PARAM(ttl=3600) */ SELECT id, name, price FROM products WHERE category = 'electronics'

-- never cached (no hint) — always goes to MySQL
SELECT stock FROM products WHERE id = 1
```

## Stop the infrastructure

```bash
docker compose down
```
