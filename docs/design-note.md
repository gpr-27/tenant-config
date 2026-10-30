# Design note: tenant-config

## Problem

About 40 services read per-tenant settings such as `booking.cutoff` on almost every request, and a setting can be
set at GLOBAL, CLIENT, OFFICE or SHIFT level with the most specific level winning. A change has to reach all of
those services within 5 seconds, and a group of related changes must never be seen half-applied. The services
must keep working when the config server is down, so they cannot ask it on every request.

## Architecture

![architecture](architecture.png)

Source: [diagrams/architecture.mmd](diagrams/architecture.mmd)

One Spring Boot server is the single writer. It validates a change set, stores it as one row in an append-only log
in PostgreSQL with the next global version, updates its in-memory snapshot and pushes the event to every connected
client over SSE. Each service embeds `config-client`, which keeps an immutable snapshot in memory, applies events
strictly in version order, polls the version every 30 s to catch anything the push missed, and saves each snapshot
to disk so it can start while the server is down.

## Publish sequence

![publish sequence](sequence-publish.png)

Source: [diagrams/sequence-publish.mmd](diagrams/sequence-publish.mmd)

1. The operator posts a change; `@PreAuthorize` checks the role and `TenantGuard` checks the tenant.
2. `ChangeSetService.submit` takes the write lock and validates every operation against the current snapshot.
3. In one transaction it inserts the `config_change` row. **The version is assigned here**, by the database
   sequence, inside the lock. The override rows are upserted and the transaction commits.
4. Only after the commit: `CurrentState.apply` builds the server's next snapshot, the event goes into the
   1,000-entry ring, and `PushHub.publish` queues it on each connection's stripe. Then the lock is released.
5. The client receives `id: 144`. Since 144 = local + 1 it builds a new snapshot from the old one plus the change.
   **Readers switch here**, in one reference assignment. The snapshot is then written to disk (temp file + atomic
   rename) and listeners are told.
6. Booking code calls `client.snapshot()` or `client.explain(...)` and gets `PT30M`, source OFFICE, version 144,
   from memory.

## Consistency

All writes go through one server, which puts every change set into a single global version sequence. Each client
holds an immutable snapshot equal to exactly one version, and readers read from one snapshot, so no reader sees a
state between two versions and a multi-key change is all-or-nothing. Clients are eventually consistent with a
staleness bound of 5 s while pushes arrive and 30 s when a push is lost; while the server is unreachable the bound
is open, the client keeps serving its last version, and the metrics show it. Clients can briefly be at different
versions, but each one passes through the same versions in the same order and never goes back.

## Failure modes

| What fails | What is seen | Recovery |
|---|---|---|
| Server crash before commit | Request fails; no version exists | Retry with the same `Idempotency-Key`; the sequence number is reused |
| Server crash after commit, before push | Clients one version behind | Restart rebuilds state and ring from the log; `Last-Event-ID` replay or the heal poll |
| Push lost | One client behind | Gap on the next event, or the 30 s heal poll, triggers a delta fetch |
| Client offline beyond 1,000 events | Nothing until reconnect | `resync` instruction, full snapshot replaces local state |
| Disk file corrupt | WARN | Treated as absent; filled from the server |
| Bad value | 422 with `errors[]` | Nothing written or pushed |
| Duplicate POST | Original version, or 409 if the body differs | Idempotency key per tenant |
| Reconnect storm | Clients keep serving | Full-jitter backoff, ring replay with zero DB reads, 503 + `Retry-After` above 2,000 connections |
| PostgreSQL down | Writes and logins 503 | Reads unaffected; writes resume when it returns |
| Commit acknowledgement lost | Operator sees an error; server memory one version behind | Next write reloads state from the database first |
| Definition change would break `cutoff < maxAdvance` | 422 `CROSS_FIELD_ORDER` naming the scope | Nothing written; change the overrides first |
| Promote of a staged change that newer changes overtook | 409 `STAGED_CHANGE_SUPERSEDED` | Nothing written; stage again |

## Trade-offs

- I chose read availability and latency over freshness: reads are local and survive a dead server; staleness is
  bounded and measured. Writes need the server and the database and fail loudly instead.
- SSE, not WebSocket: one-way traffic, plain HTTP, and `Last-Event-ID` gives resume-from-version for free.
- One global version: a consumer serving all tenants needs one cursor, not hundreds.
- Copy-on-write snapshot: a change set becomes one visible moment, at the cost of copying the touched scope maps.
- Serialised writes: at a few changes a minute the lock costs nothing and keeps version, commit and publish order
  identical.
- Rollback by replay of the tenant's log: simple and exact; O(history), which is milliseconds at operator rates.

## Left out

More than one server instance (Redis pub/sub), Kafka, per-tenant filtered subscriptions, checkpointed rollback,
OAuth2/SSO, secrets management, dashboards, Kubernetes, WebFlux. The README lists the trigger for each.

## Assumptions

Office and shift ids are unique within a tenant. Shift-to-office membership is not modelled, so a cross-field check
on a SHIFT value does not see an OFFICE-level partner. A rollback that would restore a value the current rules
reject fails with 422 instead of succeeding, and a rollback request without `"dryRun": false` is only a preview. The
embedded client authenticates as SERVICE and sees every tenant. `config_client_lag_seconds` is the time since the
server last proved alive (a heartbeat every 15 s), so it stays under 15 s while connected. A restored database is
noticed when its version falls below a client's, and the client then takes the server's full snapshot.

## Why not the obvious way

- **Call the server on every request.** 40 services times every request is a lot of traffic for values that change
  a few times a minute, and every read would then depend on one server being up. A local snapshot answers in
  nanoseconds and does not care whether the server is alive.
- **`ConcurrentHashMap` on the client.** It makes each put thread-safe, but a change set of two keys is still two
  puts, and a reader can land between them and see a new cutoff with an old maxAdvance, which blocks every booking.
  An immutable snapshot swapped in one assignment has no in-between.
- **WebSocket for push.** The server talks and the client listens; nothing flows back. WebSocket needs an upgrade
  that some proxies handle badly and gives no built-in resume point. SSE reconnects carry `Last-Event-ID`, which is
  exactly the "I was at 143" the delta catch-up needs.
- **A version counter per tenant.** The booking service serves every tenant, so it would track hundreds of cursors
  and the server would keep hundreds of streams. Isolation between tenants is an authorisation question, handled at
  the API with `TenantGuard`, not a property of the counter.
- **A plain thread pool for fan-out.** Two events for the same connection could run on two threads and arrive as
  145 before 144, which the client would treat as a gap and resync. `SseEmitter.send` also blocks, so one slow
  client could tie up the pool. Eight single-thread stripes, each connection pinned to one, keep per-connection order
  and limit a slow client to its own stripe.
