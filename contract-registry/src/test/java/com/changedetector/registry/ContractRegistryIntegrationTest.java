package com.changedetector.registry;

import com.changedetector.registry.entity.Endpoint;
import com.changedetector.registry.entity.SchemaField;
import com.changedetector.registry.entity.Service;
import com.changedetector.registry.ingestion.OpenApiIngestionService;
import com.changedetector.registry.entity.ConsumerUsage;
import com.changedetector.registry.repository.ConsumerUsageRepository;
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
    private ConsumerUsageRepository consumerUsageRepository;

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

    @Test
    public void testConsumerUsageRegistrationAndLookup() {
        ConsumerUsage usage = ConsumerUsage.builder()
                .consumerService("api-gateway")
                .providerService("customers-service")
                .endpointPath("/owners/{ownerId}")
                .httpMethod("GET")
                .fieldPath("firstName")
                .evidenceType("CONFIRMED")
                .sourceFile("src/main/java/com/example/OwnerGatewayClient.java")
                .sourceLine(42)
                .evidenceDetail("Field accessed via owner.getFirstName()")
                .scanId("scan-001")
                .build();

        consumerUsageRepository.save(usage);

        List<ConsumerUsage> byProvider = consumerUsageRepository.findByProviderService("customers-service");
        assertEquals(1, byProvider.size());
        assertEquals("api-gateway", byProvider.get(0).getConsumerService());
        assertEquals("firstName", byProvider.get(0).getFieldPath());

        List<ConsumerUsage> byEndpoint = consumerUsageRepository.findByProviderServiceAndEndpointPath("customers-service", "/owners/{ownerId}");
        assertEquals(1, byEndpoint.size());

        List<String> consumers = consumerUsageRepository.findDistinctConsumerServicesForProvider("customers-service");
        assertTrue(consumers.contains("api-gateway"));
    }
}
