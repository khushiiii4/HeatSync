# Messaging operations guide (Phase 7)

How RabbitMQ is used in ThermoGrid, what each queue promises, what happens when something fails, and how to operate it. The contract is plan section 8.4 to 8.6; decisions are in [decision-log.md](decision-log.md) (D-067 onwards).

## Exchanges

| Exchange | Type | Durable | Purpose |
|----------|------|---------|---------|
| `thermogrid.events` | topic | yes | All domain events |
| `thermogrid.dlx` | topic | yes | Dead letters. Queue `thermogrid.dlq` is bound with `#` |

Every consuming service declares both exchanges and `thermogrid.dlq` on connect (identical arguments, so the declarations never conflict). Each consumer declares and owns its own queues.

## Queue inventory

**Lossless queues** carry records and commands that must not be lost: durable, no TTL, bounded (`x-overflow: drop-head`), `x-dead-letter-exchange: thermogrid.dlx`.

| Queue | Binding | Consumer | Max length | Consumers per instance | Prefetch |
|-------|---------|----------|-----------:|------------------------|---------:|
| `q.telemetry.rack-readings` | `telemetry.rack.reading` | telemetry (1 to 3 instances) | 50,000 | 1 to 3 | 20 |
| `q.telemetry.zone-readings` | `telemetry.zone.reading` | telemetry | 20,000 | 1 to 3 | 20 |
| `q.scheduler.workloads` | `workload.submitted` | scheduler (1) | 50,000 (1) | 1 | 10 |
| `q.simulator.workloads` | `workload.assigned`, `workload.removed` | simulator (1) | 50,000 (1) | Spring default (1) | 250 (default) |

(1) The plan gives a length only for the two telemetry queues (D-067).

**State queues** carry announcements that the next message replaces: durable, short `x-message-ttl`, bounded, and **no dead-letter arguments**. An expired, overflowing or invalid message is discarded.

| Queue | Binding | Consumer | TTL |
|-------|---------|----------|----:|
| `q.cooling.zone-readings` | `telemetry.zone.reading` | cooling controller | 30 s |
| `q.cooling.alerts` | `thermal.alert.#` | cooling controller | 60 s |
| `q.scheduler.alerts` | `thermal.alert.#` | scheduler | 60 s |
| `q.scheduler.cooling` | `cooling.unit.state` | scheduler | 60 s |
| `q.simulator.cooling` | `cooling.unit.state` | simulator | 60 s |

Verify the live broker against this table with `rabbitmqctl list_queues name arguments` (or the management UI, Queues tab). The test `scripts/verify-queues.sh` does it for you (see below).

## Retry and dead-letter policy

| Situation | Behaviour |
|-----------|-----------|
| Lossless message fails with a transient error (database down, timeout) | Three attempts in total. The container waits 1 s after the first failure and 2 s after the second (the 4 s step is the configured cap and is never reached with three attempts; D-068). |
| Lossless message is permanent (not JSON, wrong shape, missing field, invalid id, impossible value, unsupported `version`) | One attempt, no retry, dead-lettered at once. |
| After the last failed attempt | The message is republished to `thermogrid.dlx` (same routing key, persistent, broker-confirmed) with failure headers, and the original is acknowledged. If the republish itself fails, the message is rejected without requeue and the queue's own dead-letter arguments move it to `thermogrid.dlq`, so it is never silently lost. |
| State message is malformed or fails | Logged at WARN and dropped. No retry, no requeue, no dead letter. The next state message or the reconcile loop replaces it. |
| State message is older than its TTL | Discarded by the broker. |

Failure headers on a dead-lettered message: `x-exception-class`, `x-exception-message`, `x-exception-stacktrace` (4,000 characters), `x-retry-attempts`, `x-original-queue`, `x-original-exchange`, `x-original-routing-key`, `x-failed-at`. Messages dead-lettered by the broker itself (the fallback path, or a undeserialisable body) carry the standard `x-death` header instead.

Permanent errors are `MessageConversionException`, `IllegalArgumentException`, Jackson parse errors, `ClassCastException` and each service's own validation exceptions (`InvalidReadingException`, `MalformedEventException`).

## Inspecting and replaying the dead-letter queue

