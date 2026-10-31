# tenant-config

Hierarchical configuration for a multi-tenant cab platform. Multi-tenant means one deployment serves many client
companies (tenants); hierarchical means a setting can have a different value per client, office or shift.
Operators change settings such as `booking.cutoff` through a REST API. Every service embeds a small Java library
(`config-client`). The library holds the whole configuration in memory and answers reads from there. A committed
change reaches every connected service within 5 s over Server-Sent Events (SSE: one long HTTP response that the
server keeps writing to). If a push is lost, a version poll repairs it within 30 s. A read never makes a network
call. If the config server dies, services keep answering from their in-memory snapshot, and a service that
restarts during the outage loads its last copy from disk.

Modules:

- `config-core`: types, validation, immutable snapshot; plain Java.
- `config-client`: the embeddable library; plain Java + Jackson.
- `config-server`: Spring Boot, PostgreSQL.
- `booking-service-demo`: a service that embeds the client.
- `load-test`: benchmarks and failure tests.

## Run it

You only need Docker. Ports: 8080 for config-server, 8081 for the booking demo, 5432 for Postgres.

```bash
docker compose up --build
```

On a fresh database the eight seeded definitions take versions 1 to 8. So the first change is version 9.

```bash
# 1. token as the acme operator
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/token -H 'Content-Type: application/json' \
  -d '{"username":"acme-ops","password":"ops"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')

# 2. booking.cutoff = 30 min for office blr-ecospace only  ->  201 {"version":9}
curl -s -X POST localhost:8080/api/v1/tenants/acme/changes \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H 'Idempotency-Key: readme-1' \
  -d '{"reason":"pilot","operations":[{"op":"SET","level":"OFFICE","scopeId":"blr-ecospace","key":"booking.cutoff","value":"PT30M"}]}'

# 3. resolve with explain  ->  {"key":"booking.cutoff","value":"PT30M","source":"OFFICE","version":9}
curl -s "localhost:8080/api/v1/tenants/acme/resolve?key=booking.cutoff&officeId=blr-ecospace&explain=true" \
  -H "Authorization: Bearer $TOKEN"
```

The booking demo reads the same value from its own snapshot. It makes no call to the server:

```bash
curl -s "localhost:8081/bookings/can-book?tenantId=acme&officeId=blr-ecospace&departureInMinutes=45"
# before the change: {"allowed":false,"cutoff":"PT1H","configVersion":8}
# after the change:  {"allowed":true,"cutoff":"PT30M","configVersion":9}
```

Use `officeId=hyd-hitec` to see an office with no override. It falls back to the 60 min default. The demo gets its
first snapshot a few seconds after the config server is up. Until then, `can-book` answers 503 with the problem
detail `configuration not loaded yet`, and the demo's `/actuator/health` stays DOWN.

Metrics for the server are on `localhost:8080/actuator/prometheus` (`config_server_version`,
`config_push_connections`, `config_changes_total`, ...). Metrics for the client are on
`localhost:8081/actuator/prometheus` (`config_client_version`, `config_client_lag_versions`,
`config_client_reconnects_total`, `config_client_read_latency_seconds`, ...).

To see a failure: run `docker compose kill config-server`, repeat the can-book call, then run `docker compose start config-server`.

## The four questions

### a. Consistency model

The server is the only writer. It takes change sets one at a time. It gives each one the next number in a single
global version sequence. So every accepted change has one place in one total order.

Each client holds an immutable snapshot (a read-only copy of the whole configuration) that is exactly one of those
versions. A reader takes one snapshot and reads from it. So it can never see a state between two versions, and a
change set that touches several keys is seen completely or not at all.

Clients are eventually consistent (they can be behind the server for a while, but they catch up). The lag has a
bound:

- at most 5 s while the push stream is up;
- at most 30 s when a push is lost. The heal poll notices it (every 30 s the client asks the server for its current
  version);
- unbounded while the server cannot be reached. The client then keeps serving the last version it has, and the
  reconnect and lag metrics show how far behind it may be.

Two clients can be at different versions at the same moment. But every client goes through the same versions in
the same order and never moves backwards. There is one exception: if the server's database is restored to an
older version, clients follow it back, because the server is the truth.

