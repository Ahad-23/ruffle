package com.changedetector.registry.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "schema_change")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SchemaChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    private Service service;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "old_spec_version_id", nullable = false)
    private SpecVersion oldSpecVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "new_spec_version_id", nullable = false)
    private SpecVersion newSpecVersion;

    @Column(name = "change_type", nullable = false, length = 50)
    private String changeType; // FIELD_REMOVED, TYPE_CHANGED, REQUIRED_FIELD_ADDED, ENDPOINT_REMOVED, FIELD_RENAMED, etc.

    @Column(name = "endpoint_path", length = 512)
    private String endpointPath;

    @Column(name = "http_method", length = 10)
    private String httpMethod;

    @Column(name = "field_path", length = 512)
    private String fieldPath;

    @Column(name = "old_value")
    private String oldValue;

    @Column(name = "new_value")
    private String newValue;

    @Column(nullable = false, length = 20)
    private String severity; // BREAKING, WARNING, INFO

    @Builder.Default
    @Column(name = "detected_at")
    private LocalDateTime detectedAt = LocalDateTime.now();

    public SchemaChange(Service service, SpecVersion oldSpecVersion, SpecVersion newSpecVersion,
                        String changeType, String endpointPath, String httpMethod, String fieldPath,
                        String oldValue, String newValue, String severity) {
        this.service = service;
        this.oldSpecVersion = oldSpecVersion;
        this.newSpecVersion = newSpecVersion;
        this.changeType = changeType;
        this.endpointPath = endpointPath;
        this.httpMethod = httpMethod;
        this.fieldPath = fieldPath;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.severity = severity;
        this.detectedAt = LocalDateTime.now();
    }
}
