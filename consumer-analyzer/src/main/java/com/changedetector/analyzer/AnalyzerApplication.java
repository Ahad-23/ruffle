package com.changedetector.analyzer;

import com.changedetector.analyzer.client.RegistryApiClient;
import com.changedetector.analyzer.model.FieldUsageFinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(
        name = "consumer-analyzer",
        mixinStandardHelpOptions = true,
        version = "1.0.0",
        description = "Static AST analysis of Spring Boot consumers to detect cross-service API field usages"
)
public class AnalyzerApplication implements Callable<Integer> {

    @Option(names = {"-p", "--project-dir"}, required = true, description = "Path to the consumer service project root directory")
    private File projectDir;

    @Option(names = {"-c", "--consumer-name"}, required = true, description = "Name of the consumer service (e.g. api-gateway)")
    private String consumerName;

    @Option(names = {"-r", "--registry-url"}, defaultValue = "http://localhost:8080", description = "Base URL of the contract registry service")
    private String registryUrl;

    @Option(names = {"--classpath-dir"}, description = "Directory containing compiled JARs/dependencies for type resolution")
    private File classpathDir;

    @Option(names = {"--scan-id"}, description = "Unique scan identifier (default: random UUID)")
    private String scanId;

    @Option(names = {"--dry-run"}, description = "Output findings to stdout without publishing to contract registry")
    private boolean dryRun;

    @Option(names = {"-v", "--verbose"}, description = "Enable verbose output")
    private boolean verbose;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new AnalyzerApplication()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        System.out.println("===============================================================");
        System.out.println(" 🔍 Cross-Service Consumer Static Analyzer");
        System.out.println(" Consumer: " + consumerName);
        System.out.println(" Project:  " + projectDir.getAbsolutePath());
        System.out.println("===============================================================");

        if (scanId == null || scanId.isBlank()) {
            scanId = "scan-" + UUID.randomUUID().toString().substring(0, 8);
        }

        ProjectScanner scanner = new ProjectScanner(projectDir, classpathDir);
        List<FieldUsageFinding> findings = scanner.scan();

        System.out.println("\n📊 Analysis Results:");
        long confirmed = findings.stream().filter(f -> f.getEvidenceType().name().equals("CONFIRMED")).count();
        long likely = findings.stream().filter(f -> f.getEvidenceType().name().equals("LIKELY")).count();
        long unknown = findings.stream().filter(f -> f.getEvidenceType().name().equals("UNKNOWN")).count();

        System.out.printf("   Total Findings: %d\n", findings.size());
        System.out.printf("   - 🔴 CONFIRMED: %d\n", confirmed);
        System.out.printf("   - 🟡 LIKELY:    %d\n", likely);
        System.out.printf("   - ⚪ UNKNOWN:   %d\n", unknown);

        if (verbose || dryRun) {
            System.out.println("\n🔎 Details of Findings:");
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            System.out.println(mapper.writeValueAsString(findings));
        }

        if (!dryRun) {
            System.out.println("\n🚀 Publishing findings to Contract Registry at: " + registryUrl);
            try {
                RegistryApiClient client = new RegistryApiClient(registryUrl);
                client.registerFindings(consumerName, scanId, findings);
                System.out.println("✅ Successfully published " + findings.size() + " findings (Scan ID: " + scanId + ")");
            } catch (Exception e) {
                System.err.println("❌ Failed to publish findings to registry: " + e.getMessage());
                return 1;
            }
        } else {
            System.out.println("\nℹ️ [Dry Run] Skipped publishing to registry.");
        }

        return 0;
    }
}