### b. A client at version 43 receives version 41

Version 43 already contains everything that 41 did, so applying 41 would move the client backwards.
The client drops it and counts it (`config_client_stale_events_total`). The rule:

- apply only `local + 1`;
- drop anything `<= local`;
- on a gap (`> local + 1`), fetch everything since `local` and apply it in order.

Test: `VersionMonotonicityTest.eventWithVersion41After43IsDropped` (plus `duplicateEventIsDropped`,
`gapTriggersResyncAndAppliesInOrder`).

### c. Continuous-read test

[`config-core/src/test/java/com/moveinsync/config/core/snapshot/AtomicSnapshotConcurrencyTest.java`](config-core/src/test/java/com/moveinsync/config/core/snapshot/AtomicSnapshotConcurrencyTest.java)

- `readerNeverSeesNewCutoffWithOldMaxAdvance`: one writer flips the pair (`booking.cutoff`, `booking.maxAdvance`)
  between (PT30M, PT1H) and (PT2H, PT4H) 10,000 times. Each flip is one change set, swapped in through an
  `AtomicReference`. Eight reader threads loop the whole time. Each read takes one snapshot, reads both keys from
  it, and is counted if cutoff >= maxAdvance. The test asserts that there are zero such reads, that reads happened,
  and that the final version is exactly 10,000 above the start.
- `readingKeysFromTwoDifferentSnapshotsCanMix`: the negative control (a test that proves a mix would be detected).
  It uses no threads. It reads cutoff from the newer snapshot and maxAdvance from the older one, and asserts that
  the pair is invalid (PT2H >= PT1H). This shows that the first test would catch a mix if one could happen. It also
  shows that the guarantee comes from reading one snapshot.

### d. Benchmarks

Machine: Apple M3, 8 cores, 16 GB, macOS 27.0.1, OpenJDK 21.0.13 (Homebrew), Docker via Colima (4 CPU / 6 GB VM).
Run on 2026-10-30. The write and propagation numbers are from commit `dbb52ae`. Commands and raw output are in
[docs/benchmarks.md](docs/benchmarks.md).

Reads were measured with JMH (the standard Java micro-benchmark tool). p50, p99 and p99.9 are percentiles: 50%,
99% and 99.9% of reads were at least this fast.

| Read (JMH, 50 tenants x 40 offices x 25 keys) | p50 ns | p99 ns | p99.9 ns | ops/s |
|---|---|---|---|---|
| SHIFT hit | 41 | 83 | 167 | 19.9 M |
| OFFICE hit | 42 | 167 | 375 | 15.7 M |
| GLOBAL fallback | 83 | 167 | 375 | 17.4 M |

The timer on this Mac ticks in steps of roughly 41 ns, so the percentiles land on multiples of it. Every case is one to four hash-map lookups. The p99.99 (8 to 21 µs, not shown) is GC and scheduling noise. Throughput is from a separate `-bm thrpt` run.

| Propagation, 200 clients, 20 publishes | p50 ms | p99 ms | max ms |
|---|---|---|---|
| POST return to listener fired | 0.99 | 6.9 | 7.9 |

| Reconnect storm, 500 clients, server restarted | time to all current | `config_change` selects |
|---|---|---|
| 2 changes committed while the streams were down; server memory rebuilt from the database | 1.9 s worst of 3 runs (1.7, 1.0, 1.9) | 0 statements, 0 queries |

| Writes (`hey -z 30s -c 20`, POST /changes) | req/s | p50 ms | p99 ms |
|---|---|---|---|
| 32,537 requests, all 201 | 1,084 | 13.5 | 71.5 |

Propagation is measured from the moment the operator's `POST /changes` returns. 5 of the 20 per-publish medians
are negative. The reason: the server publishes inside the same call, right after the commit, so the push often
reaches the clients before the HTTP response reaches the operator.

The write run sends no `Idempotency-Key` (a key chosen by the caller, so that a retried request returns the first
result instead of writing again). `hey` repeats the same headers on every request, so one shared key would turn the
run into one write plus 30,000 replays. So the number covers validate, commit, apply and publish. It does not cover
the idempotency lookup, which is one indexed select on `(tenant_id, idempotency_key)`.

