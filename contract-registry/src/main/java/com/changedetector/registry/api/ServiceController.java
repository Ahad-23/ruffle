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
import com.changedetector.registry.diff.SchemaDiffEngine;
import com.changedetector.registry.entity.SchemaChange;
import com.changedetector.registry.repository.SchemaChangeRepository;
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
    private final SchemaDiffEngine diffEngine;
    private final SchemaChangeRepository schemaChangeRepository;

    public ServiceController(ServiceRepository serviceRepository,
                             SpecVersionRepository specVersionRepository,
                             EndpointRepository endpointRepository,
                             SchemaFieldRepository schemaFieldRepository,
                             OpenApiIngestionService ingestionService,
                             SchemaDiffEngine diffEngine,
                             SchemaChangeRepository schemaChangeRepository) {
        this.serviceRepository = serviceRepository;
        this.specVersionRepository = specVersionRepository;
        this.endpointRepository = endpointRepository;
        this.schemaFieldRepository = schemaFieldRepository;
        this.ingestionService = ingestionService;
        this.diffEngine = diffEngine;
        this.schemaChangeRepository = schemaChangeRepository;
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

    @GetMapping("/{id}/diff")
    public ResponseEntity<?> getDiff(
            @PathVariable Long id,
            @RequestParam(required = false) Long oldVersionId,
            @RequestParam(required = false) Long newVersionId) {
        var serviceOpt = serviceRepository.findById(id);
        if (serviceOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var service = serviceOpt.get();

        Long oldId = oldVersionId;
        Long newId = newVersionId;

        if (oldId == null || newId == null) {
            List<SpecVersion> versions = specVersionRepository.findByServiceIdOrderByIngestedAtDesc(id);
            if (versions.size() < 2) {
                return ResponseEntity.badRequest().body(Map.of("error", "Service must have at least 2 versions to calculate diff"));
            }
            newId = versions.get(0).getId();
            oldId = versions.get(1).getId();
        }

        var oldVersionOpt = specVersionRepository.findById(oldId);
        var newVersionOpt = specVersionRepository.findById(newId);
        if (oldVersionOpt.isEmpty() || newVersionOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid version IDs provided"));
        }

        List<SchemaChange> cachedChanges = schemaChangeRepository.findByOldSpecVersionIdAndNewSpecVersionId(oldId, newId);
        if (!cachedChanges.isEmpty()) {
            return ResponseEntity.ok(cachedChanges);
        }

        List<SchemaChange> computed = diffEngine.computeAndSaveDiff(service, oldVersionOpt.get(), newVersionOpt.get());
        return ResponseEntity.ok(computed);
    }
}
