package com.changedetector.registry.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "spec_version", uniqueConstraints = {
    @UniqueConstraint(name = "uq_service_spec_hash", columnNames = {"service_id", "spec_hash"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpecVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    private Service service;

    @Column(name = "version_tag")
    private String versionTag;

    @Column(name = "spec_hash", nullable = false, length = 64)
    private String specHash;

    @Column(name = "raw_spec", nullable = false, columnDefinition = "TEXT")
    private String rawSpec;

    @Builder.Default
    @Column(name = "ingested_at")
    private LocalDateTime ingestedAt = LocalDateTime.now();

    public SpecVersion(Service service, String versionTag, String specHash, String rawSpec) {
        this.service = service;
        this.versionTag = versionTag;
        this.specHash = specHash;
        this.rawSpec = rawSpec;
        this.ingestedAt = LocalDateTime.now();
    }
}