What these numbers do not show:

- everything ran on one host over loopback, so there is no network latency or loss between server and clients;
- Postgres ran in a VM on the same machine;
- no JVM or GC tuning beyond the defaults;
- one server instance.

## Cost: time, space and where it stops scaling

| Operation | Time | Space | Measured |
|---|---|---|---|
| Read (`get`, `explain`) | O(1): at most four hash lookups (SHIFT, OFFICE, CLIENT, then the definition default) | none per read | 41 to 83 ns p50 |
| Client snapshot | | O(definitions + overrides) for all tenants | 106 bytes per override; the 52,000-override benchmark snapshot is 5.5 MB |
| Apply a change set (client and server) | O(k + m): k operations, m entries in the scope maps they touch; everything else is shared | O(m) new map entries | |
| Validate a change set | O(k + m) for a trial apply, plus four resolves per scope of the tenant for each `lessThan` pair the change touches | O(k + m) | |
| Publish | O(connections) enqueues; each connection's write runs on one of 8 stripes | one queued frame per connection | 200 clients: p50 1.0 ms, p99 6.9 ms |
| Commit a change set | serialised: one at a time, under the write lock | one log row + k override rows | 1,084 per second, under 1 ms of lock time each |
| Reconnect catch-up | O(missed events), from the in-memory ring; O(state) for a full snapshot past the ring | ring holds 1,000 events | 500 clients current in 1.9 s, 0 queries |
| Rollback | O(tenant history): replay of that tenant's log, then a diff | O(tenant state) | |

Where it stops scaling, in the order I expect to hit the limits:

1. **Snapshot size per service.** Every service holds every tenant. At 106 bytes per override, 1 million overrides
   is about 100 MB in each of the 40 services. A full snapshot of that size is also slow to send after a long
   outage. That is the point where I would add per-tenant filtered subscriptions.
2. **Connections per server.** One instance serves at most 2,000 SSE streams. Above that, the cap answers 503. Each
   stream holds a socket, and a slow client can delay the others on its stripe. Past that limit, the next step is a
   second instance with Redis pub/sub for fan-out.
3. **Writes.** The single writer does a few hundred commits per second. Operators make a few per minute, so this
   is the last limit to matter.
4. **Rollback by replay.** The cost grows with one tenant's history. At operator rates it stays in milliseconds
   for years.

## Architecture

![architecture](docs/architecture.png)

Source: [docs/diagrams/architecture.mmd](docs/diagrams/architecture.mmd). Design note: [docs/design-note.md](docs/design-note.md).

- **ChangeSetService** holds one fair lock (it serves waiting threads in arrival order) across four steps:
  validate, the database transaction (log row + override upsert), the in-memory apply, and the publish. So version
  order = commit order = publish order.
- **PostgreSQL**: `config_change` is the append-only log and the source of truth. `config_override` is the current
  state, and it can be rebuilt from the log. `setting_definition` holds types, rules and global defaults.
- **CurrentState** is the server's own snapshot, held behind an `AtomicReference`. **RecentChanges** keeps the last
  1,000 events in a ring buffer (a fixed-size list that drops its oldest entry when full), so reconnecting clients
  catch up from memory.
- **PushHub** sends events out over SSE on 8 single-thread stripes. A slow client only delays its own stripe.
- **ConfigClient** holds the snapshot and applies only the next version. It polls `/config/version` every 30 s. It
  writes each new snapshot to disk with a temp file and an atomic rename.

## API

All paths are under `/api/v1`. Errors are RFC 7807 problem details (a standard JSON error format) with `errorCode`
and `requestId`. A 422 adds `errors[]` of `{operationIndex, key, code, message}`. A 400 for a malformed request adds
`errors[]` of `{field, code, message}`, which names the field and the broken constraint. No error repeats a
submitted value.

