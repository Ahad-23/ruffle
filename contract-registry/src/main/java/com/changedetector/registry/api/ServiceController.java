package com.changedetector.registry.api;

import com.changedetector.registry.entity.Endpoint;
import com.changedetector.registry.entity.SchemaField;
import com.changedetector.registry.entity.Service;
import com.changedetector.registry.entity.SpecVersion;
import com.changedetector.registry.ingestion.OpenApiIngestionService;
import com.changedetector.registry.repository.EndpointRepository;
import com.changedetector.registry.repository.SchemaFieldRepository;
import com.changedetector.registry.repository.ServiceRepository;
import com.changedetector.registry.repository.SpecVersionRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/services")
@CrossOrigin(origins = "*")
public class ServiceController {

    private final ServiceRepository serviceRepository;
    private final SpecVersionRepository specVersionRepository;
    private final EndpointRepository endpointRepository;
    private final SchemaFieldRepository schemaFieldRepository;
    private final OpenApiIngestionService ingestionService;

    public ServiceController(ServiceRepository serviceRepository,
                             SpecVersionRepository specVersionRepository,
                             EndpointRepository endpointRepository,
                             SchemaFieldRepository schemaFieldRepository,
                             OpenApiIngestionService ingestionService) {
        this.serviceRepository = serviceRepository;
        this.specVersionRepository = specVersionRepository;
        this.endpointRepository = endpointRepository;
        this.schemaFieldRepository = schemaFieldRepository;
        this.ingestionService = ingestionService;
    }

    public record CreateServiceRequest(@NotBlank String name, String baseUrl) {}
    public record IngestUrlRequest(String url, String versionTag) {}
    public record IngestUploadRequest(@NotBlank String spec, String versionTag) {}

    @GetMapping
    public List<Service> listServices() {
        return serviceRepository.findAll();
    }

    @PostMapping
    public ResponseEntity<Service> createService(@Valid @RequestBody CreateServiceRequest req) {
        if (serviceRepository.findByName(req.name()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        Service entity = new Service(req.name(), req.baseUrl());
        return ResponseEntity.status(HttpStatus.CREATED).body(serviceRepository.save(entity));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Service> getService(@PathVariable Long id) {
        return serviceRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/ingest")
    public ResponseEntity<?> ingestFromUrl(@PathVariable Long id, @RequestBody(required = false) IngestUrlRequest req) {
        String url = req != null ? req.url() : null;
        String versionTag = req != null ? req.versionTag() : null;
        try {
            OpenApiIngestionService.IngestionResult result = ingestionService.ingestFromUrl(id, url, versionTag);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/ingest/upload")
    public ResponseEntity<?> ingestUpload(@PathVariable Long id, @Valid @RequestBody IngestUploadRequest req) {
        try {
            OpenApiIngestionService.IngestionResult result = ingestionService.ingestSpecContent(id, req.spec(), req.versionTag());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/versions")
    public List<SpecVersion> listVersions(@PathVariable Long id) {
        return specVersionRepository.findByServiceIdOrderByIngestedAtDesc(id);
    }

    @GetMapping("/{id}/endpoints")
    public ResponseEntity<List<Endpoint>> listEndpoints(@PathVariable Long id, @RequestParam(required = false) Long versionId) {
        Long vId = versionId;
        if (vId == null) {
            var latest = specVersionRepository.findLatestByServiceId(id);
            if (latest.isEmpty()) return ResponseEntity.ok(List.of());
            vId = latest.get().getId();
        }
        return ResponseEntity.ok(endpointRepository.findBySpecVersionId(vId));
    }

    @GetMapping("/{id}/fields")
    public ResponseEntity<List<SchemaField>> listFields(@PathVariable Long id, @RequestParam(required = false) Long versionId) {
        Long vId = versionId;
        if (vId == null) {
            var latest = specVersionRepository.findLatestByServiceId(id);
            if (latest.isEmpty()) return ResponseEntity.ok(List.of());
            vId = latest.get().getId();
        }
        return ResponseEntity.ok(schemaFieldRepository.findBySpecVersionId(vId));
    }
}
