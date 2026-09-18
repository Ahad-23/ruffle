package com.changedetector.registry.ingestion;


import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public class OpenApiParserService {

    private static final Logger log = LoggerFactory.getLogger(OpenApiParserService.class);

    public static class ParsedSpecResult {
        private final OpenAPI openAPI;
        private final String specHash;
        private final String rawSpec;
        private final List<EndpointWithFields> endpoints;

        public ParsedSpecResult(OpenAPI openAPI, String specHash, String rawSpec, List<EndpointWithFields> endpoints) {
            this.openAPI = openAPI;
            this.specHash = specHash;
            this.rawSpec = rawSpec;
            this.endpoints = endpoints;
        }

        public OpenAPI getOpenAPI() {
            return openAPI;
        }

        public String getSpecHash() {
            return specHash;
        }

        public String getRawSpec() {
            return rawSpec;
        }

        public List<EndpointWithFields> getEndpoints() {
            return endpoints;
        }
    }

    public static class EndpointWithFields {
        private final String httpMethod;
        private final String path;
        private final String operationId;
        private final String summary;
        private final List<ParsedField> fields;

        public EndpointWithFields(String httpMethod, String path, String operationId, String summary, List<ParsedField> fields) {
            this.httpMethod = httpMethod;
            this.path = path;
            this.operationId = operationId;
            this.summary = summary;
            this.fields = fields;
        }

        public String getHttpMethod() {
            return httpMethod;
        }

        public String getPath() {
            return path;
        }

        public String getOperationId() {
            return operationId;
        }

        public String getSummary() {
            return summary;
        }

        public List<ParsedField> getFields() {
            return fields;
        }
    }

    public static class ParsedField {
        private final String responseCode;
        private final String fieldPath;
        private final String fieldType;
        private final boolean isRequired;
        private final String parentSchema;

        public ParsedField(String responseCode, String fieldPath, String fieldType, boolean isRequired, String parentSchema) {
            this.responseCode = responseCode;
            this.fieldPath = fieldPath;
            this.fieldType = fieldType;
            this.isRequired = isRequired;
            this.parentSchema = parentSchema;
        }

        public String getResponseCode() {
            return responseCode;
        }

        public String getFieldPath() {
            return fieldPath;
        }

        public String getFieldType() {
            return fieldType;
        }

        public boolean isRequired() {
            return isRequired;
        }

        public String getParentSchema() {
            return parentSchema;
        }
    }

    public ParsedSpecResult parseSpec(String rawSpec) {
        if (rawSpec == null || rawSpec.trim().isEmpty()) {
            throw new IllegalArgumentException("OpenAPI specification cannot be empty");
        }

        String specHash = computeSha256(rawSpec);

        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);

        SwaggerParseResult parseResult = new OpenAPIParser().readContents(rawSpec, null, options);
        if (parseResult.getOpenAPI() == null) {
            String errors = parseResult.getMessages() != null ? String.join("; ", parseResult.getMessages()) : "Unknown parsing error";
            throw new IllegalArgumentException("Failed to parse OpenAPI spec: " + errors);
        }

        OpenAPI openAPI = parseResult.getOpenAPI();
        List<EndpointWithFields> endpoints = extractEndpoints(openAPI);

        return new ParsedSpecResult(openAPI, specHash, rawSpec, endpoints);
    }

    public String computeSha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private List<EndpointWithFields> extractEndpoints(OpenAPI openAPI) {
        List<EndpointWithFields> endpoints = new ArrayList<>();
        if (openAPI.getPaths() == null) {
            return endpoints;
        }

        openAPI.getPaths().forEach((path, pathItem) -> {
            Map<String, Operation> operations = getOperations(pathItem);
            operations.forEach((method, operation) -> {
                if (operation != null) {
                    List<ParsedField> fields = extractResponseFields(operation, openAPI);
                    endpoints.add(new EndpointWithFields(
                            method.toUpperCase(),
                            path,
                            operation.getOperationId(),
                            operation.getSummary(),
                            fields
                    ));
                }
            });
        });

        return endpoints;
    }

    private Map<String, Operation> getOperations(PathItem pathItem) {
        Map<String, Operation> ops = new LinkedHashMap<>();
        if (pathItem.getGet() != null) ops.put("GET", pathItem.getGet());
        if (pathItem.getPost() != null) ops.put("POST", pathItem.getPost());
        if (pathItem.getPut() != null) ops.put("PUT", pathItem.getPut());
        if (pathItem.getDelete() != null) ops.put("DELETE", pathItem.getDelete());
        if (pathItem.getPatch() != null) ops.put("PATCH", pathItem.getPatch());
        if (pathItem.getHead() != null) ops.put("HEAD", pathItem.getHead());
        if (pathItem.getOptions() != null) ops.put("OPTIONS", pathItem.getOptions());
        return ops;
    }

    private List<ParsedField> extractResponseFields(Operation operation, OpenAPI openAPI) {
        List<ParsedField> fields = new ArrayList<>();
        if (operation.getResponses() == null) {
            return fields;
        }

        operation.getResponses().forEach((code, response) -> {
            if (response.getContent() != null) {
                response.getContent().forEach((mediaTypeName, mediaType) -> {
                    if (mediaType.getSchema() != null) {
                        Schema<?> rootSchema = mediaType.getSchema();
                        Set<String> visited = new HashSet<>();
                        flattenSchema(rootSchema, "", code, rootSchema.getName(), openAPI, fields, visited);
                    }
                });
            }
        });

        return fields;
    }

    @SuppressWarnings("rawtypes")
    private void flattenSchema(Schema<?> schema, String prefix, String responseCode, String parentSchema,
                               OpenAPI openAPI, List<ParsedField> fields, Set<String> visited) {
        if (schema == null) return;

        // Dereference if $ref is present
        if (schema.get$ref() != null) {
            String ref = schema.get$ref();
            if (visited.contains(ref)) {
                return; // Avoid circular reference infinite recursion
            }
            visited.add(ref);
            String schemaName = ref.substring(ref.lastIndexOf('/') + 1);
            if (openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null) {
                Schema<?> refSchema = openAPI.getComponents().getSchemas().get(schemaName);
                if (refSchema != null) {
                    flattenSchema(refSchema, prefix, responseCode, schemaName, openAPI, fields, visited);
                    return;
                }
            }
        }

        // Handle Array
        if (schema instanceof ArraySchema || "array".equalsIgnoreCase(schema.getType())) {
            Schema<?> itemsSchema = schema.getItems();
            if (itemsSchema != null) {
                String arrayPrefix = prefix.isEmpty() ? "[]" : prefix + "[]";
                // If items is a primitive or direct type
                if (itemsSchema.getProperties() == null && itemsSchema.get$ref() == null) {
                    String itemType = itemsSchema.getType() != null ? itemsSchema.getType() : "object";
                    fields.add(new ParsedField(responseCode, arrayPrefix, "array<" + itemType + ">", false, parentSchema));
                } else {
                    flattenSchema(itemsSchema, arrayPrefix, responseCode, parentSchema, openAPI, fields, visited);
                }
            }
            return;
        }

        // Handle Object Properties
        Map<String, Schema> properties = schema.getProperties();
        List<String> requiredList = schema.getRequired() != null ? schema.getRequired() : Collections.emptyList();

        if (properties != null && !properties.isEmpty()) {
            properties.forEach((propName, propSchema) -> {
                String currentPath = prefix.isEmpty() ? propName : (prefix.endsWith("[]") ? prefix + "." + propName : prefix + "." + propName);
                boolean isReq = requiredList.contains(propName);
                String propType = determineType(propSchema);

                fields.add(new ParsedField(responseCode, currentPath, propType, isReq, parentSchema));

                // Recurse into nested structures
                if (propSchema.getProperties() != null || propSchema.getItems() != null || propSchema.get$ref() != null) {
                    flattenSchema(propSchema, currentPath, responseCode, parentSchema, openAPI, fields, new HashSet<>(visited));
                }
            });
        } else if (prefix.isEmpty()) {
            // Root primitive or empty schema
            String type = determineType(schema);
            fields.add(new ParsedField(responseCode, "_root", type, false, parentSchema));
        }
    }

    private String determineType(Schema<?> schema) {
        if (schema == null) return "unknown";
        if (schema instanceof ArraySchema || "array".equalsIgnoreCase(schema.getType())) {
            if (schema.getItems() != null) {
                return "array<" + determineType(schema.getItems()) + ">";
            }
            return "array";
        }
        if (schema.getType() != null) {
            if (schema.getFormat() != null) {
                return schema.getType() + "(" + schema.getFormat() + ")";
            }
            return schema.getType();
        }
        if (schema.get$ref() != null) {
            String ref = schema.get$ref();
            return ref.substring(ref.lastIndexOf('/') + 1);
        }
        return "object";
    }
}