| Method and path | Role | Body / params -> response |
|---|---|---|
| `POST /auth/token` | public | `{username, password}` -> `{token, role, tenantId}` |
| `GET /definitions` | any | -> list of `{key, type, rules, default, description}` |
| `POST /definitions` | ADMIN | `{key, type, rules, default, description}` -> 201 `{version}` |
| `PUT /definitions/{key}` | ADMIN | `{rules?, default?, description?}` -> `{version}` |
| `POST /tenants/{t}/changes` | OPERATOR, ADMIN | `{reason (max 500), operations:[{op, level, scopeId, key, value}]}`, optional `Idempotency-Key` header -> 201 `{version}` |
| `POST /tenants/{t}/changes/staged` | OPERATOR, ADMIN | `{reason (max 500), targetOffices[], operations:[{key, value}]}` -> 201 `{version, offices}` |
| `POST /tenants/{t}/changes/{v}/promote` | OPERATOR, ADMIN | -> 201 `{version}` |
| `GET /tenants/{t}/changes?since&limit` | OPERATOR, ADMIN | `limit` 1 to 1000, default 50 -> `[{version, author, reason, kind, createdAt, operations}]` |
| `DELETE /tenants/{t}/overrides/{level}/{scopeId}/{key}` | OPERATOR, ADMIN | -> `{version}` |
| `POST /tenants/{t}/rollback` | OPERATOR, ADMIN | `{targetVersion, dryRun}` -> `{targetVersion, diff, version?}` |
| `GET /tenants/{t}/resolve?key&officeId&shiftId&explain` | any, own tenant | -> `{key, value, source?, version?}` |
| `GET /config/version` | SERVICE, ADMIN | -> `{version}` |
| `GET /config/stream` | SERVICE, ADMIN | SSE; the `Last-Event-ID` header (or `?since`) replays what was missed |
| `GET /config/snapshot?since=V` | authenticated | `{"type":"delta",...}` or `{"type":"full",...}`; other tenants are filtered out unless SERVICE/ADMIN |
| `GET /actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus` | public | liveness ignores the database, so a server that still serves reads from memory is not restarted when only Postgres is down |

To watch the push stream the way an embedded client does, run the commands below. The stream starts with a
`: connected` comment and replays everything after `Last-Event-ID`. Then it stays open, with a `: ping` every 15 s:

```bash
SVC=$(curl -s -X POST localhost:8080/api/v1/auth/token -H 'Content-Type: application/json' \
  -d '{"username":"booking-svc","password":"svc"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
curl -N localhost:8080/api/v1/config/stream -H "Authorization: Bearer $SVC" -H 'Last-Event-ID: 0'
```

`op` is `SET` or `UNSET`. `level` is `CLIENT`, `OFFICE` or `SHIFT`; GLOBAL changes go through the definition
default instead. A CLIENT `scopeId` must be the tenant id itself.

| errorCode | HTTP | When |
|---|---|---|
| `VALIDATION_FAILED` | 422 | a value, a change set or a definition breaks a rule; `errors[].code` says which one: `UNKNOWN_KEY`, `GLOBAL_SCOPE_NOT_ALLOWED`, `CLIENT_SCOPE_MISMATCH`, `CROSS_FIELD_ORDER`, `TYPE_MISMATCH`, `OUT_OF_RANGE`, `NEGATIVE_DURATION`, `WINDOW_EMPTY`, `WINDOW_END_BEFORE_START`, `OVERNIGHT_NOT_ALLOWED`, `OVERNIGHT_FLAG_MISMATCH`, `NOT_IN_ENUM`, `TOO_MANY_ITEMS`, `UNKNOWN_TYPE`, `INVALID_RULES`, `DEFINITION_EXISTS`, `NOT_A_STAGED_CHANGE` |
| `BAD_REQUEST` | 400 | malformed body, missing field, wrong parameter type or out-of-range `limit`; other HTTP errors (405, 415, ...) carry their status name in the same way |
| `UNAUTHORIZED` | 401 | no token, or an invalid one |
| `FORBIDDEN` | 403 | the role may not do this at all |
| `NOT_FOUND`, `UNKNOWN_SETTING`, `VERSION_NOT_FOUND` | 404 | another tenant (same body as any unknown id); an unknown key; a version that does not exist |
| `IDEMPOTENCY_CONFLICT`, `STAGED_CHANGE_SUPERSEDED` | 409 | the same `Idempotency-Key` with a different body; promoting a staged change that newer changes have overtaken |
| `PUSH_CAPACITY`, `STORAGE_UNAVAILABLE` | 503 | 2,000 streams are already open (with `Retry-After: 5`); the database is down |
| `INTERNAL` | 500 | anything else: "Unexpected error" and the `requestId`; details only in the log |

