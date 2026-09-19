package com.changedetector.analyzer.client;

import com.changedetector.analyzer.model.FieldUsageFinding;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

public class RegistryApiClient {

    private final String registryUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public RegistryApiClient(String registryUrl) {
        this.registryUrl = registryUrl.replaceAll("/+$", "");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public void registerFindings(String consumerService, String scanId, List<FieldUsageFinding> findings) throws IOException, InterruptedException {
        String endpoint = registryUrl + "/api/consumer-usage/register";

        List<Map<String, Object>> findingDtos = new ArrayList<>();
        for (FieldUsageFinding f : findings) {
            Map<String, Object> dto = new HashMap<>();
            dto.put("providerService", f.getProviderService());
            dto.put("endpointPath", f.getEndpointPath());
            dto.put("httpMethod", f.getHttpMethod());
            dto.put("fieldPath", f.getFieldPath());
            dto.put("evidenceType", f.getEvidenceType().name());
            dto.put("sourceFile", f.getSourceFile());
            dto.put("sourceLine", f.getSourceLine());
            dto.put("evidenceDetail", f.getEvidenceDetail());
            findingDtos.add(dto);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("consumerService", consumerService);
        payload.put("scanId", scanId != null ? scanId : UUID.randomUUID().toString());
        payload.put("replaceExisting", true);
        payload.put("findings", findingDtos);

        String json = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Registry returned error status " + response.statusCode() + ": " + response.body());
        }
    }
}
