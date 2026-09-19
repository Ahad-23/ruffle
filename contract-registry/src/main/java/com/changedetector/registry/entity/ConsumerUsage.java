package com.changedetector.registry.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "consumer_usage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConsumerUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consumer_service", nullable = false)
    private String consumerService;

    @Column(name = "provider_service", nullable = false)
    private String providerService;

    @Column(name = "endpoint_path", nullable = false, length = 512)
    private String endpointPath;

    @Column(name = "http_method", length = 10)
    private String httpMethod;

    @Column(name = "field_path", length = 512)
    private String fieldPath;

    @Column(name = "evidence_type", nullable = false, length = 20)
    private String evidenceType; // CONFIRMED, LIKELY, UNKNOWN

    @Column(name = "source_file", nullable = false, length = 1024)
    private String sourceFile;

    @Column(name = "source_line", nullable = false)
    private Integer sourceLine;

    @Column(name = "evidence_detail", columnDefinition = "TEXT")
    private String evidenceDetail;

    @Builder.Default
    @Column(name = "scan_timestamp")
    private LocalDateTime scanTimestamp = LocalDateTime.now();

    @Column(name = "scan_id", length = 100)
    private String scanId;

    public ConsumerUsage(String consumerService, String providerService, String endpointPath,
                         String httpMethod, String fieldPath, String evidenceType,
                         String sourceFile, Integer sourceLine, String evidenceDetail, String scanId) {
        this.consumerService = consumerService;
        this.providerService = providerService;
        this.endpointPath = endpointPath;
        this.httpMethod = httpMethod;
        this.fieldPath = fieldPath;
        this.evidenceType = evidenceType;
        this.sourceFile = sourceFile;
        this.sourceLine = sourceLine;
        this.evidenceDetail = evidenceDetail;
        this.scanId = scanId;
        this.scanTimestamp = LocalDateTime.now();
    }
}