Postman collection: [docs/postman/tenant-config.postman_collection.json](docs/postman/tenant-config.postman_collection.json).

## Roles and seeded users

| Role | May | Seeded user / password | Tenant |
|---|---|---|---|
| ADMIN | define settings, change GLOBAL defaults, and everything below for any tenant | `admin` / `admin` | all |
| OPERATOR | change, stage, promote, roll back and read the log for its own tenant | `acme-ops` / `ops`, `globex-ops` / `ops` | acme, globex |
| EMPLOYEE | resolve values for its own tenant | `acme-emp` / `emp` | acme |
| SERVICE | read the stream, the version and the full snapshot for all tenants | `booking-svc` / `svc` | all |

Tokens are HS256 JWTs (signed JSON Web Tokens) that carry `sub`, `role`, `tenantId`, `exp`. Tenants are not
registered anywhere: a tenant is the id in the path and in the operator's token. If an OPERATOR or EMPLOYEE names
any tenant other than their own, they get **404**, not 403. The body is the same fixed body whether or not anyone
uses that id, so the answer reveals nothing about other tenants. ADMIN may write to any tenant id. `*` is reserved
for definitions and is 404 for everyone. A role that may not do the action at all (for example, an EMPLOYEE posting a change) gets 403.

## Failure modes

| What fails | What the operator / service sees | How it recovers |
|---|---|---|
| Server crashes before commit | The operator's request fails. The change never existed, and no client saw it | Retry with the same `Idempotency-Key`. The rolled-back version number is reused, so there is no gap |
| Server crashes after commit, before push | The operator may get no response. Clients stay one version behind | On restart the server rebuilds memory and the ring from the log. Clients reconnect with `Last-Event-ID` and get the event replayed. A retry with the same key returns the stored version |
| A push is lost | One client is behind | The next event shows a gap and triggers a delta fetch. If nothing else is published, the 30 s heal poll does it |
| Client offline longer than the ring holds (1,000 events) | Nothing until it reconnects | The server answers with a `resync` instruction. The client fetches a full snapshot and replaces its state |
| Disk snapshot file is corrupt | WARN in the service log | The file is treated as absent and the client fills from the server. If the server is also down, the service has no config until the server returns |
| Bad value submitted | 422 with one entry per failing operation | Nothing is written, no version is used, nothing is pushed |
| Duplicate POST after a timeout | Same `Idempotency-Key` and body: the original version. Same key, different body: 409 | Without a key, the retry becomes a second version with the same content |
| Reconnect storm after a server restart | Clients serve their snapshot in the meantime | Full-jitter backoff (each client waits a random time between 0 and its current backoff) spreads the reconnects. Each one is served from the ring with no database query. Above 2,000 connections the server answers 503 with `Retry-After: 5` |
| PostgreSQL down | Writes and logins get 503 `STORAGE_UNAVAILABLE`. Client reads, resolve, the stream and the heal poll keep working from memory | Writes succeed again once the database is back. No restart is needed |
| Commit succeeds in the database but its acknowledgement is lost | The operator sees an error. The server's memory is one version behind its own database | The next write notices that the database is ahead, reloads state from it, then continues. Clients catch up through the heal poll |
| A definition change would break `cutoff < maxAdvance` somewhere | 422 `CROSS_FIELD_ORDER` naming the scope | Nothing is written. The operator changes the overrides first or picks another value |

## Trade-offs

- **Availability and read latency over freshness.** Reads come from local memory. So they take nanoseconds and
  keep working when the server is dead. The price is bounded staleness. Writes make the opposite choice: they need
  the server and Postgres, and they fail with 503 rather than accept something that could be lost.
- **SSE over WebSocket.** Traffic goes one way only. SSE is plain HTTP, so it passes through proxies, and
  `Last-Event-ID` gives me resume-from-version without inventing a protocol. A WebSocket would add a channel I never
  use.
- **One global version, not one per tenant.** A service like booking serves all tenants and needs one cursor.
  Per-tenant versions would mean hundreds of cursors per client. Tenant separation is enforced at the API.
