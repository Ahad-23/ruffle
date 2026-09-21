package com.changedetector.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BlastCliApplicationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private BlastCliApplication cli;

    @BeforeEach
    void setUp() {
        cli = new BlastCliApplication();
    }

    @Test
    void shouldDisplayHelpInformation() {
        CommandLine cmd = new CommandLine(cli);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--help");

        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("blast-cli"));
        assertTrue(output.contains("--service-id"));
        assertTrue(output.contains("--registry-url"));
        assertTrue(output.contains("--fail-on-breaking"));
    }

    @Test
    void shouldFailWhenMissingRequiredServiceId() {
        CommandLine cmd = new CommandLine(cli);
        StringWriter sw = new StringWriter();
        cmd.setErr(new PrintWriter(sw));

        int exitCode = cmd.execute();

        assertNotEquals(0, exitCode);
        assertTrue(sw.toString().contains("Missing required option: '--service-id=<serviceId>'"));
    }

    @Test
    void shouldFormatMarkdownWhenNoChangesAreDetected() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("providerService", "customers-service");
        root.put("oldVersionTag", "1.0.0");
        root.put("newVersionTag", "1.1.0");
        root.put("confirmedCount", 0);
        root.put("likelyCount", 0);
        root.put("unknownCount", 0);

        String markdown = cli.formatMarkdown(root);

        assertNotNull(markdown);
        assertTrue(markdown.contains("Cross-Service Breaking Change Detection Report"));
        assertTrue(markdown.contains("customers-service"));
        assertTrue(markdown.contains("No internal consumer services are broken by this change!"));
    }

    @Test
    void shouldFormatMarkdownWithConfirmedLikelyAndUnknownImpacts() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("providerService", "vets-service");
        root.put("oldVersionTag", "2.0.0");
        root.put("newVersionTag", "2.1.0");
        root.put("confirmedCount", 1);
        root.put("likelyCount", 1);
        root.put("unknownCount", 1);

        ArrayNode confirmed = root.putArray("confirmedImpacts");
        ObjectNode cItem = confirmed.addObject();
        cItem.put("consumerService", "api-gateway");
        cItem.put("sourceFile", "VetClient.java");
        cItem.put("sourceLine", 42);
        cItem.put("changeType", "FIELD_REMOVED");
        cItem.put("endpointPath", "/vets");
        cItem.put("fieldPath", "specialties");
        cItem.put("evidenceDetail", "Direct getter access getSpecialties()");

        ArrayNode likely = root.putArray("likelyImpacts");
        ObjectNode lItem = likely.addObject();
        lItem.put("consumerService", "admin-service");
        lItem.put("sourceFile", "VetAdmin.java");
        lItem.put("sourceLine", 88);
        lItem.put("changeType", "FIELD_TYPE_CHANGED");
        lItem.put("endpointPath", "/vets/{id}");
        lItem.put("fieldPath", "name");
        lItem.put("evidenceDetail", "Dynamic map access");

        ArrayNode unknown = root.putArray("unknownImpacts");
        ObjectNode uItem = unknown.addObject();
        uItem.put("consumerService", "billing-service");
        uItem.put("sourceFile", "BillingService.java");
        uItem.put("sourceLine", 105);
        uItem.put("endpointPath", "/vets/billable");
        uItem.put("evidenceDetail", "Wildcard/generic return type");

        String markdown = cli.formatMarkdown(root);

        assertTrue(markdown.contains("### 🔴 CONFIRMED Breaking Changes (Action Required)"));
        assertTrue(markdown.contains("VetClient.java:42"));
        assertTrue(markdown.contains("FIELD_REMOVED"));
        assertTrue(markdown.contains("getSpecialties()"));

        assertTrue(markdown.contains("### 🟡 LIKELY Impacted Consumers"));
        assertTrue(markdown.contains("VetAdmin.java:88"));
        assertTrue(markdown.contains("Dynamic map access"));

        assertTrue(markdown.contains("### ⚪ UNKNOWN Field Visibility (Manual Verification Needed)"));
        assertTrue(markdown.contains("BillingService.java:105"));
        assertTrue(markdown.contains("Wildcard/generic return type"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExecuteCallSuccessfullyAndWriteOutputFile(@TempDir Path tempDir) throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);

        ObjectNode responseJson = objectMapper.createObjectNode();
        responseJson.put("providerService", "visits-service");
        responseJson.put("oldVersionTag", "1.0");
        responseJson.put("newVersionTag", "2.0");
        responseJson.put("confirmedCount", 0);
        responseJson.put("likelyCount", 0);
        responseJson.put("unknownCount", 0);

        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson.toString());
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        File outputFile = tempDir.resolve("report.md").toFile();

        BlastCliApplication application = new BlastCliApplication();
        application.setHttpClient(mockHttpClient);

        CommandLine cmd = new CommandLine(application);
        int exitCode = cmd.execute(
                "-s", "1",
                "-r", "http://localhost:8080",
                "-o", outputFile.getAbsolutePath()
        );

        assertEquals(0, exitCode);
        assertTrue(outputFile.exists());
        String written = Files.readString(outputFile.toPath());
        assertTrue(written.contains("visits-service"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldFailWhenFailOnBreakingAndConfirmedBreaksExist() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);

        ObjectNode responseJson = objectMapper.createObjectNode();
        responseJson.put("providerService", "visits-service");
        responseJson.put("oldVersionTag", "1.0");
        responseJson.put("newVersionTag", "2.0");
        responseJson.put("confirmedCount", 2);
        responseJson.put("likelyCount", 0);
        responseJson.put("unknownCount", 0);

        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson.toString());
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        BlastCliApplication application = new BlastCliApplication();
        application.setHttpClient(mockHttpClient);

        CommandLine cmd = new CommandLine(application);
        int exitCode = cmd.execute(
                "-s", "1",
                "--fail-on-breaking"
        );

        assertEquals(1, exitCode);
    }
}
