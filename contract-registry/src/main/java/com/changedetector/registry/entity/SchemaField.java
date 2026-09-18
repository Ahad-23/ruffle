package com.changedetector.registry.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "schema_field")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SchemaField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "endpoint_id", nullable = false)
    private Endpoint endpoint;

    @Column(name = "response_code", length = 10)
    private String responseCode;

    @Column(name = "field_path", nullable = false, length = 512)
    private String fieldPath;

    @Column(name = "field_type", nullable = false, length = 100)
    private String fieldType;

    @Builder.Default
    @Column(name = "is_required")
    private Boolean isRequired = false;

    @Column(name = "parent_schema")
    private String parentSchema;

    public SchemaField(Endpoint endpoint, String responseCode, String fieldPath, String fieldType, Boolean isRequired, String parentSchema) {
        this.endpoint = endpoint;
        this.responseCode = responseCode;
        this.fieldPath = fieldPath;
        this.fieldType = fieldType;
        this.isRequired = isRequired;
        this.parentSchema = parentSchema;
    }
}
