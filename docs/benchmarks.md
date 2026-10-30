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
| Commit | working tree of 2026-10-30, before the first commit |

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
| 200 | 20 | 0.42 | 26.0 | 32.0 |

The max and the p99 both come from the first publish (JIT warm-up on server and clients); publishes 2 to 20 all completed under 15 ms. Negative values mean the listener fired before the operator's POST returned.

Raw output:

```
| publish | version | p50 ms | p99 ms | max ms |
|---:|---:|---:|---:|---:|
| 1 | 9 | 18.49 | 31.40 | 31.95 |
| 2 | 10 | 3.68 | 11.70 | 11.79 |
| 3 | 11 | 1.62 | 4.43 | 4.49 |
| 4 | 12 | 3.86 | 8.29 | 8.33 |
| 5 | 13 | -0.39 | 1.53 | 1.57 |
| 6 | 14 | 2.54 | 10.51 | 10.54 |
| 7 | 15 | -9.67 | 1.88 | 2.07 |
| 8 | 16 | 5.22 | 14.48 | 14.67 |
| 9 | 17 | -0.05 | 6.37 | 6.45 |
| 10 | 18 | -2.66 | 2.11 | 2.14 |
| 11 | 19 | -5.77 | -0.91 | -0.90 |
| 12 | 20 | 3.65 | 8.68 | 8.70 |
| 13 | 21 | 5.58 | 14.94 | 14.95 |
| 14 | 22 | -13.20 | -1.61 | -1.59 |
| 15 | 23 | -6.57 | 0.82 | 0.92 |
| 16 | 24 | 1.07 | 8.33 | 8.35 |
| 17 | 25 | 3.74 | 5.85 | 5.89 |
| 18 | 26 | -2.42 | 2.47 | 2.50 |
| 19 | 27 | 3.00 | 6.24 | 6.29 |
| 20 | 28 | -0.53 | 1.75 | 1.75 |
| all |  | 0.42 | 26.03 | 31.95 |
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
| 500 | 1.9 s worst of 3 runs (1,734 / 984 / 1,898 ms) | 0 Hibernate statements, 0 queries |

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
| 346 | 38.3 | 358.8 | 0 (10,414 requests, all 201) |

Every request is a real commit (log row + override upsert + push to the one connected demo client), taken under
the single write lock, so this is the serialised write path at full tilt: about 2.9 ms per change set end to end.
Operators make a few changes a minute.

Raw output:

```
Summary:
  Total:        30.0633 secs
  Slowest:      1.3461 secs
  Fastest:      0.0097 secs
  Average:      0.0577 secs
  Requests/sec: 346.4028

Latency distribution:
  10% in 0.0129 secs
  25% in 0.0200 secs
  50% in 0.0383 secs
  75% in 0.0695 secs
  90% in 0.1143 secs
  95% in 0.1452 secs
  99% in 0.3588 secs

Status code distribution:
  [201] 10414 responses
```

## What these numbers do not show

- Network: server, clients and Postgres share one host and talk over loopback or the Colima VM bridge. A real
  network adds latency and loss to propagation and writes.
- Scale-out: one server instance; no load balancer or proxy between clients and the SSE stream.
- Tuning: default JVM flags and GC, default HikariCP pool, default Postgres settings.
- Write mix: the write benchmark repeats one small change set; larger change sets cost more to validate and copy.
