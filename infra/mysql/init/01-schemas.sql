-- ThermoGrid: six MySQL schemas, one per service (plan sections 5.5 and 7).
-- The simulator uses embedded H2 and has no MySQL schema.
-- Users and grants are created by 02-users.sh, because passwords come from
-- environment variables and .sql init files cannot read them.
-- Runs only on a fresh data volume.
CREATE DATABASE IF NOT EXISTS auth_db      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS topology_db  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS telemetry_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS workload_db  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS scheduler_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS cooling_db   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;