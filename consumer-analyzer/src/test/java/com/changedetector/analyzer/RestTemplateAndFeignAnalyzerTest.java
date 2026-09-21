package com.changedetector.analyzer;

import com.changedetector.analyzer.model.EvidenceType;
import com.changedetector.analyzer.model.FieldUsageFinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RestTemplateAndFeignAnalyzerTest {

    @TempDir
    Path tempDir;

    @Test
    public void testDetectRestTemplateAndFieldUsage() throws IOException {
        Path srcDir = tempDir.resolve("src/main/java/com/example/rest");
        Files.createDirectories(srcDir);

        // Model
        String ownerDtoCode = """
                package com.example.rest;
                public class OwnerDto {
                    private String firstName;
                    private String city;
                    public String getFirstName() { return firstName; }
                    public String getCity() { return city; }
                }
                """;
        Files.writeString(srcDir.resolve("OwnerDto.java"), ownerDtoCode);

        // Caller using RestTemplate
        String callerCode = """
                package com.example.rest;
                import org.springframework.web.client.RestTemplate;

                public class OwnerService {
                    private RestTemplate restTemplate;

                    public void loadOwner(Long id) {
                        OwnerDto owner = restTemplate.getForObject("http://customers-service/owners/{id}", OwnerDto.class, id);
                        if (owner != null) {
                            String name = owner.getFirstName();
                            String c = owner.getCity();
                        }
                    }
                }
                """;
        Files.writeString(srcDir.resolve("OwnerService.java"), callerCode);

        ProjectScanner scanner = new ProjectScanner(tempDir.toFile(), null);
        List<FieldUsageFinding> findings = scanner.scan();

        assertFalse(findings.isEmpty(), "Should find field usages from RestTemplate call");

        boolean foundFirstName = findings.stream().anyMatch(f ->
                "customers-service".equals(f.getProviderService()) &&
                "/owners/{id}".equals(f.getEndpointPath()) &&
                "firstName".equals(f.getFieldPath()) &&
                f.getEvidenceType() == EvidenceType.CONFIRMED);

        boolean foundCity = findings.stream().anyMatch(f ->
                "customers-service".equals(f.getProviderService()) &&
                "/owners/{id}".equals(f.getEndpointPath()) &&
                "city".equals(f.getFieldPath()) &&
                f.getEvidenceType() == EvidenceType.CONFIRMED);

        assertTrue(foundFirstName, "Should detect CONFIRMED usage of firstName via RestTemplate");
        assertTrue(foundCity, "Should detect CONFIRMED usage of city via RestTemplate");
    }

    @Test
    public void testDetectFeignClientAndFieldUsage() throws IOException {
        Path srcDir = tempDir.resolve("src/main/java/com/example/feign");
        Files.createDirectories(srcDir);

        // DTO
        String vetDtoCode = """
                package com.example.feign;
                public class VetDto {
                    private String specialty;
                    public String getSpecialty() { return specialty; }
                }
                """;
        Files.writeString(srcDir.resolve("VetDto.java"), vetDtoCode);

        // Feign Client Interface
        String feignClientCode = """
                package com.example.feign;
                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;

                @FeignClient(name = "vets-service", path = "/vets")
                public interface VetClient {
                    @GetMapping("/{id}")
                    VetDto getVetById(@PathVariable("id") Long id);
                }
                """;
        Files.writeString(srcDir.resolve("VetClient.java"), feignClientCode);

        // Service using the client
        String serviceCode = """
                package com.example.feign;

                public class VetManager {
                    private VetClient vetClient;

                    public String checkSpecialty(Long vetId) {
                        VetDto vet = vetClient.getVetById(vetId);
                        return vet.getSpecialty();
                    }
                }
                """;
        Files.writeString(srcDir.resolve("VetManager.java"), serviceCode);

        ProjectScanner scanner = new ProjectScanner(tempDir.toFile(), null);
        List<FieldUsageFinding> findings = scanner.scan();

        assertFalse(findings.isEmpty(), "Should find field usages from FeignClient interface");

        boolean foundSpecialty = findings.stream().anyMatch(f ->
                "vets-service".equals(f.getProviderService()) &&
                "/vets/{id}".equals(f.getEndpointPath()) &&
                "specialty".equals(f.getFieldPath()) &&
                f.getEvidenceType() == EvidenceType.CONFIRMED);

        assertTrue(foundSpecialty, "Should detect CONFIRMED usage of specialty via FeignClient");
    }
}
