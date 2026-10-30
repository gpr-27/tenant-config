# Benchmarks

All numbers below are from one machine on one day; the commands reproduce them.

## Machine

| | |
|---|---|
| CPU | Apple M3, 8 cores |
| Memory | 16 GB |
| OS | macOS 27.0.1 |
| JDK | OpenJDK 21.0.13 (Homebrew) |
| Docker | Colima, 4 CPU / 6 GB VM |
| Date | 2026-10-30 |
| Commit | reads: working tree before the first commit (the read path has not changed since); propagation, writes and the reconnect storm: `dbb52ae` |

Before any run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
mvn -q -B package -DskipTests
```

## 1. Read latency (JMH)

Snapshot of 50 tenants x 40 offices x 25 keys (about 50,000 overrides). `SampleTime` mode, nanoseconds,
5 warm-up and 5 measurement iterations, 1 fork. Three cases: the value is set at SHIFT, at OFFICE, or only the
GLOBAL default applies (the full walk).

```bash
# read benchmark (SampleTime, ns) and a throughput pass (ops/s)
mvn -q -B -pl load-test -am package -DskipTests
java -jar load-test/target/benchmarks.jar
java -jar load-test/target/benchmarks.jar -bm thrpt -tu s
```

| Case | p50 ns | p99 ns | p99.9 ns | ops/s |
|---|---|---|---|---|
| SHIFT hit | 41 | 83 | 167 | 19.9 M |
| OFFICE hit | 42 | 167 | 375 | 15.7 M |
| GLOBAL fallback | 83 | 167 | 375 | 17.4 M |

Raw output (sample mode, then throughput mode):

```
Benchmark Mode Cnt Score Error Units
ReadLatencyBenchmark.globalFallback sample 1320338 90.838 ± 6.634 ns/op
ReadLatencyBenchmark.globalFallback:p0.50 sample 83.000 ns/op
ReadLatencyBenchmark.globalFallback:p0.99 sample 167.000 ns/op
ReadLatencyBenchmark.globalFallback:p0.999 sample 375.000 ns/op
ReadLatencyBenchmark.globalFallback:p0.9999 sample 13741.288 ns/op
ReadLatencyBenchmark.officeHit sample 1528871 88.741 ± 13.406 ns/op
ReadLatencyBenchmark.officeHit:p0.50 sample 42.000 ns/op
ReadLatencyBenchmark.officeHit:p0.99 sample 167.000 ns/op
ReadLatencyBenchmark.officeHit:p0.999 sample 375.000 ns/op
ReadLatencyBenchmark.officeHit:p0.9999 sample 20711.219 ns/op
ReadLatencyBenchmark.shiftHit sample 1117862 41.173 ± 8.879 ns/op
ReadLatencyBenchmark.shiftHit:p0.50 sample 41.000 ns/op
ReadLatencyBenchmark.shiftHit:p0.99 sample 83.000 ns/op
ReadLatencyBenchmark.shiftHit:p0.999 sample 167.000 ns/op
ReadLatencyBenchmark.shiftHit:p0.9999 sample 7762.806 ns/op

