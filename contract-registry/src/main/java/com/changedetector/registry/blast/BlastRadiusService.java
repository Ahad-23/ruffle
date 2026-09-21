package com.changedetector.registry.blast;

import com.changedetector.registry.diff.SchemaDiffEngine;
import com.changedetector.registry.entity.*;
import com.changedetector.registry.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class BlastRadiusService {

    private static final Logger log = LoggerFactory.getLogger(BlastRadiusService.class);

    private final ServiceRepository serviceRepository;
    private final SpecVersionRepository specVersionRepository;
    private final SchemaChangeRepository schemaChangeRepository;
    private final ConsumerUsageRepository consumerUsageRepository;
    private final BlastReportRepository blastReportRepository;
    private final SchemaDiffEngine schemaDiffEngine;
    private final ObjectMapper objectMapper;

    public BlastRadiusService(ServiceRepository serviceRepository,
                              SpecVersionRepository specVersionRepository,
                              SchemaChangeRepository schemaChangeRepository,
                              ConsumerUsageRepository consumerUsageRepository,
                              BlastReportRepository blastReportRepository,
                              SchemaDiffEngine schemaDiffEngine,
                              ObjectMapper objectMapper) {
        this.serviceRepository = serviceRepository;
        this.specVersionRepository = specVersionRepository;
        this.schemaChangeRepository = schemaChangeRepository;
        this.consumerUsageRepository = consumerUsageRepository;
        this.blastReportRepository = blastReportRepository;
        this.schemaDiffEngine = schemaDiffEngine;
        this.objectMapper = objectMapper;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BlastRadiusReport {
        private String providerService;
        private Long oldVersionId;
        private String oldVersionTag;
        private Long newVersionId;
        private String newVersionTag;
        private LocalDateTime generatedAt;

        private int confirmedCount;
        private int likelyCount;
        private int unknownCount;

        @Builder.Default
        private List<ImpactItem> confirmedImpacts = new ArrayList<>();
        @Builder.Default
        private List<ImpactItem> likelyImpacts = new ArrayList<>();
        @Builder.Default
        private List<ImpactItem> unknownImpacts = new ArrayList<>();
        @Builder.Default
        private List<SchemaChangeSummary> safeChanges = new ArrayList<>();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ImpactItem {
        private String consumerService;
        private String sourceFile;
        private Integer sourceLine;
        private String evidenceType;
        private String evidenceDetail;

        private String changeType;
        private String endpointPath;
        private String httpMethod;
        private String fieldPath;
        private String oldValue;
        private String newValue;
        private String severity;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SchemaChangeSummary {
        private String changeType;
        private String endpointPath;
        private String httpMethod;
        private String fieldPath;
        private String severity;
    }

    @Transactional
    public BlastRadiusReport calculateBlastRadius(Long serviceId, Long oldVersionId, Long newVersionId) {
        com.changedetector.registry.entity.Service service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException("Service not found with id: " + serviceId));

        SpecVersion oldVersion = specVersionRepository.findById(oldVersionId)
                .orElseThrow(() -> new IllegalArgumentException("Old spec version not found: " + oldVersionId));
        SpecVersion newVersion = specVersionRepository.findById(newVersionId)
                .orElseThrow(() -> new IllegalArgumentException("New spec version not found: " + newVersionId));

        List<SchemaChange> changes = schemaChangeRepository.findByOldSpecVersionIdAndNewSpecVersionId(oldVersionId, newVersionId);
        if (changes.isEmpty()) {
            changes = schemaDiffEngine.computeAndSaveDiff(service, oldVersion, newVersion);
        }

        List<ConsumerUsage> allUsages = consumerUsageRepository.findByProviderService(service.getName());

        BlastRadiusReport report = new BlastRadiusReport();
        report.setProviderService(service.getName());
        report.setOldVersionId(oldVersionId);
        report.setOldVersionTag(oldVersion.getVersionTag());
        report.setNewVersionId(newVersionId);
        report.setNewVersionTag(newVersion.getVersionTag());
        report.setGeneratedAt(LocalDateTime.now());
        report.setConfirmedImpacts(new ArrayList<>());
        report.setLikelyImpacts(new ArrayList<>());
        report.setUnknownImpacts(new ArrayList<>());
        report.setSafeChanges(new ArrayList<>());

        Set<String> impactedKeys = new HashSet<>();

        for (SchemaChange change : changes) {
            boolean hasImpact = false;

            if ("INFO".equalsIgnoreCase(change.getSeverity())) {
                report.getSafeChanges().add(new SchemaChangeSummary(
                        change.getChangeType(), change.getEndpointPath(), change.getHttpMethod(), change.getFieldPath(), change.getSeverity()
                ));
                continue;
            }

            for (ConsumerUsage usage : allUsages) {
                if (endpointMatches(usage.getEndpointPath(), change.getEndpointPath())) {
                    boolean fieldMatches = false;

                    if ("ENDPOINT_REMOVED".equalsIgnoreCase(change.getChangeType())) {
                        fieldMatches = true;
                    } else if (change.getFieldPath() != null) {
                        if (usage.getFieldPath() == null || usage.getFieldPath().trim().isEmpty()) {
                            // Usage is on endpoint level without field visibility -> UNKNOWN bucket
                            fieldMatches = true;
                        } else if (usage.getFieldPath().equalsIgnoreCase(change.getFieldPath())
                                || usage.getFieldPath().endsWith("." + change.getFieldPath())
                                || change.getFieldPath().endsWith("." + usage.getFieldPath())) {
                            fieldMatches = true;
                        }
                    }

                    if (fieldMatches) {
                        hasImpact = true;
                        ImpactItem item = ImpactItem.builder()
                                .consumerService(usage.getConsumerService())
                                .sourceFile(usage.getSourceFile())
                                .sourceLine(usage.getSourceLine())
                                .evidenceType(usage.getEvidenceType())
                                .evidenceDetail(usage.getEvidenceDetail())
                                .changeType(change.getChangeType())
                                .endpointPath(change.getEndpointPath())
                                .httpMethod(change.getHttpMethod())
                                .fieldPath(change.getFieldPath())
                                .oldValue(change.getOldValue())
                                .newValue(change.getNewValue())
                                .severity(change.getSeverity())
                                .build();

                        String key = usage.getConsumerService() + ":" + usage.getSourceFile() + ":" + usage.getSourceLine() + ":" + change.getId();
                        if (impactedKeys.add(key)) {
                            if ("CONFIRMED".equalsIgnoreCase(usage.getEvidenceType())) {
                                report.getConfirmedImpacts().add(item);
                            } else if ("LIKELY".equalsIgnoreCase(usage.getEvidenceType())) {
                                report.getLikelyImpacts().add(item);
                            } else {
                                report.getUnknownImpacts().add(item);
                            }
                        }
                    }
                }
            }

            if (!hasImpact) {
                report.getSafeChanges().add(new SchemaChangeSummary(
                        change.getChangeType(), change.getEndpointPath(), change.getHttpMethod(), change.getFieldPath(), change.getSeverity()
                ));
            }
        }

        report.setConfirmedCount(report.getConfirmedImpacts().size());
        report.setLikelyCount(report.getLikelyImpacts().size());
        report.setUnknownCount(report.getUnknownImpacts().size());

        // Save report entity
        try {
            String reportJson = objectMapper.writeValueAsString(report);
            BlastReport reportEntity = new BlastReport(
                    service, oldVersion, newVersion, reportJson,
                    report.getConfirmedCount(), report.getLikelyCount(), report.getUnknownCount()
            );
            blastReportRepository.save(reportEntity);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize blast report JSON", e);
        }

        return report;
    }

    private boolean endpointMatches(String usagePath, String changePath) {
        if (usagePath == null || changePath == null) return false;
        String cleanUsage = normalizePath(usagePath);
        String cleanChange = normalizePath(changePath);
        return cleanUsage.equalsIgnoreCase(cleanChange);
    }

    private String normalizePath(String path) {
        // Strip query params and replace path variables e.g. {id} or {ownerId} with {*}
        String clean = path.split("\\?")[0].trim();
        clean = clean.replaceAll("\\{[^}]+}", "{*}");
        return clean.replaceAll("/+$", "");
    }

    public String formatMarkdownReport(BlastRadiusReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 🚨 Cross-Service Breaking Change Detection Report\n\n");
        sb.append("**Provider Service:** `").append(report.getProviderService()).append("`\n");
        sb.append("**Comparison:** `").append(report.getOldVersionTag()).append("` ➔ `").append(report.getNewVersionTag()).append("`\n\n");

        sb.append("| Bucket | Count | Description |\n");
        sb.append("|---|---|---|\n");
        sb.append("| 🔴 **CONFIRMED** | **").append(report.getConfirmedCount()).append("** | Static proof of field access that will break |\n");
        sb.append("| 🟡 **LIKELY** | **").append(report.getLikelyCount()).append("** | Dynamic / Map / JSON access to modified field |\n");
        sb.append("| ⚪ **UNKNOWN** | **").append(report.getUnknownCount()).append("** | Endpoint caller with unresolvable field usage |\n\n");

        if (report.getConfirmedCount() > 0) {
            sb.append("### 🔴 CONFIRMED Breaking Changes (Action Required)\n\n");
            sb.append("| Consumer Service | Source Location | Change Type | Endpoint | Field | Details |\n");
            sb.append("|---|---|---|---|---|---|\n");
            for (ImpactItem item : report.getConfirmedImpacts()) {
                sb.append("| `").append(item.getConsumerService()).append("` | `")
                        .append(item.getSourceFile()).append(":").append(item.getSourceLine()).append("` | `")
                        .append(item.getChangeType()).append("` | `")
                        .append(item.getHttpMethod() != null ? item.getHttpMethod() + " " : "").append(item.getEndpointPath()).append("` | `")
                        .append(item.getFieldPath() != null ? item.getFieldPath() : "-").append("` | ")
                        .append(item.getEvidenceDetail() != null ? item.getEvidenceDetail() : "-").append(" |\n");
            }
            sb.append("\n");
        }

        if (report.getLikelyCount() > 0) {
            sb.append("### 🟡 LIKELY Impacted Consumers\n\n");
            sb.append("| Consumer Service | Source Location | Change Type | Endpoint | Field | Details |\n");
            sb.append("|---|---|---|---|---|---|\n");
            for (ImpactItem item : report.getLikelyImpacts()) {
                sb.append("| `").append(item.getConsumerService()).append("` | `")
                        .append(item.getSourceFile()).append(":").append(item.getSourceLine()).append("` | `")
                        .append(item.getChangeType()).append("` | `")
                        .append(item.getEndpointPath()).append("` | `")
                        .append(item.getFieldPath() != null ? item.getFieldPath() : "-").append("` | ")
                        .append(item.getEvidenceDetail() != null ? item.getEvidenceDetail() : "-").append(" |\n");
            }
            sb.append("\n");
        }

        if (report.getUnknownCount() > 0) {
            sb.append("### ⚪ UNKNOWN Field Visibility (Manual Verification Needed)\n\n");
            sb.append("| Consumer Service | Source Location | Endpoint | Evidence Reason |\n");
            sb.append("|---|---|---|---|\n");
            for (ImpactItem item : report.getUnknownImpacts()) {
                sb.append("| `").append(item.getConsumerService()).append("` | `")
                        .append(item.getSourceFile()).append(":").append(item.getSourceLine()).append("` | `")
                        .append(item.getEndpointPath()).append("` | ")
                        .append(item.getEvidenceDetail() != null ? item.getEvidenceDetail() : "Caller has no typed field access").append(" |\n");
            }
            sb.append("\n");
        }

        if (report.getConfirmedCount() == 0 && report.getLikelyCount() == 0 && report.getUnknownCount() == 0) {
            sb.append("✅ **No internal consumer services are broken by this change!**\n\n");
        }

        if (!report.getSafeChanges().isEmpty()) {
            sb.append("<details><summary><b>View Unaffected / Safe Changes (")
                    .append(report.getSafeChanges().size()).append(")</b></summary>\n\n");
            sb.append("| Change Type | Endpoint | Field | Severity |\n");
            sb.append("|---|---|---|---|\n");
            for (SchemaChangeSummary safe : report.getSafeChanges()) {
                sb.append("| `").append(safe.getChangeType()).append("` | `")
                        .append(safe.getEndpointPath()).append("` | `")
                        .append(safe.getFieldPath() != null ? safe.getFieldPath() : "-").append("` | ")
                        .append(safe.getSeverity()).append(" |\n");
            }
            sb.append("</details>\n");
        }

        return sb.toString();
    }
}
