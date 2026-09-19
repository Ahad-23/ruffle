package com.changedetector.analyzer;

import com.changedetector.analyzer.model.EvidenceType;
import com.changedetector.analyzer.model.FieldUsageFinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ConsumerAnalyzerTest {

    @TempDir
    Path tempDir;

    @Test
    public void testDetectWebClientAndFieldAccess() throws IOException {
        Path srcDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir);

        // Model class
        String visitDetailsCode = """
                package com.example;
                public class VisitDetails {
                    private int petId;
                    private String description;
                    public int getPetId() { return petId; }
                    public String getDescription() { return description; }
                }
                """;
        Files.writeString(srcDir.resolve("VisitDetails.java"), visitDetailsCode);

        // Client caller
        String clientCode = """
                package com.example;
                import org.springframework.web.reactive.function.client.WebClient;
                import reactor.core.publisher.Flux;

                public class VisitClient {
                    private WebClient webClient;

                    public Flux<VisitDetails> getVisitsForPet(int petId) {
                        return webClient.get()
                            .uri("http://visits-service/pets/visits?petId={petId}", petId)
                            .retrieve()
                            .bodyToFlux(VisitDetails.class);
                    }

                    public void processVisit(VisitDetails visit) {
                        int id = visit.getPetId();
                        String desc = visit.getDescription();
                    }
                }
                """;
        Files.writeString(srcDir.resolve("VisitClient.java"), clientCode);

        ProjectScanner scanner = new ProjectScanner(tempDir.toFile(), null);
        List<FieldUsageFinding> findings = scanner.scan();

        assertFalse(findings.isEmpty(), "Should find field usage findings");

        boolean foundPetId = findings.stream().anyMatch(f ->
                "visits-service".equals(f.getProviderService()) &&
                "/pets/visits".equals(f.getEndpointPath()) &&
                "petId".equals(f.getFieldPath()) &&
                f.getEvidenceType() == EvidenceType.CONFIRMED);

        boolean foundDesc = findings.stream().anyMatch(f ->
                "visits-service".equals(f.getProviderService()) &&
                "/pets/visits".equals(f.getEndpointPath()) &&
                "description".equals(f.getFieldPath()) &&
                f.getEvidenceType() == EvidenceType.CONFIRMED);

        assertTrue(foundPetId, "Should detect CONFIRMED usage of field 'petId'");
        assertTrue(foundDesc, "Should detect CONFIRMED usage of field 'description'");
    }
}
