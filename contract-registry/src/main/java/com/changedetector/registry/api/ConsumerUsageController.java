package com.changedetector.registry.api;

import com.changedetector.registry.entity.ConsumerUsage;
import com.changedetector.registry.repository.ConsumerUsageRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/consumer-usage")
@CrossOrigin(origins = "*")
public class ConsumerUsageController {

    private final ConsumerUsageRepository consumerUsageRepository;

    public ConsumerUsageController(ConsumerUsageRepository consumerUsageRepository) {
        this.consumerUsageRepository = consumerUsageRepository;
    }

    public record RegisterUsageRequest(
            @NotBlank String consumerService,
            String scanId,
            boolean replaceExisting,
            @NotEmpty List<UsageFindingDto> findings
    ) {}

    public record UsageFindingDto(
            @NotBlank String providerService,
            @NotBlank String endpointPath,
            String httpMethod,
            String fieldPath,
            @NotBlank String evidenceType, // CONFIRMED, LIKELY, UNKNOWN
            @NotBlank String sourceFile,
            int sourceLine,
            String evidenceDetail
    ) {}

    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> registerUsages(@Valid @RequestBody RegisterUsageRequest req) {
        if (req.replaceExisting()) {
            consumerUsageRepository.deleteByConsumerService(req.consumerService());
        }

        List<ConsumerUsage> entities = new ArrayList<>();
        for (UsageFindingDto finding : req.findings()) {
            entities.add(new ConsumerUsage(
                    req.consumerService(),
                    finding.providerService(),
                    finding.endpointPath(),
                    finding.httpMethod(),
                    finding.fieldPath(),
                    finding.evidenceType(),
                    finding.sourceFile(),
                    finding.sourceLine(),
                    finding.evidenceDetail(),
                    req.scanId()
            ));
        }

        List<ConsumerUsage> saved = consumerUsageRepository.saveAll(entities);
        return ResponseEntity.ok(Map.of(
                "consumerService", req.consumerService(),
                "registeredCount", saved.size(),
                "scanId", req.scanId() != null ? req.scanId() : ""
        ));
    }

    @GetMapping
    public List<ConsumerUsage> listUsages(
            @RequestParam(required = false) String providerService,
            @RequestParam(required = false) String consumerService) {
        if (providerService != null) {
            return consumerUsageRepository.findByProviderService(providerService);
        }
        if (consumerService != null) {
            return consumerUsageRepository.findByConsumerService(consumerService);
        }
        return consumerUsageRepository.findAll();
    }
}
