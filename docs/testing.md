# Testing results

Source: plan section 12 and roadmap Phase 10. Everything below was run on 2026-11-19 on the development machine (Windows 11, JDK 22, Node 22, MySQL 8.4 and RabbitMQ 3.13 in Docker, all services started with `scripts/start-all.sh`, gateway on port 18080 because 8080 is held by an unrelated process). Anything not run is listed under "Not run" and is not claimed as passing.

## 1. How to run

| What | Command |
|------|---------|
| Everything (unit, slice, context tests, all Java modules) | `./mvnw -B verify` |
| One service | `./mvnw -pl <module> -am test` (reports in `<module>/target/surefire-reports`) |
| Frontend tests and build | `cd frontend && npm test && npm run build` |
| Live-broker tests (throwaway broker only, see section 4) | set `THERMOGRID_LIVE_RABBIT_PORT`, then `./mvnw test -Dtest=LiveBrokerTest -Dsurefire.failIfNoSpecifiedTests=false -pl telemetry-service,scheduler-service,simulator-service,cooling-controller-service` |
| End to end through the gateway | start the stack, then `scripts/e2e.sh` (uses `.env`; `GATEWAY_URL=http://host:port` to override) |
| Manual requests | `scripts/demo.http` (VS Code REST Client or IntelliJ) or import `postman/thermogrid.postman_collection.json` |

## 2. Unit, slice and context tests (`./mvnw -B verify`: BUILD SUCCESS, exit 0)

| Module | Tests run | Failures/errors | Skipped |
|--------|-----------|-----------------|---------|
| thermogrid-contracts | 23 | 0 | 0 |
| thermogrid-internal-security | 8 | 0 | 0 |
| eureka-server | 1 | 0 | 0 |
| api-gateway | 28 | 0 | 0 |
| auth-service | 11 | 0 | 0 |
| topology-service | 16 | 0 | 0 |
| simulator-service | 142 | 0 | 4 (live broker) |
| telemetry-service | 106 | 0 | 5 (live broker) |
| workload-service | 29 | 0 | 0 |
| scheduler-service | 104 | 0 | 4 (live broker) |
| cooling-controller-service | 76 | 0 | 2 (live broker) |
| **Total** | **544** | **0** | **15** |

Every service meets the plan's minimum (8 test methods; telemetry and scheduler 10 or more). The 15 skips are the `LiveBrokerTest` classes, which are only enabled against a throwaway broker (section 4). The full `./mvnw -B verify` was run twice: once before the Phase 10 telemetry query fix (section 3) and once again on the final branch (commit `1efb76c`, which includes the fix). Both gave BUILD SUCCESS with the same totals shown above: 12 modules, 544 tests, 0 failures, 0 errors, 15 skipped. The 15 skipped live-broker tests were run separately against a temporary RabbitMQ container and passed (section 4); they were not re-run during the final `verify`.

Frontend: `npm test` 6 files, 64 tests passed; `npm run build` succeeded (chunk-size warning only).

## 3. End-to-end checks (`scripts/e2e.sh`, through the gateway, secured setup)

Final run: **38 passed, 0 failed** (stack freshly restarted, 36 demo workloads running, all 8 services UP in `/api/platform/services`).

