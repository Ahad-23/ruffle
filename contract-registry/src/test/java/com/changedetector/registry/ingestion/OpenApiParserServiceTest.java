package com.changedetector.registry.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class OpenApiParserServiceTest {

    private OpenApiParserService parserService;

    @BeforeEach
    public void setUp() {
        parserService = new OpenApiParserService();
    }

    private static final String JSON_SPEC = """
            {
              "openapi": "3.0.1",
              "info": { "title": "Pet Store API", "version": "1.0.0" },
              "paths": {
                "/pets": {
                  "post": {
                    "operationId": "createPet",
                    "summary": "Create a pet",
                    "responses": {
                      "201": {
                        "description": "Pet created",
                        "content": {
                          "application/json": {
                            "schema": {
                              "type": "object",
                              "required": ["id", "name"],
                              "properties": {
                                "id": { "type": "integer" },
                                "name": { "type": "string" },
                                "tag": { "type": "string" },
                                "owner": {
                                  "type": "object",
                                  "properties": {
                                    "address": { "type": "string" },
                                    "phone": { "type": "string" }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                },
                "/pets/{petId}": {
                  "get": {
                    "operationId": "getPetById",
                    "summary": "Get pet by ID",
                    "responses": {
                      "200": {
                        "description": "Pet details",
                        "content": {
                          "application/json": {
                            "schema": {
                              "type": "object",
                              "properties": {
                                "id": { "type": "integer" },
                                "name": { "type": "string" }
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

    private static final String YAML_SPEC = """
            openapi: 3.0.1
            info:
              title: Vets Service
              version: 1.0.0
            paths:
              /vets:
                get:
                  operationId: listVets
                  summary: List all veterinarians
                  responses:
                    '200':
                      description: List of vets
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              id:
                                type: integer
                              specialty:
                                type: string
            """;

    @Test
    public void testParseValidJsonSpecWithNestedAndArrayFields() {
        OpenApiParserService.ParsedSpecResult result = parserService.parseSpec(JSON_SPEC);

        assertNotNull(result);
        assertNotNull(result.getOpenAPI());
        assertNotNull(result.getSpecHash());
        assertEquals(64, result.getSpecHash().length(), "SHA-256 hash must be 64 characters");

        // Endpoints verification
        assertEquals(2, result.getEndpoints().size());

        OpenApiParserService.EndpointWithFields postEndpoint = result.getEndpoints().stream()
                .filter(e -> "/pets".equals(e.getPath()) && "POST".equals(e.getHttpMethod()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("POST /pets not found"));
        assertEquals("createPet", postEndpoint.getOperationId());

        // Fields verification
        assertTrue(postEndpoint.getFields().stream().anyMatch(f -> "id".equals(f.getFieldPath()) && f.isRequired()));
        assertTrue(postEndpoint.getFields().stream().anyMatch(f -> "name".equals(f.getFieldPath()) && f.isRequired()));
        assertTrue(postEndpoint.getFields().stream().anyMatch(f -> "tag".equals(f.getFieldPath()) && !f.isRequired()));
        assertTrue(postEndpoint.getFields().stream().anyMatch(f -> "owner.address".equals(f.getFieldPath())));
        assertTrue(postEndpoint.getFields().stream().anyMatch(f -> "owner.phone".equals(f.getFieldPath())));
    }

    @Test
    public void testParseValidYamlSpec() {
        OpenApiParserService.ParsedSpecResult result = parserService.parseSpec(YAML_SPEC);

        assertNotNull(result);
        assertEquals(1, result.getEndpoints().size());

        OpenApiParserService.EndpointWithFields getEndpoint = result.getEndpoints().get(0);
        assertEquals("/vets", getEndpoint.getPath());
        assertEquals("GET", getEndpoint.getHttpMethod());
        assertEquals(2, getEndpoint.getFields().size());
        assertTrue(getEndpoint.getFields().stream().anyMatch(f -> "specialty".equals(f.getFieldPath()) && "string".equals(f.getFieldType())));
    }

    @Test
    public void testParseEmptyOrNullSpecThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> parserService.parseSpec(null));
        assertThrows(IllegalArgumentException.class, () -> parserService.parseSpec("   "));
    }

    @Test
    public void testParseInvalidSpecThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> parserService.parseSpec("not a valid openapi spec 1234"));
    }

    @Test
    public void testComputeSha256Determinism() {
        String hash1 = parserService.computeSha256(JSON_SPEC);
        String hash2 = parserService.computeSha256(JSON_SPEC);
        assertEquals(hash1, hash2, "Hashing same content must be deterministic");

        String hash3 = parserService.computeSha256(YAML_SPEC);
        assertNotEquals(hash1, hash3, "Different content must produce different hashes");
    }
}
