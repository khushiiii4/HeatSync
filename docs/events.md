# RabbitMQ events

The contract is plan sections 8.4 to 8.6, mirrored in code by the `thermogrid-contracts` module (envelope, payload records, exchange and routing-key constants). Queue policies, retry, dead-lettering, idempotency and how to operate the broker are in [messaging.md](messaging.md). The flow is drawn in [diagrams/events.mmd](diagrams/events.mmd).

One durable topic exchange `thermogrid.events` (dead letters go to `thermogrid.dlx` and the queue `thermogrid.dlq`). Every message has the same envelope: `eventId` (UUID), `eventType`, `occurredAt`, `producer`, `version` and `data`. Events carry absolute state, not deltas.

| Routing key | Producer | Consumers |
|-------------|----------|-----------|
| `telemetry.rack.reading` | simulator | telemetry |
| `telemetry.zone.reading` | simulator | telemetry, cooling-controller |
| `thermal.alert.raised`, `thermal.alert.lowered`, `thermal.alert.cleared` | telemetry | scheduler, cooling-controller |
| `workload.submitted` | workload | scheduler |
| `workload.assigned`, `workload.removed` | workload | simulator |
| `cooling.unit.state` (on every change and as a 10 s heartbeat) | cooling-controller | simulator, scheduler |

The owner of the data publishes its events, so the scheduler publishes nothing: workload-service publishes `workload.assigned` after the scheduler's assignment call. Because a message published before its queue exists is lost, start consumers before producers (`scripts/start-all.sh` and the Docker `depends_on` order do this). The synthetic flood used for load tests (`POST /api/sim/flood`) publishes ordinary `telemetry.rack.reading` events for racks named `SYN001` upward.
