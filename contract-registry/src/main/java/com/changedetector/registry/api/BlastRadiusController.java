package com.changedetector.registry.api;

import com.changedetector.registry.blast.BlastRadiusService;
import com.changedetector.registry.entity.BlastReport;
import com.changedetector.registry.entity.ConsumerUsage;
import com.changedetector.registry.entity.Service;
import com.changedetector.registry.entity.SpecVersion;
import com.changedetector.registry.repository.BlastReportRepository;
import com.changedetector.registry.repository.ConsumerUsageRepository;
import com.changedetector.registry.repository.ServiceRepository;
import com.changedetector.registry.repository.SpecVersionRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class BlastRadiusController {

    private final BlastRadiusService blastRadiusService;
    private final BlastReportRepository blastReportRepository;
    private final ServiceRepository serviceRepository;
    private final SpecVersionRepository specVersionRepository;
    private final ConsumerUsageRepository consumerUsageRepository;

    public BlastRadiusController(BlastRadiusService blastRadiusService,
                                 BlastReportRepository blastReportRepository,
                                 ServiceRepository serviceRepository,
                                 SpecVersionRepository specVersionRepository,
                                 ConsumerUsageRepository consumerUsageRepository) {
        this.blastRadiusService = blastRadiusService;
        this.blastReportRepository = blastReportRepository;
        this.serviceRepository = serviceRepository;
        this.specVersionRepository = specVersionRepository;
        this.consumerUsageRepository = consumerUsageRepository;
    }

    public record CalculateBlastRequest(
            @NotNull Long serviceId,
            Long oldVersionId,
            Long newVersionId
    ) {}

    @PostMapping("/blast-radius")
    public ResponseEntity<?> calculateBlastRadius(@Valid @RequestBody CalculateBlastRequest req) {
        Long oldId = req.oldVersionId();
        Long newId = req.newVersionId();

        if (oldId == null || newId == null) {
            List<SpecVersion> versions = specVersionRepository.findByServiceIdOrderByIngestedAtDesc(req.serviceId());
            if (versions.size() < 2) {
                return ResponseEntity.badRequest().body(Map.of("error", "Service must have at least 2 versions to calculate blast radius"));
            }
            newId = versions.get(0).getId();
            oldId = versions.get(1).getId();
        }

        BlastRadiusService.BlastRadiusReport report = blastRadiusService.calculateBlastRadius(req.serviceId(), oldId, newId);
        return ResponseEntity.ok(report);
    }

    @GetMapping("/reports")
    public List<BlastReport> listReports(@RequestParam(required = false) Long serviceId) {
        if (serviceId != null) {
            return blastReportRepository.findByServiceIdOrderByGeneratedAtDesc(serviceId);
        }
        return blastReportRepository.findAll();
    }

    @GetMapping("/reports/{id}")
    public ResponseEntity<BlastReport> getReport(@PathVariable Long id) {
        return blastReportRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/reports/{id}/markdown", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getReportMarkdown(@PathVariable Long id) {
        Optional<BlastReport> entityOpt = blastReportRepository.findById(id);
        if (entityOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        BlastReport entity = entityOpt.get();
        BlastRadiusService.BlastRadiusReport report = blastRadiusService.calculateBlastRadius(
                entity.getService().getId(),
                entity.getOldSpecVersion().getId(),
                entity.getNewSpecVersion().getId()
        );
        return ResponseEntity.ok(blastRadiusService.formatMarkdownReport(report));
    }

    @GetMapping("/dependency-graph")
    public ResponseEntity<?> getDependencyGraph() {
        List<Service> services = serviceRepository.findAll();
        List<ConsumerUsage> usages = consumerUsageRepository.findAll();

        List<Map<String, Object>> nodes = new ArrayList<>();
        Set<String> knownServiceNames = new HashSet<>();

        for (Service s : services) {
            knownServiceNames.add(s.getName());
            nodes.add(Map.of(
                    "id", s.getName(),
                    "data", Map.of(
                            "label", s.getName(),
                            "type", "service",
                            "baseUrl", s.getBaseUrl() != null ? s.getBaseUrl() : ""
                    ),
                    "type", "customService"
            ));
        }

        // Add any consumers that might not have a registered service record yet
        for (ConsumerUsage u : usages) {
            if (knownServiceNames.add(u.getConsumerService())) {
                nodes.add(Map.of(
                        "id", u.getConsumerService(),
                        "data", Map.of(
                                "label", u.getConsumerService(),
                                "type", "service",
                                "baseUrl", ""
                        ),
                        "type", "customService"
                ));
            }
        }

        // Group edges by consumer -> provider
        Map<String, Map<String, Object>> edgeMap = new HashMap<>();
        for (ConsumerUsage u : usages) {
            String edgeId = u.getConsumerService() + "->" + u.getProviderService();
            edgeMap.computeIfAbsent(edgeId, k -> {
                Map<String, Object> edge = new HashMap<>();
                edge.put("id", edgeId);
                edge.put("source", u.getConsumerService());
                edge.put("target", u.getProviderService());
                edge.put("animated", true);
                edge.put("data", new HashMap<String, Object>());
                ((Map<String, Object>) edge.get("data")).put("endpoints", new HashSet<String>());
                ((Map<String, Object>) edge.get("data")).put("confirmedCount", 0);
                ((Map<String, Object>) edge.get("data")).put("likelyCount", 0);
                ((Map<String, Object>) edge.get("data")).put("unknownCount", 0);
                return edge;
            });

            Map<String, Object> edge = edgeMap.get(edgeId);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) edge.get("data");
            @SuppressWarnings("unchecked")
            Set<String> endpoints = (Set<String>) data.get("endpoints");
            endpoints.add(u.getEndpointPath());

            if ("CONFIRMED".equalsIgnoreCase(u.getEvidenceType())) {
                data.put("confirmedCount", (int) data.get("confirmedCount") + 1);
            } else if ("LIKELY".equalsIgnoreCase(u.getEvidenceType())) {
                data.put("likelyCount", (int) data.get("likelyCount") + 1);
            } else {
                data.put("unknownCount", (int) data.get("unknownCount") + 1);
            }
        }

        List<Map<String, Object>> edges = new ArrayList<>();
        for (Map<String, Object> edge : edgeMap.values()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) edge.get("data");
            @SuppressWarnings("unchecked")
            Set<String> endpoints = (Set<String>) data.get("endpoints");
            edge.put("label", endpoints.size() + " calls");
            data.put("endpoints", new ArrayList<>(endpoints));
            edges.add(edge);
        }

        return ResponseEntity.ok(Map.of(
                "nodes", nodes,
                "edges", edges
        ));
    }
}