1. Open http://localhost:15672, Queues, `thermogrid.dlq`, **Get messages** (choose *Nack message requeue true* so nothing is removed). Read the headers above and the payload.
2. Fix the cause (restore the database, deploy the fix, or correct the data).
3. Replay one message with **Publish message** on the exchange `thermogrid.events` (Exchanges tab): routing key = the value of the `x-original-routing-key` header, properties `delivery_mode` = 2 and `content_type` = `application/json`, payload = the body you copied. (The queue's *Move messages* tab would also work but needs the `rabbitmq_shovel_management` plugin, which the dev image does not enable, and it would not keep the routing key for a topic exchange.) Then remove that one message from `thermogrid.dlq` (Get messages, 1 message, *Ack message requeue false*).
4. Confirm: the consumer logs the message as processed, and re-delivery is harmless because every consumer is idempotent (below), so a double replay creates no duplicate rows or actions.

Do not purge `thermogrid.dlq` before you have recorded why the messages were there.

## Idempotency (plan 8.6)

| Consumer | Key | Mechanism |
|----------|-----|-----------|
| telemetry, rack readings | `(rackId, occurredAt)` | Unique constraint, duplicate insert ignored |
| telemetry, zone readings | `(zoneId, occurredAt)` | Unique constraint |
| scheduler, `workload.submitted` | `eventId` + workload status | `processed_event` insert; only PENDING workloads are placed |
| scheduler, alerts and cooling state | none needed | Absolute state, 30 s freshness check, reconcile loop |
| simulator, `workload.assigned` / `removed` | `workloadId` | Set semantics; assigned ignored if its version is not newer; a removed id is remembered 5 min |
| simulator, `cooling.unit.state` | `zoneId` + `seq` | Lower `seq` ignored unless newer in time (D-056) |
| cooling controller, zone readings | `zoneId` + `occurredAt` | Reading must be newer than the last accepted and at most 10 s old |
| workload-service, assignment call | `migrationId` | Same id returns the same 200 body, no second change |

Limits: delivery is at least once; the exactly-once effect comes from the keys above. Telemetry instances compete for readings, so two readings for one rack can be processed out of order; the per-rack row lock and last-timestamp check keep the forecast correct (older readings are stored but do not move it).

## Listener settings

| Service | Concurrency | Prefetch | Why |
|---------|-------------|---------:|-----|
| telemetry | 1 to 3 per instance | 20 | Competing consumers; ordering is protected by the row lock and timestamp guard, not by the broker |
| scheduler | 1 | 10 | Single decision maker; bounded in-memory backlog |
| cooling controller | 1 | 10 | One consumer keeps zone readings in order |
| simulator | 1 | 250 (Boot default) | Single instance, tiny messages; replays are applied as set operations |

Acknowledgement is automatic after the listener method returns (Spring AMQP `AUTO`): a message is acknowledged only when it was fully processed, or after it was dead-lettered or deliberately dropped (state queues).

## Publisher reliability

Telemetry (alerts), workload, simulator and cooling controller publish through `ConfirmingRabbitTemplate`:

* durable exchange, persistent messages, `content_type application/json`, envelope per plan 8.5;
* publisher confirms (`publisher-confirm-type: correlated`) keyed by the message id: a nack is logged at ERROR;
* publisher returns and `mandatory=true`: a message that matches no queue is logged at WARN (first one, then every 100th);
* bounded behaviour when the broker is down: a connection timeout of 1 to 2 s, then each publisher's own cooldown or bounded retry (simulator and cooling controller skip for 10 s and 5 s; telemetry and workload make 3 attempts 200 ms apart). Nothing is buffered in memory.

Remaining dual-write limitations (accepted, plan 15.6): the database commit and the publish are not atomic in telemetry (alerts) and workload-service. A lost alert event is repaired by the scheduler's reconcile loop and the cooling controller's own temperature term; a lost `workload.assigned` by the simulator's 60 s resync. A lost `workload.submitted` is picked up by the scheduler's 3 s pending loop. A transactional outbox is listed as a future enhancement.

### Cooling controller and its database

The controller persists a unit's state **before** it publishes it, and never publishes a state the database does not hold. If the database write fails, control keeps running in memory (so cooling is not interrupted), nothing is published for that change, the unit is marked persist-pending and the write is retried every second. When it succeeds, the latest absolute state is published with the stored `seq`. The simulator therefore holds the last published output while the database is down (as it does whenever the controller is silent), and `seq` can never go backwards after a restart. A broker failure is separate: the state is stored and the next heartbeat (5 s) re-sends it.

## Broker outage behaviour

| Event | Result |
|-------|--------|
| RabbitMQ stops | Publishers fail fast and skip; the simulator skips ticks; consumers reconnect automatically (Spring AMQP default, every 5 s); durable queues keep what was acknowledged. |
| RabbitMQ restarts | Consumers re-attach and redeclare their queues; the simulator resumes publishing; no service restart is needed. Messages published while the queues were missing are lost (a queue must exist before a message is published to it), which is why consumers start first. |
| Messages sent while no consumer is up | Lossless queues keep them (up to the max length, oldest dropped first). State queues expire them. |

## Startup and shutdown

`scripts/start-all.sh` (Git Bash, Linux, macOS) or `scripts/start-all.ps1` (Windows PowerShell) start, in this order: MySQL and RabbitMQ, eureka-server, topology, telemetry, workload, scheduler, cooling controller, simulator, gateway. Each step waits for the port to open and for the service to show `UP` in Eureka, and fails with the log tail if the process exits. A service that already runs (from its pid file in `.run/`) is not started twice; a port held by another process is reported and **not** killed.

```
scripts/start-all.sh                 # everything
DRY_RUN=1 scripts/start-all.sh       # print the plan only
SKIP_BUILD=1 scripts/start-all.sh    # skip "mvnw install"
scripts/stop-all.sh                  # stop only what start-all.sh started, in reverse order
scripts/test-start-order.sh          # check the order and readiness steps without starting anything
```

Secrets come from the environment or the git-ignored `.env`; nothing is stored in the scripts. The Docker containers keep running after `stop-all.sh`.

## Changing queue arguments on an existing broker (migration)

RabbitMQ refuses to redeclare a queue with different arguments (`PRECONDITION_FAILED - inequivalent arg`). Phase 7 removed `x-dead-letter-exchange` from the five state queues, so a broker that was running Phase 5 or 6 still holds the old declarations and the affected service will fail to start its listener. The services never delete queues automatically. Safe procedure, per affected queue:

1. Stop the consuming service (and, to avoid loss, leave the producers running: state messages are replaceable).
2. Check the queue is empty or holds only replaceable state: `rabbitmqctl list_queues name messages`.
3. Delete just that queue: `rabbitmqctl delete_queue q.scheduler.alerts` (or the UI, Queues, Delete).
4. Start the service; it declares the queue with the new arguments.

Affected queues: `q.cooling.zone-readings`, `q.cooling.alerts`, `q.scheduler.alerts`, `q.scheduler.cooling`, `q.simulator.cooling`. The lossless queues did not change and need no migration.

## Checking a live broker

`scripts/verify-queues.sh` reads `rabbitmqctl list_queues` through `docker exec thermogrid-rabbitmq` and checks every queue of the tables above (durable, TTL present or absent, dead-letter argument present or absent, bindings). It changes nothing.
