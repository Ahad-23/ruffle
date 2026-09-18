package com.changedetector.registry.ingestion;

import com.changedetector.registry.entity.Endpoint;
import com.changedetector.registry.entity.SchemaField;
import com.changedetector.registry.entity.Service;
import com.changedetector.registry.entity.SpecVersion;
import com.changedetector.registry.repository.EndpointRepository;
import com.changedetector.registry.repository.SchemaFieldRepository;
import com.changedetector.registry.repository.ServiceRepository;
import com.changedetector.registry.repository.SpecVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@org.springframework.stereotype.Service
public class OpenApiIngestionService {

    private static final Logger log = LoggerFactory.getLogger(OpenApiIngestionService.class);

    private final ServiceRepository serviceRepository;
    private final SpecVersionRepository specVersionRepository;
    private final EndpointRepository endpointRepository;
    private final SchemaFieldRepository schemaFieldRepository;
    private final OpenApiParserService openApiParserService;
    private final HttpClient httpClient;

    public OpenApiIngestionService(ServiceRepository serviceRepository,
                                  SpecVersionRepository specVersionRepository,
                                  EndpointRepository endpointRepository,
                                  SchemaFieldRepository schemaFieldRepository,
                                  OpenApiParserService openApiParserService) {
        this.serviceRepository = serviceRepository;
        this.specVersionRepository = specVersionRepository;
        this.endpointRepository = endpointRepository;
        this.schemaFieldRepository = schemaFieldRepository;
        this.openApiParserService = openApiParserService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public static class IngestionResult {
        private final SpecVersion specVersion;
        private final boolean isNew;
        private final int endpointCount;
        private final int fieldCount;
        private final int changesDetected;

        public IngestionResult(SpecVersion specVersion, boolean isNew, int endpointCount, int fieldCount, int changesDetected) {
            this.specVersion = specVersion;
            this.isNew = isNew;
            this.endpointCount = endpointCount;
            this.fieldCount = fieldCount;
            this.changesDetected = changesDetected;
        }

        public SpecVersion getSpecVersion() {
            return specVersion;
        }

        public boolean isNew() {
            return isNew;
        }

        public int getEndpointCount() {
            return endpointCount;
        }

        public int getFieldCount() {
            return fieldCount;
        }

        public int getChangesDetected() {
            return changesDetected;
        }
    }

    @Transactional
    public IngestionResult ingestSpecContent(Long serviceId, String rawSpec, String versionTag) {
        Service service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException("Service not found with id: " + serviceId));

        OpenApiParserService.ParsedSpecResult parsed = openApiParserService.parseSpec(rawSpec);

        // Check idempotency: if spec_hash already exists for service
        Optional<SpecVersion> existing = specVersionRepository.findByServiceIdAndSpecHash(serviceId, parsed.getSpecHash());
        if (existing.isPresent()) {
            log.info("Spec with hash {} already exists for service {}. Skipping re-ingestion.", parsed.getSpecHash(), service.getName());
            return new IngestionResult(existing.get(), false, 0, 0, 0);
        }

        SpecVersion specVersion = new SpecVersion(
                service,
                versionTag != null ? versionTag : "v-" + System.currentTimeMillis(),
                parsed.getSpecHash(),
                parsed.getRawSpec()
        );
        specVersion = specVersionRepository.save(specVersion);

        int totalFields = 0;
        for (OpenApiParserService.EndpointWithFields ep : parsed.getEndpoints()) {
            Endpoint endpoint = new Endpoint(
                    specVersion,
                    ep.getHttpMethod(),
                    ep.getPath(),
                    ep.getOperationId(),
                    ep.getSummary()
            );
            endpoint = endpointRepository.save(endpoint);

            List<SchemaField> fieldsToSave = new ArrayList<>();
            for (OpenApiParserService.ParsedField pf : ep.getFields()) {
                fieldsToSave.add(new SchemaField(
                        endpoint,
                        pf.getResponseCode(),
                        pf.getFieldPath(),
                        pf.getFieldType(),
                        pf.isRequired(),
                        pf.getParentSchema()
                ));
            }
            if (!fieldsToSave.isEmpty()) {
                schemaFieldRepository.saveAll(fieldsToSave);
                totalFields += fieldsToSave.size();
            }
        }

        int changesCount = 0;
        return new IngestionResult(specVersion, true, parsed.getEndpoints().size(), totalFields, changesCount);
    }

    @Transactional
    public IngestionResult ingestFromUrl(Long serviceId, String customUrl, String versionTag) {
        Service service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException("Service not found with id: " + serviceId));

        String url = customUrl;
        if (url == null || url.trim().isEmpty()) {
            if (service.getBaseUrl() == null || service.getBaseUrl().trim().isEmpty()) {
                throw new IllegalArgumentException("No URL provided and service " + service.getName() + " has no base_url configured");
            }
            url = service.getBaseUrl().replaceAll("/+$", "") + "/v3/api-docs";
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json, application/yaml, */*")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("Failed to fetch OpenAPI spec from " + url + ": HTTP " + response.statusCode());
            }

            return ingestSpecContent(serviceId, response.body(), versionTag);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to connect to " + url + ": " + e.getMessage(), e);
        }
    }
}
