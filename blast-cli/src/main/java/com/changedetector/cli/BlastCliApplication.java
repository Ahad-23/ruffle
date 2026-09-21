package com.changedetector.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "blast-cli",
        mixinStandardHelpOptions = true,
        version = "1.0.0",
        description = "CLI tool to trigger blast radius calculation and format Markdown PR reports"
)
public class BlastCliApplication implements Callable<Integer> {

    @Option(names = {"-r", "--registry-url"}, defaultValue = "http://localhost:8080", description = "Base URL of the contract registry service")
    private String registryUrl;

    @Option(names = {"-s", "--service-id"}, required = true, description = "Service ID in the registry")
    private Long serviceId;

    @Option(names = {"--old-version-id"}, description = "Old spec version ID (defaults to previous ingested version)")
    private Long oldVersionId;

    @Option(names = {"--new-version-id"}, description = "New spec version ID (defaults to latest ingested version)")
    private Long newVersionId;

    @Option(names = {"-f", "--format"}, defaultValue = "markdown", description = "Output format: markdown, json")
    private String format;

    @Option(names = {"-o", "--output-file"}, description = "File to write output to (e.g. pr-comment.md)")
    private File outputFile;

    @Option(names = {"--fail-on-breaking"}, description = "Exit with code 1 if confirmed breaking changes are detected")
    private boolean failOnBreaking;

    private HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) {
        int exitCode = new CommandLine(new BlastCliApplication()).execute(args);
        System.exit(exitCode);
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public Integer call() throws Exception {
        String url = registryUrl.replaceAll("/+$", "") + "/api/blast-radius";

        Map<String, Object> payload = new HashMap<>();
        payload.put("serviceId", serviceId);
        if (oldVersionId != null) payload.put("oldVersionId", oldVersionId);
        if (newVersionId != null) payload.put("newVersionId", newVersionId);

        String jsonPayload = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            System.err.println("❌ Registry error (HTTP " + response.statusCode() + "): " + response.body());
            return 1;
        }

        JsonNode rootNode = objectMapper.readTree(response.body());
        int confirmedCount = rootNode.path("confirmedCount").asInt(0);
        int likelyCount = rootNode.path("likelyCount").asInt(0);
        int unknownCount = rootNode.path("unknownCount").asInt(0);

        String outputContent;
        if ("json".equalsIgnoreCase(format)) {
            outputContent = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode);
        } else {
            outputContent = formatMarkdown(rootNode);
        }

        System.out.println(outputContent);

        if (outputFile != null) {
            Files.writeString(outputFile.toPath(), outputContent);
            System.out.println("\n💾 Saved report to: " + outputFile.getAbsolutePath());
        }

        if (failOnBreaking && confirmedCount > 0) {
            System.err.println("\n🚨 Process failed: Found " + confirmedCount + " CONFIRMED breaking changes for downstream consumers!");
            return 1;
        }

        return 0;
    }

    public String formatMarkdown(JsonNode report) {
        StringBuilder sb = new StringBuilder();
        String service = report.path("providerService").asText();
        String oldTag = report.path("oldVersionTag").asText();
        String newTag = report.path("newVersionTag").asText();
        int confirmedCount = report.path("confirmedCount").asInt(0);
        int likelyCount = report.path("likelyCount").asInt(0);
        int unknownCount = report.path("unknownCount").asInt(0);

        sb.append("## 🚨 Cross-Service Breaking Change Detection Report\n\n");
        sb.append("**Provider Service:** `").append(service).append("`\n");
        sb.append("**Comparison:** `").append(oldTag).append("` ➔ `").append(newTag).append("`\n\n");

        sb.append("| Bucket | Count | Description |\n");
        sb.append("|---|---|---|\n");
        sb.append("| 🔴 **CONFIRMED** | **").append(confirmedCount).append("** | Static proof of field access that will break |\n");
        sb.append("| 🟡 **LIKELY** | **").append(likelyCount).append("** | Dynamic / Map / JSON access to modified field |\n");
        sb.append("| ⚪ **UNKNOWN** | **").append(unknownCount).append("** | Endpoint caller with unresolvable field usage |\n\n");

        JsonNode confirmedArray = report.path("confirmedImpacts");
        if (confirmedArray.isArray() && confirmedArray.size() > 0) {
            sb.append("### 🔴 CONFIRMED Breaking Changes (Action Required)\n\n");
            sb.append("| Consumer Service | Source Location | Change Type | Endpoint | Field | Details |\n");
            sb.append("|---|---|---|---|---|---|\n");
            for (JsonNode item : confirmedArray) {
                sb.append("| `").append(item.path("consumerService").asText()).append("` | `")
                        .append(item.path("sourceFile").asText()).append(":").append(item.path("sourceLine").asInt()).append("` | `")
                        .append(item.path("changeType").asText()).append("` | `")
                        .append(item.path("endpointPath").asText()).append("` | `")
                        .append(item.path("fieldPath").asText("-")).append("` | ")
                        .append(item.path("evidenceDetail").asText("-")).append(" |\n");
            }
            sb.append("\n");
        }

        JsonNode likelyArray = report.path("likelyImpacts");
        if (likelyArray.isArray() && likelyArray.size() > 0) {
            sb.append("### 🟡 LIKELY Impacted Consumers\n\n");
            sb.append("| Consumer Service | Source Location | Change Type | Endpoint | Field | Details |\n");
            sb.append("|---|---|---|---|---|---|\n");
            for (JsonNode item : likelyArray) {
                sb.append("| `").append(item.path("consumerService").asText()).append("` | `")
                        .append(item.path("sourceFile").asText()).append(":").append(item.path("sourceLine").asInt()).append("` | `")
                        .append(item.path("changeType").asText()).append("` | `")
                        .append(item.path("endpointPath").asText()).append("` | `")
                        .append(item.path("fieldPath").asText("-")).append("` | ")
                        .append(item.path("evidenceDetail").asText("-")).append(" |\n");
            }
            sb.append("\n");
        }

        JsonNode unknownArray = report.path("unknownImpacts");
        if (unknownArray.isArray() && unknownArray.size() > 0) {
            sb.append("### ⚪ UNKNOWN Field Visibility (Manual Verification Needed)\n\n");
            sb.append("| Consumer Service | Source Location | Endpoint | Evidence Reason |\n");
            sb.append("|---|---|---|---|\n");
            for (JsonNode item : unknownArray) {
                sb.append("| `").append(item.path("consumerService").asText()).append("` | `")
                        .append(item.path("sourceFile").asText()).append(":").append(item.path("sourceLine").asInt()).append("` | `")
                        .append(item.path("endpointPath").asText()).append("` | ")
                        .append(item.path("evidenceDetail").asText("-")).append(" |\n");
            }
            sb.append("\n");
        }

        if (confirmedCount == 0 && likelyCount == 0 && unknownCount == 0) {
            sb.append("✅ **No internal consumer services are broken by this change!**\n\n");
        }

        return sb.toString();
    }
}
