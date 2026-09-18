package com.changedetector.registry.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "endpoint")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Endpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "spec_version_id", nullable = false)
    private SpecVersion specVersion;

    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod;

    @Column(nullable = false, length = 512)
    private String path;

    @Column(name = "operation_id")
    private String operationId;

    @Column(columnDefinition = "TEXT")
    private String summary;

    public Endpoint(SpecVersion specVersion, String httpMethod, String path, String operationId, String summary) {
        this.specVersion = specVersion;
        this.httpMethod = httpMethod;
        this.path = path;
        this.operationId = operationId;
        this.summary = summary;
    }
}