- **Copy-on-write snapshot over `ConcurrentHashMap`.** Copy-on-write means building a changed copy and swapping it
  in, instead of editing in place. A concurrent map makes each single put safe, but not a group of puts: two puts
  are two visible moments. I copy only the scope maps a change touches, so a change set becomes one moment. The cost
  is O(operations + size of touched maps) per change.
- **Serialised writes.** One lock covers allocate, commit, apply and publish. Operators make a few changes a
  minute, so the waiting costs nothing. It also rules out clients seeing 11 before 10.
- **Rollback by replay.** A rollback replays the tenant's log up to the target version, diffs that against the
  current state, and submits the diff as a new version. History only moves forward, and clients need no special
  case. Replay is O(tenant history), which is milliseconds at operator rates.

## What I left out

| Left out | Trigger that would add it |
|---|---|
| More than one server instance (Redis pub/sub for fan-out, shared ring) | Writes must survive a server deploy or crash, or connections get close to the 2,000 cap |
| Kafka | A second kind of consumer needs a durable, replayable change stream beyond the 1,000-event ring |
| Per-tenant filtered subscriptions | A service serves only a few tenants and the full snapshot grows past what it should hold, or the service must not hold other tenants' data |
| Checkpointed rollback | Replaying one tenant's log takes longer than about 100 ms |
| OAuth2 / SSO | Operators log in with company identities instead of seeded users |
| Secrets management | Any deployment beyond the demo; today the JWT secret and DB password are environment variables |
| Dashboards | A team watches this on call; the Prometheus metrics are already exported |
| Kubernetes | Deploying beyond one host |
| WebFlux | SSE connections per instance reach the thousands; I would try virtual threads first |

## Assumptions

- Office and shift ids are unique within a tenant. A scope is (tenant, level, id).
- Shift-to-office membership is not modelled. The caller passes `officeId` and `shiftId`. A cross-field check on a
  SHIFT value looks for its partner at SHIFT, then CLIENT, then GLOBAL. It does not see a partner set at OFFICE
  level.
- The one cross-field rule is `booking.cutoff < booking.maxAdvance`. A change set or a definition change is judged
  on the state it would produce, at every scope of the tenant where it changes the pair. A place it leaves as it
  was is not blamed for an older problem. `lessThan` must name a setting of the same type, NUMBER or DURATION.
- GLOBAL values change only through `PUT /definitions/{key}`. Definitions are never deleted, and a key's type never
  changes. A rule change that would make an existing override invalid is rejected with 422. There is no migration.
- The ring buffer holds 1,000 events. A client that is further behind takes a full snapshot.
- Embedded clients log in as SERVICE and see all tenants. Other callers of `/config/snapshot` get only their own
  tenant. Their deltas skip other tenants' versions, so gaps are expected there.
- Versions have no gaps. A failed commit's sequence number is reused, because clients apply exactly `local + 1`.
- Rollback covers one tenant's overrides. It does not cover definitions or GLOBAL defaults. A rollback that would
  restore a value that today's rules reject fails with 422. A rollback request without `"dryRun": false` is only a
  preview.
- Promote accepts a CHANGE made only of OFFICE-level SETs that agree on each key's value (a staged rollout, or a
  one-office pilot). It is refused with 409 `STAGED_CHANGE_SUPERSEDED` once any of those offices no longer holds the
  staged value. This also makes a second promote of the same version a 409.
- An UNSET of a value that is not set still writes a version. That version has no effect on state.
- There is no token revocation. Human tokens are short-lived instead.
- `config_client_lag_seconds` is the time since the stream or the server last proved alive, not the age of the
  data. Proof of life is a heartbeat (every 15 s), an applied event, or a heal poll with no lag. The value stays
  under 15 s while connected and grows during an outage. An applied event uses the server's commit time, so clock
  skew can show up there.
- Two interfaces grew beyond the original plan. `ConfigTransport.openStream` also takes `onResync` (the server's
  "fetch a snapshot" instruction) and `onAlive` (heartbeats). `ClientMetrics` adds `eventAge` and `alive` for the
  lag gauge.
