# Demo checklists: screenshots, backup video, three rehearsals

These are working checklists. **Nothing in the "Done" columns below has been done yet except where stated.** No backup video has been recorded and no full rehearsal has been performed by a person; the three-run table is empty on purpose. Fill it in as you go and commit it, so the record is honest.

## A. Before every run (2 minutes)

- [ ] Services up: `scripts/start-all.sh` (or the Docker stack); health strip all green; Eureka lists 8 services.
- [ ] `scripts/reset-demo.sh` printed "calm: 16 racks NORMAL, no alerts..." (about 10 to 20 s).
- [ ] Tabs open: dashboard (signed in as admin), Eureka, RabbitMQ UI, architecture slide, terminal in the repository root.
- [ ] Terminal ready with the scheduler stop and start commands (`docs/demo-script.md`, part 7). Docker users: the `docker compose ... stop/start scheduler-service` pair.
- [ ] Browser zoom so the heat map and the decision feed are readable from the back of the room; notifications off; laptop on power.
- [ ] Code frozen 24 hours before the real demo (plan Phase 13); no new commits to the demo branch after that.

## B. Screenshots

Save to `docs/screenshots/` (PNG, 1440 x 900 or larger). `frontend/scripts/screenshots.mjs` can capture the standard pages automatically.

| # | Screenshot | Status |
|---|-----------|--------|
| 1 | Overview, normal operation (scenario 1) | Exists from Phase 8 (`laptop-1440x900-overview.png`, `desktop-1920x1080-overview.png`); retake after the final run if the layout changed |
| 2 | Rack detail with the forecast line above the temperature line | Exists from Phase 8 (`laptop-1440x900-rack-detail.png`); retake during scenario 3 to show a real PREDICTED alert |
| 3 | Control Room and the injection confirmation dialog | Exists from Phase 8 (`laptop-1440x900-control-room.png`, `scenario-degrade-1-confirm-dialog.png`) |
| 4 | Cooling page | Exists from Phase 8 (`laptop-1440x900-cooling.png`) |
| 5 | Login page (real login) | **Retake**: the existing `laptop-1440x900-login.png` was taken in Phase 8, before the real login of Phase 9 |
| 6 | Overview with a PREDICTED WARNING alert (scenario 3) | To do |
| 7 | Decision feed with migrations and their reasons (scenario 4) | To do |
| 8 | Z3 cooling unit failure: Z3 row label red, other zones at 60 percent or more (scenario 6) | To do |
| 9 | Scheduler badge red and the stale badge on the decision feed (scenario 7) | To do |
| 10 | Recovery: badge green, pending 0 (scenario 8) | To do |
| 11 | Eureka dashboard with all services registered | To do |
| 12 | RabbitMQ Queues page (consumers present, `thermogrid.dlq` empty) | To do |
| 13 | Architecture slide | Done: `docs/slides/architecture.png` (rendered from `architecture.html`) |
| 14 | k6 summary and flood queue graph (1 against 3 telemetry instances) | To do: the numbers are in `docs/performance.md`; capture the terminal and the RabbitMQ chart if you rerun them |
| 15 | `docker compose ps` and Eureka with 3 telemetry instances **on the lab server** | **Blocked**: lab-server deployment is pending |

## C. Backup video (not recorded)

Purpose: a fallback if the live demo cannot run. **Status: not recorded.**

- [ ] Record the screen at 1080p with audio (for example OBS Studio or the Windows Game Bar), after `reset-demo.sh`, following `docs/demo-script.md` from part 2 to part 7. Aim for under 10 minutes.
- [ ] Show the terminal commands for the scheduler stop and start on screen.
- [ ] Narrate what each step proves in one sentence; say clearly that the plant is simulated.
- [ ] Play it back once end to end: audio level, readable text, no password or secret visible (do not show `.env`).
- [ ] Store the file outside the repository (it is large) and note its location here: `______________________`. Keep a copy on a USB stick or cloud drive.
- [ ] Record on the final, frozen build, not an earlier one.

## D. Three complete rehearsals

Acceptance for each run: the whole script from `docs/demo-script.md` finishes in **under 10 minutes** with **no manual fix** (no code edit, no manual database change, no restart beyond what the script itself calls for), every "See" line in the script was observed, and `scripts/reset-demo.sh` returned to a calm state at the end. A different team member should run at least one of the three.

| Run | Date and time | Who presented | Machine | Started | Finished | Total | Manual fixes needed | All expected observations seen? | Notes |
|-----|---------------|---------------|---------|---------|----------|-------|---------------------|---------------------------------|-------|
| 1 | not yet performed | | | | | | | | |
| 2 | not yet performed | | | | | | | | |
| 3 | not yet performed | | | | | | | | |

- [ ] Run 1 done and recorded    - [ ] Run 2 done and recorded    - [ ] Run 3 done and recorded
- [ ] Viva answers rehearsed aloud (`docs/viva.md`: database per service, events, polling, proportional controller).
- [ ] If the lab server is used for the real demo: do at least one rehearsal on the lab URL itself.

## E. Still open before the real demo

- Deploy to the lab server and record the lab URL, `docker compose ps` and a k6 run there (`docs/performance.md`, section 6).
- Perform and record the three rehearsals above, and record the backup video.
- Retake the screenshots marked "Retake" or "To do".
