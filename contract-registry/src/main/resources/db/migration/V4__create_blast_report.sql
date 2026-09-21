CREATE TABLE blast_report (
    id                  BIGSERIAL PRIMARY KEY,
    service_id          BIGINT REFERENCES service(id) ON DELETE CASCADE,
    old_spec_version_id BIGINT REFERENCES spec_version(id) ON DELETE CASCADE,
    new_spec_version_id BIGINT REFERENCES spec_version(id) ON DELETE CASCADE,
    report_json         TEXT NOT NULL,
    confirmed_count     INTEGER DEFAULT 0,
    likely_count        INTEGER DEFAULT 0,
    unknown_count       INTEGER DEFAULT 0,
    generated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_report_service ON blast_report(service_id);
CREATE INDEX idx_report_versions ON blast_report(old_spec_version_id, new_spec_version_id);