| Plan id | What is checked | Result |
|---------|-----------------|--------|
| A-01 | Login for viewer, operator and admin returns a token; wrong password and unknown user both 401 | Pass |
| S-01 | No token: 401 | Pass |
| S-02 | Tampered signature and garbage token: 401 (an expired token is covered by the gateway and auth unit tests; it cannot be minted without the secret) | Pass |
| S-03 | VIEWER POST: 403; VIEWER GET: 200 | Pass |
| S-04 | OPERATOR `/api/sim/control` and `/api/workloads/seed-demo`: 403 | Pass |
| S-05 | Direct `/internal/**` on topology, workload and telemetry without the token or with a wrong one: 401; with the real token: 200 | Pass |
| S-06 | Forged `X-User-Role: ADMIN` with a VIEWER token: 403; `/api/auth/me` still reports VIEWER | Pass |
| S-07 | `/internal/topology` through the gateway, even for ADMIN: 404 | Pass |
| A-05 | One GET per route prefix (topology, sim, telemetry, alerts, workloads, scheduler, cooling, platform): 200 | Pass |
| A-03 | `racks/latest` returns 16 racks | Pass |
| A-04 | Failure injection for unknown zone Z9: 400 | Pass |
| A-02 | `cpuDemand` 0 and 101: 400; a valid body: 201 PENDING | Pass |
| I-01 | The submitted workload is RUNNING on a rack within 10 s (observed R33) and a placement decision exists for it; VIEWER delete 403; OPERATOR removes it | Pass |
| I-02 | Zone readings present for all 4 zones through the gateway | Pass after the fix below |

**Defect found and fixed (D-091).** The first run failed I-02: `GET /api/telemetry/zones/latest` returned 503 through the gateway. Called directly the service answered 200 but took about 15 s (three runs: 15.2, 14.9 and 15.3 s), above the gateway's 5 s timeout. Cause: a correlated `max()` subquery in `ZoneReadingRepository.findLatestPerZone` walked every stored reading. After replacing it with a grouped max the same call takes about 0.05 s (0.069, 0.044 and 0.020 s) and the e2e run is 38/38. The dashboard does not call this endpoint, which is why it was not seen earlier.

## 4. Messaging tests against a real broker (I-04, I-05, I-06 and related)

The `LiveBrokerTest` classes publish poison messages and read `thermogrid.dlq`, so they must never run against the development broker. They were run against a temporary RabbitMQ 3.13 container started only for this purpose (port 25672, user `p7test`) and removed afterwards; the development containers were not touched.

| Module | Tests | Result |
|--------|-------|--------|
| simulator-service | 4 | Pass |
| telemetry-service | 5 | Pass |
| scheduler-service | 4 | Pass |
| cooling-controller-service | 2 | Pass |

These cover dead-lettering after 3 attempts (I-04), malformed JSON dead-lettered at once (I-05), duplicate messages harmless (I-06), set semantics for assignments, sequence ordering of cooling state, and dropping of bad state messages without dead-lettering.

## 5. Not run in Phase 10

| Item | Status |
|------|--------|
| Postman collection executed | **Not run.** Newman is not installed. The collection was generated and parsed as valid JSON only; the same requests are exercised by `scripts/e2e.sh` |
| `scripts/demo.http` executed | **Not run** (needs an editor extension); its requests mirror the e2e calls |
| I-03 (WARNING raises cooling and a migration), F-01 scenarios 6 to 9, F-02 sensor dropout, F-03 broker restart, I-07 three telemetry instances, I-08 instance stop and Eureka removal, I-09 telemetry restart | **Not run.** Failure and scaling evidence belongs to Phases 11 and 12; earlier phases recorded their own runs in `docs/status.md` |
| Performance P-01 to P-03 (k6, flood) | **Not run** (Phase 12; k6 is not installed) |
| Frontend browser scripts (`frontend/scripts/verify-roles.mjs`, screenshots) | **Not run** in Phase 10 |
| Expired-token case against the live stack | Covered only by unit tests (gateway and auth) |
| Code coverage (JaCoCo) | Not measured (optional in the plan) |
| Gateway on default port 8080 | Not tested: 8080 is occupied on this machine; everything ran on 18080 |
| `scripts/e2e.sh` (38/38) during the final verification | Not repeated. It was run after the telemetry fix (with the rebuilt telemetry jar); the final verification re-ran only `./mvnw -B verify` |

## 6. Deviations from the plan

- `scripts/e2e.sh` uses plain curl and sed instead of jq (jq is not installed).
- Test class names differ from plan section 12.2 in places (the services were built with their own names); the required behaviours of section 12.3 are covered, with the exceptions listed above.
- One production query was changed in Phase 10 (D-091). No other source file outside tests, scripts and docs changed.
