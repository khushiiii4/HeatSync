-- Simulator persistence (plan section 7, embedded H2 file simdb). Idempotent: runs on every startup.
CREATE TABLE IF NOT EXISTS failure_injection (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    type        VARCHAR(20) NOT NULL,
    target_id   VARCHAR(8)  NOT NULL,
    param       DOUBLE      NULL,
    started_at  TIMESTAMP   NOT NULL,
    ends_at     TIMESTAMP   NULL,
    cleared_at  TIMESTAMP   NULL,
    injected_by VARCHAR(50) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_failure_injection_cleared_at ON failure_injection (cleared_at);
