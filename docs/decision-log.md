# Decision log

Plan section 14 (rule 4, rule 9) and "Change control" require every architecture, API, event, table or stack change to be recorded here before code changes. Format: ID, date, decision, reason, rejected alternatives, status.

The master plan's own decisions (section 15.7, items 1-15) are the baseline and are not repeated here.

| ID | Date | Decision | Reason | Rejected alternatives | Status |
|----|------|----------|--------|-----------------------|--------|
| D-001 | 2026-11-18 | Pin Spring Boot 3.5.16 with Spring Cloud 2025.0.3 | Plan requires Boot 3.x (section 3) and one pinned pair (section 15.8 item 4). 3.5.x is the last 3.x line and pairs with Cloud 2025.0.x | Boot 4.x (start.spring.io no longer offers 3.x; a major change to the stack) | Approved by team lead |
| D-002 | 2026-11-18 | Java release 17 | Plan allows "17 or newer"; 17 is the safest floor and builds on the installed JDK 22 | Java 21 or 22 | Approved |
| D-003 | 2026-11-18 | Root POM inherits spring-boot-starter-parent and imports the Cloud BOM | Standard plugin management with minimal configuration | Import-only Boot BOM with manual plugin setup | Approved (retained in Bundle 0.2) |
| D-004 | 2026-11-18 | Maven wrapper (mvnw) pinned to Maven 3.9.16 | Plan section 3: one Maven wrapper for all modules. Maven is not installed on the dev machine | Requiring a system Maven install | Approved || D-005 | 2026-11-18 | MySQL 8.4 and RabbitMQ 3.13-management images; ports bound to 127.0.0.1 | Plan says MySQL 8.x and RabbitMQ 3.x. 8.4 is the current LTS; localhost binding avoids exposing the dev DB | MySQL 8.0 (older) | Pending approval |
| D-006 | 2026-11-18 | Schemas in 01-schemas.sql, users and grants in 02-users.sh | .sql init files cannot read env vars, and passwords must not be committed (plan section 3) | One .sql file with hardcoded passwords | Pending approval |
| D-007 | 2026-11-18 | Eureka dev timers: server sets eviction 3 s and self-preservation off; the 3 s renewal and 9 s expiry are instance lease settings, set in eureka-server and required in every client service | In Eureka, renewal and expiry are properties of each registering instance, not of the server | Server-only configuration (would have no effect on clients) | Applied; client services must follow in their own bundles |
| D-008 | 2026-11-18 | Eureka registry cache refresh set to 3 s (response-cache-update-interval-ms) | Default 30 s cache would hide evictions from gateway and Feign for up to 30 s, defeating the 9 s demo target (scenario 7) | Default cache | Applied |
| D-009 | 2026-11-18 | spring-boot-maven-plugin added per service module (eureka-server first) | Executable JAR needed for Phase 12 Dockerfiles. Not added to the parent, because contracts must stay a plain jar | Plugin in parent | Applied |
