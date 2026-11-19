# Architecture

The source of truth is the *ThermoGrid Master Implementation and Technical Architecture Plan* (sections 4 and 5). This page points to the material in the repository; it does not restate the plan.

- **One-slide overview:** [slides/architecture.html](slides/architecture.html) (PNG: [slides/architecture.png](slides/architecture.png)). Browser, nginx, gateway, Eureka, seven business services, the RabbitMQ exchange, the database per service and the five-step control loop.
- **Diagrams (Mermaid):** [diagrams/component.mmd](diagrams/component.mmd) (components and communication), [diagrams/events.mmd](diagrams/events.mmd) (event flow), [diagrams/seq-hotspot.mmd](diagrams/seq-hotspot.mmd), [diagrams/seq-placement.mmd](diagrams/seq-placement.mmd), [diagrams/seq-cooling-failure.mmd](diagrams/seq-cooling-failure.mmd), [diagrams/alert-states.mmd](diagrams/alert-states.mmd).
- **Contracts:** REST endpoints in [api.md](api.md), events in [events.md](events.md) and [messaging.md](messaging.md), tables in [database.md](database.md).
- **Decisions and their reasons:** [decision-log.md](decision-log.md). Notable ones: owner publishes events (the scheduler publishes nothing), the cooling controller reads raw zone readings, security only at the gateway plus an internal token, polling instead of WebSockets.
- **Deployment:** [performance.md](performance.md) (Docker stack, nginx, scaling) and `infra/docker-compose.full.yml`. There is no lab server; the final deployment is Docker on one computer.

Short version: the browser talks only to the gateway; services find each other through Eureka; synchronous calls are limited to reads and one idempotent command on `/internal/**`; everything else is an event on one RabbitMQ topic exchange; each service owns one MySQL schema (the simulator an embedded H2 file); the simulator, telemetry, scheduler and cooling controller form a feedback loop in which every decision changes the next reading.
