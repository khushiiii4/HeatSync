# Load tests (Phase 12)

| Test | Plan id | How |
|------|---------|-----|
| Read APIs, 50 users for 2 minutes | P-01 | `k6 run -e BASE_URL=http://localhost -e VIEWER_PASSWORD=... load-tests/k6-read.js` |
| Telemetry flood, 1 then 3 instances | P-02 | `scripts/flood-test.sh` (see the header for options) |
| Requests alternate between instances | P-03 | needs two workload-service instances: `docker compose ... up -d --scale workload-service=2`, then the Eureka dashboard shows both |

If k6 is not installed, run it from Docker inside the compose network (nginx is `frontend`):

```
docker run --rm -i --network thermogrid-full_default -e BASE_URL=http://frontend \
  -e VIEWER_PASSWORD="$SEED_VIEWER_PASSWORD" grafana/k6 run - < load-tests/k6-read.js
```

Passwords come from your `.env`; nothing secret is stored in these files. The measured results are in `docs/performance.md`.