- A restored database is noticed when its version is below a client's version. The heal poll then takes the
  server's full snapshot. A restore followed by enough new writes to pass a client's version would not be noticed.
  That case needs an epoch id in every response, which I left out.

## Rule -> test

| Rule | Test |
|---|---|
| Five types, rules, global default; a bad value is rejected before publish | `SettingTypeValidationTest`, `ChangeSetValidatorTest`, `ChangeSetValidationTest` |
| The most specific scope wins; explain gives the source | `ScopePathTest`, `SnapshotResolveTest`, `ResolveExplainTest` |
| Author, reason, strictly increasing version | `ChangeLogTest`, `ConcurrentSubmitTest` |
| Reads never touch the network | `ConfigClientReadTest`, `ReadLatencyBenchmark` |
| Visible within 5 s; a lost push is healed by the poll | `PushEndToEndIT`, `MissedPushHealTest`, `PropagationBenchmark` |
| Versions only move forward (41 after 43) | `VersionMonotonicityTest`, `FullSnapshotReplacesStateTest` |
| Reconnect with jitter and delta | `BackoffTest`, `RecentChangesTest`, `ReconnectStormTest` |
| Atomic change set, no mixed state; a lost commit acknowledgement does not block writes | `AtomicSnapshotConcurrencyTest`, `SnapshotApplyTest`, `ChangeSetTransactionTest` |
| Staged rollout and promote | `StagedRolloutTest` |
| Rollback to any version, with a diff | `RollbackTest`, `RollbackLockTest` |
| A definition change cannot break `cutoff < maxAdvance` | `DefinitionImpactTest` |
| Every write refused by validation or a conflict (422, 409) is logged and counted | `RejectionAccountingTest` |
| The connection cap answers 503 with `Retry-After` | `PushHubTest`, `PushCapacityResponseTest` |
| Server down: keep serving, start from disk | `ServerKillIT` (it checks lag through a test metric that records when the server last answered), `DiskSnapshotColdStartTest` |
| Roles and tenant isolation | `SecurityIT` |
| Duplicate POST | `IdempotencyTest` |
| Promote cannot overwrite newer values or run twice | `StagedRolloutTest` |
| Missing fields and bad parameters are named, never echoed | `RequestValidationTest`, `ProblemDetailTest` |
| The lag metric stays under 15 s while connected | `HeartbeatTest`, `MicrometerClientMetricsTest` |
| Errors leak nothing | `ProblemDetailTest` |

## How to run the tests and benchmarks

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # any JDK 21

mvn verify          # unit and component tests; config-server tests start one Postgres container (Docker needed)
mvn verify -Pit     # adds PushEndToEndIT, SecurityIT, ServerKillIT, ReconnectStormTest

# JMH read latency (SampleTime, ns), then a throughput pass (ops/s)
mvn -q -B package -DskipTests
java -jar load-test/target/benchmarks.jar
java -jar load-test/target/benchmarks.jar -bm thrpt -tu s

# propagation to 200 clients (needs `docker compose up` running)
BASE=http://localhost:8080 java -cp load-test/target/benchmarks.jar com.moveinsync.config.loadtest.PropagationBenchmark

# one heavy test on its own
mvn -B verify -Pit -Dit.test=ReconnectStormTest -Dtest=none \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false

# write throughput (needs `hey` and the server on localhost:8080)
load-test/scripts/bench-writes.sh
```

Testcontainers (the library the tests use to start Docker containers) finds Docker Desktop by itself. With Colima
it needs two hints. The socket is not at `/var/run/docker.sock`, and Docker 29 refuses the old default API version:

```bash
printf 'docker.host=unix:///%s/.colima/default/docker.sock\n' "$HOME" > ~/.testcontainers.properties
printf 'api.version=1.44\n' > ~/.docker-java.properties
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

Postman: import `docs/postman/tenant-config.postman_collection.json` and run it in order against a fresh database
(`docker compose down -v && docker compose up -d`). Folder 0 logs in as ADMIN, OPERATOR and SERVICE, and stores
`adminToken`, `opsToken` and `svcToken`. Each folder uses the token its role needs, and every request checks its
status code. `pilotVersion`, `stagedVersion` and `rollbackTarget` are read from the responses. To do the same run
from the command line: `npx newman run docs/postman/tenant-config.postman_collection.json`. The SSE stream is not in
the collection, because a runner would wait on it forever. Use the `curl -N` command under the API table instead.

