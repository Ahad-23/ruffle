CREATE TABLE service (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    base_url    VARCHAR(512),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE spec_version (
    id          BIGSERIAL PRIMARY KEY,
    service_id  BIGINT REFERENCES service(id) ON DELETE CASCADE,
    version_tag VARCHAR(100),
    spec_hash   VARCHAR(64) NOT NULL,
    raw_spec    TEXT NOT NULL,
    ingested_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_service_spec_hash UNIQUE(service_id, spec_hash)
);

CREATE TABLE endpoint (
    id              BIGSERIAL PRIMARY KEY,
    spec_version_id BIGINT REFERENCES spec_version(id) ON DELETE CASCADE,
    http_method     VARCHAR(10) NOT NULL,
    path            VARCHAR(512) NOT NULL,
    operation_id    VARCHAR(255),
    summary         TEXT
);

CREATE TABLE schema_field (
    id              BIGSERIAL PRIMARY KEY,
    endpoint_id     BIGINT REFERENCES endpoint(id) ON DELETE CASCADE,
    response_code   VARCHAR(10),
    field_path      VARCHAR(512) NOT NULL,
    field_type      VARCHAR(100) NOT NULL,
    is_required     BOOLEAN DEFAULT FALSE,
    parent_schema   VARCHAR(255)
);

CREATE INDEX idx_field_endpoint ON schema_field(endpoint_id);
CREATE INDEX idx_field_path ON schema_field(field_path);
CREATE INDEX idx_endpoint_spec ON endpoint(spec_version_id);
CREATE INDEX idx_spec_service ON spec_version(service_id);
