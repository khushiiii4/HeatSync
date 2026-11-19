# Database design

The contract is plan section 7. This page summarises who owns what; the full column lists are in the plan, and the tables are created by JPA (`ddl-auto=update`) and filled by idempotent seeders. Any deviation from the plan is recorded in [decision-log.md](decision-log.md) (for example D-029, `unit_health` is `VARCHAR(8)`).

| Service | Store | Tables | Notes |
|---------|-------|--------|-------|
| auth-service | MySQL `auth_db` | `users` | BCrypt hashes only; demo users seeded from configuration |
| topology-service | MySQL `topology_db` | `cooling_zone`, `rack` | 4 zones, 16 racks; neighbours follow from grid coordinates, not stored |
| telemetry-service | MySQL `telemetry_db` | `rack_reading`, `zone_reading`, `rack_forecast_state`, `alert` | Unique (rack or zone, time) keys make ingestion idempotent; readings kept 6 hours |
| workload-service | MySQL `workload_db` | `workload` | Optimistic locking (`version`); `rack_id` is a plain reference |
| scheduler-service | MySQL `scheduler_db` | `migration`, `placement_decision`, `zone_cooling_state`, `processed_event` | `zone_cooling_state` is a read model rebuilt from heartbeats |
| cooling-controller-service | MySQL `cooling_db` | `cooling_unit`, `cooling_action` | Current state per zone plus an action log |
| simulator-service | Embedded H2 file `simdb` | `failure_injection` | Live physical state is in memory and rebuilt at startup |

Rules that hold across all of them (plan section 7):
1. One MySQL user per schema, with rights on that schema only, so a cross-schema query fails at the database. `infra/mysql/init/01-schemas.sql` creates the schemas and `02-users.sh` creates the users from the `*_DB_PASSWORD` environment variables on the **first** start of an empty MySQL volume.
2. No foreign keys between services; ids such as `rack_id`, `zone_id` and `workload_id` are plain columns kept valid by the API and event contracts.
3. Data travels in messages or `/internal` REST calls, never by reading another service's schema.
4. Retention: readings 6 hours, alerts and decisions 7 days, cooling actions and processed events 24 hours (each service cleans up its own data in batches).

Changing a `*_DB_PASSWORD` after the first start does not change the user inside an existing MySQL volume; see the troubleshooting section of the README.
