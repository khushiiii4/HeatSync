# ThermoGrid: run and demonstration guide

This guide takes you from a fresh checkout to a running, secured ThermoGrid dashboard, and shows how to demonstrate it and check its security. Every command below uses scripts and paths that exist in this repository. Nothing here contains a real secret: your passwords and signing secrets live only in your local, git-ignored `.env` file.

Commands are shown for **Windows PowerShell** (primary) and **Git Bash** where they differ. Run everything from the repository root (`HeatSync/`) unless a step says otherwise.

What was and was not tested is stated honestly in [section G](#g-dashboard-demonstration) and in [docs/status.md](status.md).

---

## A. Prerequisites

| Tool | Needed for | Check it | Expected |
|------|------------|----------|----------|
| JDK 17 or newer (the project targets 17; 22 also works) | all Java services | `java -version` | `17` or higher |
| Node.js 18 or newer and npm | the React frontend | `node -v` then `npm -v` | `v18`+ and a version number |
| Docker Desktop (running) | MySQL and RabbitMQ | `docker version` then `docker compose version` | client **and** server sections, no error |
| Git | source control | `git --version` | any recent version |

Maven does **not** need to be installed: the repository ships the wrapper (`mvnw`, `mvnw.cmd`).

Make sure Docker Desktop is open and shows "Engine running" before you continue.

Ports used on your machine (all on `localhost`): 3306 MySQL, 5672 and 15672 RabbitMQ, 8761 Eureka, 8081 auth, 8082 topology, 8083 simulator, 8084 telemetry, 8085 workload, 8086 scheduler, 8087 cooling, **8080 gateway** (or the port you choose, see section C4), 5173 frontend.

---

## B. First-time setup

### B1. Create your `.env`

```powershell
Copy-Item .env.example .env          # PowerShell
```
```bash
cp .env.example .env                 # Git Bash
```

Open `.env` in an editor and replace every `CHANGE_ME` value. `.env` is listed in `.gitignore`, so it is never committed. Keep passwords simple (letters and digits are safest).

What each group means:

| Variable(s) | Purpose |
|-------------|---------|
| `MYSQL_ROOT_PASSWORD`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | the two Docker containers |
| `AUTH_DB_PASSWORD`, `TOPOLOGY_DB_PASSWORD`, `TELEMETRY_DB_PASSWORD`, `WORKLOAD_DB_PASSWORD`, `SCHEDULER_DB_PASSWORD`, `COOLING_DB_PASSWORD` | one MySQL user per service, created on the **first** start of MySQL |
| `THERMOGRID_JWT_SECRET` | signs and verifies login tokens. At least 32 characters. **auth-service and the gateway refuse to start without it** |
| `THERMOGRID_INTERNAL_TOKEN` | shared secret for service-to-service `/internal/**` calls. **Every service that has or calls internal endpoints refuses to start without it** |
| `THERMOGRID_SECURITY_ENABLED` | leave `true` (the secured setup). Setting `false` is only for a deliberate local experiment: it switches off the gateway JWT check and the internal-token check |
| `SEED_VIEWER_PASSWORD`, `SEED_OPERATOR_PASSWORD`, `SEED_ADMIN_PASSWORD` | passwords of the three demo users (section D) |
| `GATEWAY_PORT` | port of the API gateway, default `8080` |

### B2. Generate the two secrets safely

PowerShell (prints a 64-character random value; paste it after `THERMOGRID_JWT_SECRET=`, then run again for `THERMOGRID_INTERNAL_TOKEN=`):

```powershell
-join ((48..57)+(65..90)+(97..122) | Get-Random -Count 64 | ForEach-Object {[char]$_})
```

Git Bash:

```bash
openssl rand -base64 48
```

Never share these values, paste them into chat or commit them. If one leaks, generate a new one, put it in `.env` and restart everything (old login tokens stop working, which is what you want).

### B3. Demo users

You do not create users by hand. When `auth-service` starts it creates (or updates) three users from `.env`:

| Username | Role | Password comes from |
|----------|------|---------------------|
| `viewer` | VIEWER | `SEED_VIEWER_PASSWORD` |
| `operator` | OPERATOR | `SEED_OPERATOR_PASSWORD` |
| `admin` | ADMIN | `SEED_ADMIN_PASSWORD` |

Passwords are stored only as BCrypt hashes. To change a password: edit the value in `.env`, then restart `auth-service` (see section I); the stored hash is updated on startup. To change a username, add for example `SEED_VIEWER_USERNAME=alice`. To supply a ready-made BCrypt hash instead of a plain password, set `SEED_VIEWER_PASSWORD_HASH=$2a$...` (the hash wins).

### B4. Start MySQL and RabbitMQ, and create the databases

The start script does this for you (section C1). To do it by hand:

```powershell
docker compose --env-file .env -f infra/docker-compose.yml up -d
docker ps --format "table {{.Names}}\t{{.Status}}"      # both should say (healthy)
```

**Database initialisation is automatic, but only on the first start with an empty data volume.** MySQL runs `infra/mysql/init/01-schemas.sql` (creates `auth_db`, `topology_db`, `telemetry_db`, `workload_db`, `scheduler_db`, `cooling_db`) and `02-users.sh` (one user per schema, with the `*_DB_PASSWORD` values from `.env`). The tables inside are created by each service when it starts.

Important consequence: the `*_DB_PASSWORD` values in `.env` must match the ones the volume was first created with. If you change one later, the service cannot log in (see Troubleshooting, "MySQL connection failures"). Do **not** delete the volume to fix this unless you accept losing the data.

### B5. Install the frontend dependencies (once)

```powershell
cd frontend
npm install
cd ..
```

---

## C. Exact startup commands

### C1. Infrastructure plus all backend services (one command)

PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1
```

Git Bash:

```bash
scripts/start-all.sh
```

The script reads `.env`, builds the project once (`mvnw -DskipTests install`, takes a couple of minutes the first time), starts Docker MySQL and RabbitMQ, then starts the services **in this order**, waiting until each is up and registered in Eureka before starting the next:

1. MySQL and RabbitMQ (Docker)
2. `eureka-server` (8761)
3. `auth-service` (8081)
4. `topology-service` (8082)
5. `telemetry-service` (8084)
6. `workload-service` (8085)
7. `scheduler-service` (8086)
8. `cooling-controller-service` (8087)
9. `simulator-service` (8083)
10. `api-gateway` (8080 or `GATEWAY_PORT`)

Consumers start before producers on purpose, so no message is published before its queue exists. When it finishes you see `all services started` and the URLs. Useful options:

| Option (PowerShell / Git Bash) | Meaning |
|--------------------------------|---------|
| `-DryRun` / `DRY_RUN=1` | print the plan, start nothing |
| `-SkipBuild` / `SKIP_BUILD=1` | skip the Maven build (faster second start) |
| `-NoInfra` / `START_INFRA=0` | do not touch Docker (MySQL and RabbitMQ already running) |

Each service runs in the background; its log is `.run/logs/<service>.log` and its process id is `.run/<service>.pid`.

### C2. Start the frontend

In a **second** terminal:

```powershell
cd frontend
npm run dev
```

If your gateway is not on 8080, set the port first (section C4). Open <http://localhost:5173>.

### C3. Seed the demo workloads (once)

Sign in as `admin`, open **Control Room**, press **Seed demo workloads**. It adds 36 workloads (720 CPU units) and the scheduler places them. It is safe to press again: it reports "already present". (Command-line equivalent in section F.)

### C4. If port 8080 is already in use

The start script refuses to start the gateway on a busy port and kills nothing. Pick another port, for example 18080. Either put `GATEWAY_PORT=18080` in `.env`, or:

```powershell
$env:GATEWAY_PORT = 18080
powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1
cd frontend
$env:GATEWAY_PORT = 18080        # the dev server proxies /api to this port
npm run dev
```
```bash
GATEWAY_PORT=18080 scripts/start-all.sh
cd frontend && GATEWAY_PORT=18080 npm run dev
```

(Note: `.env` is read by the start script, not by the frontend dev server, so set `GATEWAY_PORT` in the frontend terminal too.) To see who holds 8080: `Get-NetTCPConnection -LocalPort 8080 -State Listen`.

### C5. Confirm every service is registered in Eureka

Open <http://localhost:8761>. Under "Instances currently registered" you should see **eight** applications, each `UP (1)`: `API-GATEWAY`, `AUTH-SERVICE`, `COOLING-CONTROLLER-SERVICE`, `SCHEDULER-SERVICE`, `SIMULATOR-SERVICE`, `TELEMETRY-SERVICE`, `TOPOLOGY-SERVICE`, `WORKLOAD-SERVICE`. (Eureka itself is not listed; it is the registry.) The dashboard's service health strip at the top shows the same list live.

### C6. RabbitMQ management UI

Open <http://localhost:15672> and sign in with `RABBITMQ_USER` and `RABBITMQ_PASSWORD` from your `.env`. Under **Queues** you should see `q.telemetry.rack-readings`, `q.telemetry.zone-readings`, `q.scheduler.alerts`, `q.scheduler.cooling`, `q.scheduler.workloads`, `q.cooling.zone-readings`, `q.cooling.alerts`, `q.simulator.cooling`, `q.simulator.workloads` and `thermogrid.dlq`. Each working queue has at least one consumer and a message count near zero; `thermogrid.dlq` (the dead-letter queue) has no consumer and should be empty.

### C7. Open the dashboard

<http://localhost:5173> shows the login page. Gateway (API) base: `http://localhost:8080` (or your `GATEWAY_PORT`).

---

## D. Login credentials

| Username | Role | What the role may do |
|----------|------|----------------------|
| `viewer` | VIEWER | read everything; no changes; Control Room hidden |
| `operator` | OPERATOR | everything VIEWER can, plus submit/remove workloads, cooling manual override, autopilot, rebalance, failure injection, Control Room |
| `admin` | ADMIN | everything OPERATOR can, plus pause/resume/reset the simulator and seed the demo workloads |

Passwords: whatever you set for `SEED_VIEWER_PASSWORD`, `SEED_OPERATOR_PASSWORD` and `SEED_ADMIN_PASSWORD` in `.env`. To read them back:

```powershell
Select-String -Path .env -Pattern '^SEED_.*_PASSWORD='
```

To set a new one: edit `.env`, restart `auth-service`. A login token lasts **8 hours**.

---

## E. What you should see

After startup, demo seeding and about 30 seconds of data:

1. **Login screen.** Dark two-panel page with the ThermoGrid wordmark and a Sign in form. There is no demo-session shortcut any more. Wrong credentials show "That username and password were not accepted."
2. **Overview.** Top bar: *Autopilot on*, a green "All systems operational", "Server data as of" time, your username and role, a sign-out button. Below it the **service health strip**: Gateway, Auth, Topology, Simulator, Telemetry, Workload, Scheduler, Cooling, all green.
3. **KPI strip.** Five tiles: *Average temperature*, *Hottest rack*, *Active alerts*, *Cooling power* (modelled) and *Pending workloads*.
4. **Rack heat map.** 4 zones (Z1 to Z4) with 16 racks (R11 to R44). Each cell shows the rack id, temperature (one decimal) and CPU. Healthy: cool blue to green cells, around 24 to 27 °C. Warm racks drift towards amber and red.
5. **Active alerts.** Empty ("No active alerts") when healthy. A `WATCH` or `CRITICAL` item appears for a predicted or actual hotspot.
6. **Decision feed** ("What the system did and why, newest first"). Scheduler placements, migrations and cooling changes, each with its reason. A fresh system may show "No decisions yet" until something happens.
7. **Cooling zones** cards. One per zone: mode, output percent, health (OK / FAULT).
8. **Workload distribution.** Stacked bars of CPU units per rack by priority, with the 85 percent placement cap line.
9. **Rack detail** (click a rack): temperature with a 60-second forecast, power and CPU charts, the workloads on the rack, alerts for the rack.
10. **Workloads page.** Table with filters (All, Running, Pending, priority, rack), the pending queue ("Nothing is waiting" when healthy) and CPU-by-rack chart. OPERATOR and ADMIN also see **Submit a workload** and remove buttons; VIEWER does not.
11. **Cooling page.** Output per zone over time, modelled power against a static baseline (the system uses clearly less than the baseline), the action log, and for OPERATOR and ADMIN a manual override.
12. **Event history.** Alerts, migrations and cooling actions merged into one timeline.
13. **Control Room** (OPERATOR and ADMIN only). Scenario presets, failure injection with confirmation dialog, autopilot switch, "Rebalance now", the list of active failures with Clear, and (ADMIN only) *Simulation and demo*: Pause/Resume, Seed demo workloads, Reset demo.

**A healthy system after seeding:** 36 running workloads, 0 pending, 16 racks reporting, all eight services green, no active alerts, autopilot on, modelled cooling saving around 60 percent.

---

## F. How to verify security manually

Replace nothing below except the port if you changed `GATEWAY_PORT`. First load your `.env` values into the shell so you do not have to type passwords.

**PowerShell** (paste once; defines helpers used in the steps below):

```powershell
Get-Content .env | ForEach-Object { if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') { Set-Item "Env:$($Matches[1])" $Matches[2].Trim() } }
$port = if ($env:GATEWAY_PORT) { $env:GATEWAY_PORT } else { 8080 }
$G = "http://localhost:$port"
function Login($u, $p) { (Invoke-RestMethod -Method Post -Uri "$G/api/auth/login" -ContentType 'application/json' -Body (@{username=$u; password=$p} | ConvertTo-Json)).token }
function Status($method, $uri, $token, $body, $extra) {
  $h = @{}; if ($token) { $h.Authorization = "Bearer $token" }; if ($extra) { $extra.GetEnumerator() | ForEach-Object { $h[$_.Key] = $_.Value } }
  try { (Invoke-WebRequest -UseBasicParsing -Method $method -Uri $uri -Headers $h -ContentType 'application/json' -Body $body).StatusCode }
  catch { [int]$_.Exception.Response.StatusCode }
}
```

### F1. Login as VIEWER

```powershell
$v = Login 'viewer' $env:SEED_VIEWER_PASSWORD
$v.Substring(0, 20) + '...'          # a JWT starting with eyJ
```
Response shape (from `POST /api/auth/login`): `{"token":"<jwt>","tokenType":"Bearer","expiresInSeconds":28800,"user":{"username":"viewer","role":"VIEWER"}}`.

### F2. A protected read succeeds

```powershell
Status Get "$G/api/topology" $v          # expect 200
Invoke-RestMethod -Uri "$G/api/auth/me" -Headers @{Authorization="Bearer $v"}   # username viewer, role VIEWER
```
**200** = the token is valid and VIEWER may read.

### F3. VIEWER tries to change something: 403

```powershell
Status Post "$G/api/workloads" $v '{"name":"demo-job","cpuDemand":10,"priority":"LOW"}'    # expect 403
```
**403 Forbidden** = you are who you say you are (the token is valid) but your role is not allowed.

### F4. OPERATOR operational actions: allowed

```powershell
$o = Login 'operator' $env:SEED_OPERATOR_PASSWORD
Status Post "$G/api/scheduler/autopilot" $o '{"enabled":true}'              # expect 200
Status Post "$G/api/workloads" $o '{"name":"demo-job","cpuDemand":10,"priority":"LOW"}'   # expect 201 (creates a workload; remove it afterwards on the Workloads page)
```

### F5. OPERATOR tries simulator control: 403

```powershell
Status Post "$G/api/sim/control" $o '{"action":"PAUSE"}'                    # expect 403
Status Post "$G/api/workloads/seed-demo" $o                                  # expect 403 (seeding is ADMIN only)
```

### F6. ADMIN may control the simulator

```powershell
$a = Login 'admin' $env:SEED_ADMIN_PASSWORD
Status Post "$G/api/sim/control" $a '{"action":"PAUSE"}'                    # expect 200
Status Post "$G/api/sim/control" $a '{"action":"RESUME"}'                   # expect 200: put it back!
```
While paused the dashboard stops advancing; resume straight away.

### F7. No token: 401

```powershell
Status Get "$G/api/topology" $null                                          # expect 401
curl.exe -s -o NUL -w "%{http_code}`n" "$G/api/topology"                    # same check with curl.exe: 401
Status Get "$G/api/topology" ($v.Substring(0, $v.Length-3) + 'AAA')         # tampered token: expect 401
```
**401 Unauthorized** = the gateway does not know who you are (missing, expired, malformed or tampered token). Git Bash equivalent: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/topology`.

Wrong login details are also 401, and the answer is identical for a wrong password and an unknown user (it never reveals which usernames exist):

```powershell
try { Invoke-RestMethod -Method Post -Uri "$G/api/auth/login" -ContentType 'application/json' -Body '{"username":"viewer","password":"nope"}' } catch { $_.ErrorDetails.Message }
try { Invoke-RestMethod -Method Post -Uri "$G/api/auth/login" -ContentType 'application/json' -Body '{"username":"ghost","password":"nope"}' } catch { $_.ErrorDetails.Message }
```

### F8. Internal endpoints are protected directly and not routed by the gateway

```powershell
Status Get "http://localhost:8082/internal/topology" $null                  # expect 401 (service answers directly, no internal token)
Status Get "http://localhost:8082/internal/topology" $null $null @{'X-Internal-Token'='wrong'}   # expect 401
Status Get "$G/internal/topology" $a                                        # expect 404: the gateway never exposes /internal, even for ADMIN
```
The same 401 applies to `http://localhost:8085/internal/workloads` (workload-service) and `http://localhost:8084/internal/telemetry/racks/latest` (telemetry-service). The services call each other with the real token automatically, which is why the dashboard keeps working. (To prove the token itself works: `Status Get "http://localhost:8082/internal/topology" $null $null @{'X-Internal-Token'=$env:THERMOGRID_INTERNAL_TOKEN}` returns 200. Do not paste the token anywhere else.)

### F9. A forged identity header is ignored

```powershell
Status Post "$G/api/sim/control" $v '{"action":"PAUSE"}' @{'X-User-Role'='ADMIN'; 'X-User-Name'='root'}   # expect 403
```
You sent a VIEWER token plus a header claiming ADMIN. The gateway deletes `X-User-Role` and `X-User-Name` from every incoming request and sets them again only from the verified token, so you remain a VIEWER: **403**.

### F10. Logout and session expiry in the browser

1. Sign in as `viewer` at <http://localhost:5173>. Your name and role (VIEWER) show at the top right.
2. Press the **sign-out** icon (arrow leaving a box, top right). You land on the login page. Press the browser's Back button: you are sent to login again.
3. Sign in, then open the browser developer tools, **Application > Local Storage > `thermogrid.auth`**: it holds the token and its expiry time. Edit the `token` value (change a few characters) and reload: you are sent to the login page with a "Signed out / Please sign in again" notice, and the stored entry is gone. That is the 401 handling.
4. Tokens last 8 hours. After that the dashboard signs itself out with "Session expired".
5. As `viewer`, the **Control Room** menu item does not exist, and opening `/control` by hand redirects to the Overview. (Hiding is only a convenience: the gateway enforces the rules, as F3 and F5 prove.)

An automated version of step 1 to 5 across all three roles exists: `cd frontend; node scripts/verify-roles.mjs` (needs the dev server and your `.env` loaded, see the header of that file).

---

## G. Dashboard demonstration

> For the timed, repeatable 10-minute demo with reset script, slide and viva notes, use [demo-script.md](demo-script.md). This section is the longer walk-through of each page.

Sign in as `admin` for the whole demo. Do the steps in order. Wait about 30 seconds after seeding before starting so the first forecasts exist.

**Honesty note.** What I actually ran in Phase 9, on this machine, through the secured gateway: login for all roles; the role checks in section F; seeding; a Z2 `COOLING_DEGRADED` injection as `operator` (the API returned the failure with `injectedBy: operator`, a predicted `WATCH` alert for R21 appeared within 40 seconds and a scheduler placement decision was recorded), then clearing it; the browser checks of `verify-roles.mjs`. The full scripted scenarios in steps 3 to 7 (migrations, cooling climb, Z3 failure, scheduler stop and recovery) were exercised in earlier phases (7 and 8, see docs/status.md); they were **not** re-run end to end after authentication was added, and the screenshots in `docs/screenshots/` are from Phase 8. Treat steps 3 to 7 as the intended behaviour and report anything that differs.

### 1. Normal operation
- **Open:** Overview. **Do:** nothing, watch for a minute.
- **See:** all-green health strip, heat map in blues and greens, "No active alerts", the decision feed slowly filling, Autopilot ON.
- **Responsible:** simulator-service produces readings, telemetry-service stores and forecasts them, api-gateway serves them.
- **Restore:** nothing to restore.

### 2. Z2 cooling degradation and a predicted hotspot
- **Open:** Control Room. **Do:** in *Scenario presets* press **Review and inject** on **Degrade cooling in Z2**, read the dialog, confirm.
- **See:** within about 20 to 60 seconds the Z2 racks (R21 to R24) warm up; a `WATCH` alert with basis *predicted* appears under Active alerts on the Overview (I saw R21 raise one); the heat map cells for Z2 turn amber.
- **Responsible:** `POST /api/sim/failures` (simulator-service), forecasts and alerts from telemetry-service.
- **Restore:** Control Room > *Active failures* > **Clear** next to the Z2 entry.

### 3. Automatic workload migration
- **Open:** Overview (decision feed) or Event history. **Do:** stay on the page while Z2 is degraded.
- **Expected:** the scheduler moves work from the warmest Z2 racks to cooler racks; entries like "workload X moved R22 to R11, reason: predicted hotspot" appear in the decision feed, and the CPU-by-rack chart on Workloads shifts.
- **Responsible:** scheduler-service (`/api/scheduler/migrations`, `/decisions`), workload-service applies the new rack.
- **Restore:** clear the failure (step 2); the scheduler stops migrating and the temperatures settle.

### 4. Cooling response
- **Open:** Cooling page. **Do:** watch the Z2 line while the failure is active, then clear it.
- **Expected:** the controller raises Z2's output percent as temperature rises; after clearing, a short boost is released and output settles back down.
- **Responsible:** cooling-controller-service (`/api/cooling/units`, `/actions`).

### 5. Z3 cooling failure
- **Open:** Control Room. **Do:** **Fail the cooling unit in Z3** and confirm.
- **Expected:** the Z3 cooling card shows FAULT with output 0; other zones raise their minimum output (the card text mentions a 60 percent floor); workloads are drained out of Z3 and no new work is placed there; a `CRITICAL` alert may follow.
- **Restore:** *Active failures* > **Clear**. Z3 returns to OK. If anything looks stuck, Admin > **Reset demo** clears all failures and resets the plant (existing workloads are kept).

### 6. Scheduler service failure and recovery
This stops a real service, so do it deliberately.
- **Do:** find the process that owns the scheduler's port and stop only that one:
  ```powershell
  $p = (Get-NetTCPConnection -LocalPort 8086 -State Listen).OwningProcess
  Stop-Process -Id $p
  ```
- **Expected:** within about 10 to 20 seconds the service health strip shows **Scheduler unavailable**, and only the panels fed by the scheduler (decision feed, autopilot badge) show a stale badge with the last server time. Everything else keeps updating. Through the gateway, `/api/scheduler/status` answers a JSON **503** naming SCHEDULER-SERVICE (I checked this).
- **Restore:** run the start script again without build or Docker; it leaves running services alone and starts only the missing one (I ran exactly this):
  ```powershell
  powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1 -SkipBuild -NoInfra
  ```
  ```bash
  SKIP_BUILD=1 START_INFRA=0 scripts/start-all.sh
  ```
  The scheduler re-registers in Eureka and the panels recover within a few seconds.
- Phase 8 verified the panel behaviour with `frontend/scripts/stale-check.sh`.

### 7. Telemetry failure (only if you are comfortable)
- Same method as step 6 with `telemetry-service` (port 8084). **Expected:** the heat map, alerts and KPIs freeze with a stale badge and "Telemetry unavailable"; workloads and cooling panels keep working. Restore the same way. Do this last, because it affects the main panels.

---

## H. Troubleshooting

Logs: `.run/logs/<service>.log` (for example `Get-Content .run\logs\api-gateway.log -Tail 50`, or `Get-Content .run\logs\auth-service.log -Wait` to follow). Container logs: `docker logs thermogrid-mysql --tail 50`, `docker logs thermogrid-rabbitmq --tail 50`.

| Problem | What it means and what to do |
|---------|------------------------------|
| **Port 8080 already in use** | The script refuses to start the gateway and kills nothing. Use another port (section C4). Find the owner: `Get-NetTCPConnection -LocalPort 8080 -State Listen`. |
| **MySQL connection failures** (`Access denied for user`, `Communications link failure`) | `docker ps` must show `thermogrid-mysql (healthy)`. "Access denied" means the `*_DB_PASSWORD` in `.env` differs from the one MySQL was created with (the volume keeps the first values). Put the original value back in `.env`. Do not delete the volume unless you accept losing data. |
| **RabbitMQ unavailable** | `docker ps` shows `thermogrid-rabbitmq (healthy)`? Credentials in `.env` (`RABBITMQ_USER` / `RABBITMQ_PASSWORD`) must be the ones the container was created with. Restart with `docker compose --env-file .env -f infra/docker-compose.yml up -d`. |
| **A service is missing from Eureka** | Look at its log (`.run/logs/<service>.log`); the last lines say why. Eureka forgets a service about 10 seconds after it stops. Start order matters: rerun the start script, it skips running services. |
| **A service fails to start** with `THERMOGRID_INTERNAL_TOKEN must be set` or `THERMOGRID_JWT_SECRET must be set ...32 characters` | Missing or short value in `.env`. Fix it, rerun the start script. The services were started by the script, which loads `.env`; a service started by hand needs the variables set in that terminal. |
| **Login says "not accepted"** | Wrong username or password. Check `.env` values (section D). After editing a `SEED_*_PASSWORD`, restart `auth-service`. |
| **Login says "gateway could not be reached" / frontend API connection failures** | Gateway not running, or the dev server proxies to the wrong port. Check `http://localhost:8080/api/platform/services` (it answers 401 when the gateway is up, which is correct without a token). Set `GATEWAY_PORT` in the frontend terminal (section C4) and restart `npm run dev`. |
| **401 everywhere after being logged in** | Token expired, tampered or signed with a different `THERMOGRID_JWT_SECRET` (for example you changed it and did not restart both auth-service and the gateway). Sign in again. |
| **403 "Not permitted" toast** | Your role is too low for that action (sections D and F). Sign in as a higher role. |
| **Stale dashboard panels** | A badge names the service that stopped answering and shows the last server time. Check that service's log and Eureka. Panels recover by themselves within a few seconds once it is back; **Refresh** forces it. |
| **Everything 503** | `api-gateway` is up but services are not registered yet; wait for Eureka (C5). |
| **PowerShell refuses to run the script** | Use `powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1`. |

---

## I. Stop and restart

**Stop the Java services (keeps all data):**
```powershell
powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1
```
```bash
scripts/stop-all.sh
```
It stops only the processes the start script started (recorded in `.run/`), in reverse order, and never touches anything else. It does **not** stop Docker, so MySQL and RabbitMQ keep running with their data. Stop the frontend with Ctrl+C in its terminal.

**Stop MySQL and RabbitMQ without losing data:**
```powershell
docker compose --env-file .env -f infra/docker-compose.yml stop
```
Their data lives in Docker volumes and survives `stop`, `start`, a Docker Desktop restart and a reboot. **Never** run `docker compose down -v` (or delete the volumes) unless you want to erase the database and queues.

**Restart later:** run the start script again (section C1). With `-SkipBuild` / `SKIP_BUILD=1` it skips the build, and `-NoInfra` / `START_INFRA=0` skips Docker if those are already up. The simulator keeps its failure history in a file under `simulator-service/`; workloads, users and telemetry live in MySQL, so after a restart everything is where you left it (the heat map refills within a few seconds). Seeded users are not duplicated.

**Restart one service** (for example `auth-service` after changing a password in `.env`): stop the process that owns its port (`Stop-Process -Id (Get-NetTCPConnection -LocalPort 8081 -State Listen).OwningProcess`), then run `scripts\start-all.ps1 -SkipBuild -NoInfra`. The script starts only what is missing and loads `.env` for it.