## Monitoring: what to look at at 3 AM

All metrics are on `/actuator/prometheus`: the server on 8080, and each embedding service on its own port. Logs
are JSON with `requestId`, `tenantId` and `actor`. They contain no request bodies and no personal data.

| Question | Metric | Healthy |
|---|---|---|
| Is a service behind? (lag) | `config_client_lag_versions`, `config_client_lag_seconds` | 0 versions. Seconds stay under 15 s (the heartbeat) and climb only while the server is unreachable. It measures time since the server last proved alive, not data age |
| Is the service still getting pushes? (retries) | `config_client_reconnects_total`, `config_client_gap_resync_total` | flat; a rising reconnect count means the stream keeps dropping |
| Are duplicate or out-of-order pushes arriving? | `config_client_stale_events_total` | near 0 |
| Are operators' changes being refused? (rejections) | `config_changes_total{result="rejected"}`, `config_validation_errors_total{code}` | spikes match a human mistake, not a trend |
| Is the server failing requests? (error rate) | HTTP 5xx in `http_server_requests_seconds_count{status=~"5.."}`; WARN and ERROR log lines | 0 |
| Is push falling behind? (queue depth) | `config_push_queue_depth`, `config_push_send_failures_total` | queue near 0 between changes |
| How many services are connected? | `config_push_connections` | about one per embedding instance; far below the 2,000 cap |
| Which version is the server at? | `config_server_version`, `config_recent_changes_size` | every client's `config_client_version` equals the server's within seconds |
| Are reads fast? | `config_client_read_latency_seconds`, `config_resolve_latency_seconds` | under a microsecond on clients |

## Caching

The only cache is the snapshot. Each client holds one immutable snapshot of all definitions and overrides at one
version, plus a copy on disk for cold starts. The server holds its own snapshot and the last 1,000 events. There
is no Redis and no HTTP cache.

Eviction happens by whole snapshot: a new version replaces the snapshot in one reference swap. Nothing is ever
evicted per key, because per-key eviction would let a reader combine keys from two versions. The event ring drops
its oldest entry when full. The disk file is replaced by an atomic rename.

Staleness is at most 5 s with the stream connected (tens of milliseconds in practice). It is at most 30 s if a push
is lost, and unbounded while the server is unreachable. During an outage, `config_client_reconnects_total` keeps
climbing. When the server answers again, the heal poll records `config_client_lag_versions`.

## Tunables

| Setting | Where | Default | Why this value |
|---|---|---|---|
| SSE heartbeat | `config.push.heartbeat` | 15 s | The client drops a stream after 60 s of silence, so four missed heartbeats mark it dead |
| Heal poll | `ConfigClientOptions.healPollInterval` | 30 s | The upper bound for a lost push; about 17 req/s for 500 clients, 20 bytes each |
| Reconnect backoff | `backoffBase` / `backoffCap` | 500 ms doubling to 10 s, full jitter | Spreads a restart's reconnects over a second; a restarted server is back within seconds |
| Recent-changes ring | `config.recent.capacity` | 1,000 events | Hours of operator changes; older clients take a full snapshot |
| Max SSE connections | `config.push.maxConnections` | 2,000 | 40 services x a few instances, with room to spare; above it, 503 + `Retry-After: 5` |
| JWT lifetime | `JwtService` | 12 h humans, 30 d services | Services log in again on 401 through their token supplier, so a long life costs nothing |
| Client connect / read timeout | `ConfigClientOptions` | 5 s / 60 s | The read timeout covers four heartbeats |

Server settings come from `config-server/src/main/resources/application.yml`. The database settings and the JWT
secret come from `CONFIG_DB_URL`, `CONFIG_DB_USER`, `CONFIG_DB_PASSWORD`, `CONFIG_JWT_SECRET`. The demo reads
`CONFIG_SERVER_URL`, `BOOKING_SERVICE_USER`, `BOOKING_SERVICE_PASSWORD` and `BOOKING_SNAPSHOT_FILE` (default
`/data/snapshot.json`).
