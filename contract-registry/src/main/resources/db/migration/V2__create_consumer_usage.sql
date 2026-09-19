CREATE TABLE consumer_usage (
    id               BIGSERIAL PRIMARY KEY,
    consumer_service VARCHAR(255) NOT NULL,
    provider_service VARCHAR(255) NOT NULL,
    endpoint_path    VARCHAR(512) NOT NULL,
    http_method      VARCHAR(10),
    field_path       VARCHAR(512),
    evidence_type    VARCHAR(20) NOT NULL,
    source_file      VARCHAR(1024) NOT NULL,
    source_line      INTEGER NOT NULL,
    evidence_detail  TEXT,
    scan_timestamp   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    scan_id          VARCHAR(100)
);

CREATE INDEX idx_usage_provider ON consumer_usage(provider_service, endpoint_path);
CREATE INDEX idx_usage_field ON consumer_usage(provider_service, field_path);
CREATE INDEX idx_usage_scan ON consumer_usage(scan_id);
