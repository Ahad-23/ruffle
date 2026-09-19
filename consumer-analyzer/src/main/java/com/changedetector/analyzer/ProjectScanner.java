package com.changedetector.analyzer;

import com.changedetector.analyzer.ast.*;
import com.changedetector.analyzer.model.EvidenceType;
import com.changedetector.analyzer.model.FieldUsageFinding;
import com.changedetector.analyzer.model.ServiceCall;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ProjectScanner {

    private static final Logger log = LoggerFactory.getLogger(ProjectScanner.class);

    private final File projectDir;
    private final File classpathDir;

    public ProjectScanner(File projectDir, File classpathDir) {
        this.projectDir = projectDir;
        this.classpathDir = classpathDir;
    }

    public List<FieldUsageFinding> scan() {
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            throw new IllegalArgumentException("Project directory does not exist: " + projectDir.getAbsolutePath());
        }

        // 1. Setup JavaParser with TypeSolvers
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());

        File srcDir = new File(projectDir, "src/main/java");
        if (srcDir.exists()) {
            typeSolver.add(new JavaParserTypeSolver(srcDir));
        }

        if (classpathDir != null && classpathDir.exists() && classpathDir.isDirectory()) {
            File[] jars = classpathDir.listFiles((dir, name) -> name.endsWith(".jar"));
            if (jars != null) {
                for (File jar : jars) {
                    try {
                        typeSolver.add(new JarTypeSolver(jar));
                    } catch (IOException e) {
                        log.warn("Could not load JAR for type solver: {}", jar.getName());
                    }
                }
            }
        }

        ParserConfiguration parserConfiguration = new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
        JavaParser javaParser = new JavaParser(parserConfiguration);

        // 2. Discover all .java files
        List<File> javaFiles = findJavaFiles(projectDir);
        log.info("Found {} Java source files in {}", javaFiles.size(), projectDir.getAbsolutePath());

        List<CompilationUnit> parsedUnits = new ArrayList<>();
        Map<CompilationUnit, String> relativePaths = new HashMap<>();

        for (File file : javaFiles) {
            try {
                javaParser.parse(file).getResult().ifPresent(cu -> {
                    parsedUnits.add(cu);
                    String rel = projectDir.toPath().relativize(file.toPath()).toString().replace('\\', '/');
                    relativePaths.put(cu, rel);
                });
            } catch (Exception e) {
                log.warn("Failed to parse file: {} ({})", file.getName(), e.getMessage());
            }
        }

        // 3. Pass 1: Detect Service Calls
        List<ServiceCall> allCalls = new ArrayList<>();
        WebClientCallVisitor webClientVisitor = new WebClientCallVisitor();
        RestTemplateCallVisitor restTemplateVisitor = new RestTemplateCallVisitor();
        FeignClientVisitor feignVisitor = new FeignClientVisitor();

        for (CompilationUnit cu : parsedUnits) {
            String rel = relativePaths.get(cu);
            cu.accept(webClientVisitor, rel);
            cu.accept(restTemplateVisitor, rel);
            cu.accept(feignVisitor, rel);
        }

        allCalls.addAll(webClientVisitor.getDiscoveredCalls());
        allCalls.addAll(restTemplateVisitor.getDiscoveredCalls());
        allCalls.addAll(feignVisitor.getDiscoveredCalls());

        log.info("Discovered {} HTTP client calls", allCalls.size());

        // 4. Pass 2: Trace Field Usages
        FieldAccessVisitor fieldVisitor = new FieldAccessVisitor(allCalls);
        for (CompilationUnit cu : parsedUnits) {
            String rel = relativePaths.get(cu);
            cu.accept(fieldVisitor, rel);
        }

        List<FieldUsageFinding> findings = new ArrayList<>(fieldVisitor.getFindings());
        Set<String> matchedCallKeys = fieldVisitor.getMatchedCalls();

        // 5. Pass 3: For calls without matched typed fields, create UNKNOWN bucket records
        for (ServiceCall call : allCalls) {
            String key = FieldAccessVisitor.callKey(call);
            if (!matchedCallKeys.contains(key)) {
                String reason = "Object".equalsIgnoreCase(call.getResponseTypeName())
                        ? "Response consumed as Object.class without typed field getters"
                        : "Endpoint invoked via " + call.getClientType() + ", but no specific field getters were detected";
                findings.add(new FieldUsageFinding(
                        call.getProviderService(),
                        call.getEndpointPath(),
                        call.getHttpMethod(),
                        null,
                        EvidenceType.UNKNOWN,
                        call.getSourceFile(),
                        call.getSourceLine(),
                        reason
                ));
            }
        }

        return findings;
    }

    private List<File> findJavaFiles(File dir) {
        try (Stream<Path> stream = Files.walk(dir.toPath())) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toFile)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to walk directory: {}", dir, e);
            return Collections.emptyList();
        }
    }
}
