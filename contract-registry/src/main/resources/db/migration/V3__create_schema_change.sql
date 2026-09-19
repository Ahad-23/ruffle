CREATE TABLE schema_change (
    id                  BIGSERIAL PRIMARY KEY,
    service_id          BIGINT REFERENCES service(id) ON DELETE CASCADE,
    old_spec_version_id BIGINT REFERENCES spec_version(id) ON DELETE CASCADE,
    new_spec_version_id BIGINT REFERENCES spec_version(id) ON DELETE CASCADE,
    change_type         VARCHAR(50) NOT NULL,
    endpoint_path       VARCHAR(512),
    http_method         VARCHAR(10),
    field_path          VARCHAR(512),
    old_value           VARCHAR(255),
    new_value           VARCHAR(255),
    severity            VARCHAR(20) NOT NULL,
    detected_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_change_service ON schema_change(service_id);
CREATE INDEX idx_change_versions ON schema_change(old_spec_version_id, new_spec_version_id);
