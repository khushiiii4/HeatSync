# Viva questions and answers

Short answers a team member can say aloud, with the evidence behind each one. Every figure comes from `docs/status.md`, `docs/testing.md`, `docs/performance.md` or `docs/decision-log.md`; if you are asked for more detail than is here, open those. Each person must be able to explain every line they submitted (plan rule 14).

## 1. Why a database per service?

**Short answer.** So each service owns its data and can change, fail and scale on its own, and so no service can quietly depend on another's tables. Six services use MySQL, each with its own schema and its own MySQL user that has rights on that schema only. The simulator keeps a small embedded H2 file for its failure-injection log, because the physical state is rebuilt from topology and workload data at startup.

**Evidence.** Phase 0 checked a 6 x 6 matrix: each user can use its own schema and is refused on the other five (36 of 36 as expected). There are no foreign keys between services; `rack_id`, `zone_id` and `workload_id` are plain columns, kept valid by the API and event contracts. Data moves by events (for example `workload.assigned` carries the CPU demand and priority so the simulator never has to call workload-service for each event) or by `/internal` REST calls.

**Follow-ups.**
- *Cost?* No joins across services and some data is held twice (the scheduler's `zone_cooling_state` is a read model rebuilt from the cooling heartbeats). That is the price of independence.
- *One MySQL server for six schemas?* Yes, for the lab. The separation is logical and enforced by credentials; separate servers would be the production choice.
- *Why is topology its own service when it is almost CRUD?* Four services need the same structure and none may own it; consumers cache it for 30 s and keep the last good copy, so a topology outage does not stop a control loop.

## 2. Why event-driven communication?

**Short answer.** The loop is naturally asynchronous: a reading is a fact that several services react to at their own pace. RabbitMQ decouples them, buffers when a consumer is down, and lets us add consumers without changing the producer. We still use synchronous REST, but only for reads and one idempotent command.

**How it works.** One durable topic exchange, `thermogrid.events`, with nine routing keys. Each consumer declares and owns its own queues. Two queue policies: lossless queues (readings, workloads) have no TTL and dead-letter after 3 failed attempts; state queues (alerts, cooling state) have a short TTL because the next message replaces them. The service that owns the data publishes its events, so workload-service, not the scheduler, publishes `workload.assigned`.

**Reliability.** Brokers deliver at least once, so every consumer tolerates duplicates: a unique key on (rack, timestamp) for readings, `processed_event` for submissions, `migrationId` for assignments, and a `seq` check for cooling state. Events carry absolute state, not deltas, and the scheduler's 5-second reconcile loop re-reads current state, so a lost or stale event costs at most one cycle. Poison messages go to `thermogrid.dlq`.

**Evidence.** 15 live-broker tests against a real RabbitMQ (dead-letter after 3 attempts, malformed JSON dead-lettered at once, duplicates harmless). Scenario 9 stopped telemetry: readings piled up in `q.telemetry.rack-readings` and drained 8 to 11 seconds after restart with no new dead letters. A flood of 30,000 messages was stored exactly once each, with 1 and with 3 telemetry instances.

**Follow-ups.**
- *Why not Kafka?* RabbitMQ covers every need here and its management UI helps the demo (plan section 3).
- *What if the publish fails after the database commit?* That dual-write risk is known: after-commit publish with retry, plus the simulator's 60-second resync. A transactional outbox is a listed enhancement, not built.
- *Why does the scheduler publish nothing?* Owner publishes: the scheduler decides, workload-service owns the record and announces the result.

## 3. Why does the dashboard poll instead of using WebSockets?

**Short answer.** Polling is simpler and makes the resilience visible: each panel is its own query with its own refresh interval (2 to 5 seconds) and its own error boundary. When the scheduler stops, only the decision feed and the scheduler badge degrade; the heat map keeps updating. That behaviour is part of the demonstration.

**Details.** A failed refresh keeps the last data and shows a stale badge with the last **server** timestamp, so a wrong laptop clock cannot make data look stale. Polling pauses when the tab is hidden. Server-sent events are an optional upgrade for the heat map only.

**Trade-offs.** Up to a few seconds of latency and more requests than a push channel. The k6 test shows the cost is small: 50 users polling for 2 minutes gave 0.00 percent errors and a p95 of 21 ms on the local Docker stack.

## 4. Why a proportional cooling controller and not PID?

**Short answer.** The simulated plant is first-order and noisy. A derivative term would amplify sensor noise, and an integral term risks windup when the output saturates at 100 percent. Tuning a PID costs hours and does not improve the demo. A proportional controller with alert feed-forward is explainable and testable, and its steady offset of about 0.2 C is acceptable.

**The law.** Per zone, every 2 seconds: `hot = smoothed max temperature + 5 x slope` (it looks about 10 s ahead); `raw = 35 + 12 x (hot - 26 C target)`; `desired = min(100, max(raw, floor, boost))`, where the floor is 30 percent (60 percent while any unit is faulty) and the boost comes from alerts (WATCH 50, WARNING 70, CRITICAL 100, held for a while after the alert clears). The output moves toward `desired` by at most 10 points per reading, so it never jumps.

**Safety.** No zone reading for 10 seconds puts the unit in FAILSAFE at 80 percent; a unit fault confirmed by 2 readings makes healthy zones raise their floor to 60 percent. The controller reads zone readings directly, not telemetry-service output, so it works when telemetry is down (shown in scenario 9).

**Honest points.** The energy saving shown is **modelled** (fan power follows a cube law against a static 70 percent baseline), not measured. The physics and thresholds are calibrated starting values: the plan's degraded-cooling example (efficiency 0.25) did not reach WARNING with the controller running, so the demo uses 0.15 (decision D-093). A PI controller is a listed enhancement.

## Other questions you may get

| Question | Short answer |
|----------|--------------|
| How do you predict 60 s ahead? | Damped Holt exponential smoothing (level and trend, trend damped by 0.95), forecast 30 ticks ahead. Explainable, about 25 lines, testable with fixed series; no machine learning. Risk levels use hysteresis: 5 calm readings and 2 C below the threshold before a level is lowered |
| How does the scheduler choose a rack? | Filters (rack active, zone cooling not FAULT, sensor OK, risk below WARNING, utilization at most 85 percent, forecast plus 0.08 C per CPU unit below 32 C), then the lowest score; a tie within 0.02 goes to the rack with less free capacity (best fit). The top three candidates are saved so the dashboard can explain the choice |
| How do you stop work bouncing between racks? | Cooldowns: 120 s per workload, 20 s per source rack, 60 s destination quarantine, in-cycle reservation of capacity, at most 2 moves per cycle (4 if critical or draining a failed zone) |
| Why are the scheduler, simulator and cooling controller single instances? | They are single decision makers or own a clock or an output; two schedulers would plan the same migration twice. There is no leader election (out of scope). It is not highly available and says so |
| Where is security enforced? | At the gateway: JWT (HS256, 8 h) check, role rules (VIEWER reads, OPERATOR operates, ADMIN controls the simulator and seeds), and removal of client-supplied identity headers. `/internal/**` is never routed and needs a shared token. Services run no Spring Security chain. 401 and 403 cases are tested, including a forged `X-User-Role` |
| What happens when RabbitMQ restarts? | Consumers reconnect, queues are durable; the failure test restarted the broker with no service restarted and readings resumed in 3 to 6 seconds |
| What did load testing find? | Two real defects: a 15-second query behind `/api/telemetry/zones/latest` (gateway 503), and a default 25-call bulkhead in the gateway that opened the circuit at 50 concurrent logins. Both were fixed with regression tests (decisions D-091 and D-095) |
| What is not done? | A lab-server deployment (there is none: the system runs in Docker on one computer); high availability for the single-instance services; the transactional outbox; real hardware (everything is simulated); a rehearsed live demo (see `docs/demo-checklists.md`) |
