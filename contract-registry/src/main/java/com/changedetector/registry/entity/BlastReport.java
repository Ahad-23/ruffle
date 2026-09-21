package com.changedetector.registry.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "blast_report")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BlastReport {

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

    @Column(name = "report_json", nullable = false, columnDefinition = "TEXT")
    private String reportJson;

    @Builder.Default
    @Column(name = "confirmed_count")
    private Integer confirmedCount = 0;

    @Builder.Default
    @Column(name = "likely_count")
    private Integer likelyCount = 0;

    @Builder.Default
    @Column(name = "unknown_count")
    private Integer unknownCount = 0;

    @Builder.Default
    @Column(name = "generated_at")
    private LocalDateTime generatedAt = LocalDateTime.now();

    public BlastReport(Service service, SpecVersion oldSpecVersion, SpecVersion newSpecVersion,
                       String reportJson, Integer confirmedCount, Integer likelyCount, Integer unknownCount) {
        this.service = service;
        this.oldSpecVersion = oldSpecVersion;
        this.newSpecVersion = newSpecVersion;
        this.reportJson = reportJson;
        this.confirmedCount = confirmedCount;
        this.likelyCount = likelyCount;
        this.unknownCount = unknownCount;
        this.generatedAt = LocalDateTime.now();
    }
}
