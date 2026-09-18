package com.changedetector.registry;

import com.changedetector.registry.entity.Endpoint;
import com.changedetector.registry.entity.SchemaField;
import com.changedetector.registry.entity.Service;
import com.changedetector.registry.ingestion.OpenApiIngestionService;
import com.changedetector.registry.repository.EndpointRepository;
import com.changedetector.registry.repository.SchemaFieldRepository;
import com.changedetector.registry.repository.ServiceRepository;
import com.changedetector.registry.repository.SpecVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ContractRegistryIntegrationTest {

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private SpecVersionRepository specVersionRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private SchemaFieldRepository schemaFieldRepository;

    @Autowired
    private OpenApiIngestionService ingestionService;

    private static final String SPEC_V1 = """
            {
              "openapi": "3.0.1",
              "info": { "title": "Customers Service", "version": "1.0.0" },
              "paths": {
                "/owners/{ownerId}": {
                  "get": {
                    "operationId": "getOwner",
                    "responses": {
                      "200": {
                        "description": "Owner details",
                        "content": {
                          "application/json": {
                            "schema": {
                              "type": "object",
                              "properties": {
                                "id": { "type": "integer" },
                                "firstName": { "type": "string" },
                                "lastName": { "type": "string" },
                                "city": { "type": "string" }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """;

    @Test
    public void testServiceRegistrationAndSpecIngestion() {
        // 1. Register Service
        Service service = serviceRepository.save(new Service("customers-service", "http://customers-service:8080"));
        assertNotNull(service.getId());
        assertEquals("customers-service", service.getName());

        // 2. Ingest Spec V1
        OpenApiIngestionService.IngestionResult v1Result = ingestionService.ingestSpecContent(service.getId(), SPEC_V1, "v1.0.0");
        assertTrue(v1Result.isNew());
        assertEquals(1, v1Result.getEndpointCount());
        assertEquals(4, v1Result.getFieldCount());

        // 3. Test SHA-256 Deduplication (Idempotency)
        OpenApiIngestionService.IngestionResult duplicateResult = ingestionService.ingestSpecContent(service.getId(), SPEC_V1, "v1.0.0");
        assertFalse(duplicateResult.isNew(), "Duplicate spec should not create a new version");

        // 4. Verify endpoints and fields stored
        List<Endpoint> endpoints = endpointRepository.findBySpecVersionId(v1Result.getSpecVersion().getId());
        assertEquals(1, endpoints.size());
        assertEquals("/owners/{ownerId}", endpoints.get(0).getPath());
        assertEquals("GET", endpoints.get(0).getHttpMethod());

        List<SchemaField> fields = schemaFieldRepository.findBySpecVersionId(v1Result.getSpecVersion().getId());
        assertEquals(4, fields.size());
        assertTrue(fields.stream().anyMatch(f -> f.getFieldPath().equals("firstName") && f.getFieldType().equals("string")));
        assertTrue(fields.stream().anyMatch(f -> f.getFieldPath().equals("id") && f.getFieldType().equals("integer")));
    }
}