Benchmark Mode Cnt Score Error Units
ReadLatencyBenchmark.globalFallback thrpt 5 17399522.274 ± 2103510.015 ops/s
ReadLatencyBenchmark.officeHit thrpt 5 15696948.456 ± 3182761.610 ops/s
ReadLatencyBenchmark.shiftHit thrpt 5 19869144.198 ± 4492318.761 ops/s
```

## 2. Propagation to 200 clients

200 `ConfigClient` instances against the compose server, 20 publishes. Latency per publish is measured from the
moment `POST /changes` returns to the moment each client's listener fires (`System.nanoTime`).

```bash
docker compose up --build -d
BASE=http://localhost:8080 java -cp load-test/target/benchmarks.jar com.moveinsync.config.loadtest.PropagationBenchmark
```

| Clients | Publishes | p50 ms | p99 ms | max ms |
|---|---|---|---|---|
| 200 | 20 | 0.99 | 6.9 | 7.9 |

5 of the 20 per-publish medians are negative: the listener fired before the operator's POST returned, because the
server publishes inside the same call right after commit. An earlier run on a busier machine (a Docker build in
parallel) gave p50 0.42, p99 26 and max 32 ms, all of the tail on the first, cold publish.

Raw output:

```
| publish | version | p50 ms | p99 ms | max ms |
|---:|---:|---:|---:|---:|
| 1 | 32546 | 0.76 | 5.99 | 6.07 |
| 2 | 32547 | 3.51 | 6.50 | 6.57 |
| 3 | 32548 | -1.46 | 1.23 | 1.32 |
| 4 | 32549 | 5.25 | 7.72 | 7.90 |
| 5 | 32550 | -1.64 | 1.98 | 1.99 |
| 6 | 32551 | 0.73 | 5.27 | 5.29 |
| 7 | 32552 | 0.35 | 4.71 | 4.73 |
| 8 | 32553 | 0.53 | 2.92 | 2.95 |
| 9 | 32554 | 2.19 | 4.46 | 4.67 |
| 10 | 32555 | 0.98 | 4.44 | 4.44 |
| 11 | 32556 | 0.88 | 3.58 | 3.59 |
| 12 | 32557 | 3.62 | 5.29 | 5.31 |
| 13 | 32558 | 3.01 | 6.92 | 6.97 |
| 14 | 32559 | -0.64 | 1.56 | 1.94 |
| 15 | 32560 | 1.06 | 3.38 | 3.38 |
| 16 | 32561 | 2.39 | 6.40 | 6.49 |
| 17 | 32562 | -0.43 | 2.10 | 2.17 |
| 18 | 32563 | -0.63 | 1.77 | 1.79 |
| 19 | 32564 | 1.64 | 2.94 | 2.94 |
| 20 | 32565 | 3.87 | 4.95 | 4.98 |
| all |  | 0.99 | 6.88 | 7.90 |
```

## 3. Reconnect storm

500 clients connected, server restarted. Measured: time until every client is at the current version, and the
number of `config_change` selects during the catch-up (target 0: the ring answers).

```bash
mvn -B -pl load-test -am verify -Pit -Dit.test=ReconnectStormTest -Dtest=none \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
```

| Clients | Time to all current | `config_change` selects |
|---|---|---|
| 500 | 1.9 s worst of 3 runs (1,734 / 984 / 1,898 ms); 1.7 to 1.9 s again in later full builds | 0 Hibernate statements, 0 queries |

The test stops the embedded Tomcat, which drops all 500 SSE streams, and commits two change sets while they are
down. It then empties the server's memory and rebuilds it from the database the way a fresh process does
(values, latest version and the last 1,000 log rows), restarts Tomcat, and measures until the last client reports
the current version. Hibernate statistics are read after the rebuild and again after the catch-up; every client was
served from the rebuilt in-memory ring.

```
reconnect storm: 500 clients current 1734 ms after restart; 0 SQL statements, 0 queries
reconnect storm: 500 clients current 984 ms after restart; 0 SQL statements, 0 queries
reconnect storm: 500 clients current 1898 ms after restart; 0 SQL statements, 0 queries
```

## 4. Server kill

5 clients; publish version v; stop the server container; reads keep returning v for 10 s; start it; publish v+1;
all clients reach v+1 within 5 s.

```bash
mvn -B verify -Pit
```

| Reads during outage | Time to v+1 after restart |
|---|---|
| about 200 (5 clients, sampled every 250 ms for 10 s), all returned v | under 5 s (the test's bound); the whole test, including the 10 s outage and the container restart, took 22 s |

`ServerKillIT` runs the real server image from `config-server/Dockerfile` in Testcontainers next to a Postgres
container, stops the server container with the clients connected, and starts a new one on the same host port.
During the outage every client also reports a growing time since the server last answered and a rising reconnect
count. Tokens issued before the kill still work after it, because the restarted container has the same JWT secret.

## 5. Write throughput

`hey` for 30 s with 20 concurrent workers against `POST /api/v1/tenants/acme/changes`, logged in as `acme-ops`.
Every request is one SET at OFFICE `blr-bench`. The script sends no `Idempotency-Key`, because `hey` repeats the
same headers on every request and a shared key would turn the run into one write plus replays.

```bash
docker compose up --build -d
brew install hey   # once
load-test/scripts/bench-writes.sh
```

| req/s | p50 ms | p99 ms | Non-2xx |
|---|---|---|---|
| 1,084 | 13.5 | 71.5 | 0 (32,537 requests, all 201) |

Every request is a real commit (log row + override upsert + push to the one connected demo client), taken under
the single write lock, so this is the serialised write path at full tilt: under 1 ms of lock time per change set.
Operators make a few changes a minute. An earlier run on a busier machine gave 346 req/s, p50 38 ms, p99 359 ms.

Raw output:

```
Summary:
  Total: 30.0094 secs
  Slowest: 0.1959 secs
  Fastest: 0.0084 secs
  Average: 0.0184 secs
  Requests/sec: 1084.2261
Latency distribution:
  10% in 0.0109 secs
  25% in 0.0114 secs
  50% in 0.0135 secs
  75% in 0.0198 secs
  90% in 0.0317 secs
  95% in 0.0431 secs
  99% in 0.0715 secs
Status code distribution:
  [201] 32537 responses
```

## What these numbers do not show

- Network: server, clients and Postgres share one host and talk over loopback or the Colima VM bridge. A real
  network adds latency and loss to propagation and writes.
- Scale-out: one server instance; no load balancer or proxy between clients and the SSE stream.
- Tuning: default JVM flags and GC, default HikariCP pool, default Postgres settings.
- Write mix: the write benchmark repeats one small change set; larger change sets cost more to validate and copy.
