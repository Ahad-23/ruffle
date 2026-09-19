package com.changedetector.analyzer.ast;

import com.changedetector.analyzer.model.ServiceCall;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class WebClientCallVisitor extends VoidVisitorAdapter<String> {

    private final List<ServiceCall> discoveredCalls = new ArrayList<>();

    public List<ServiceCall> getDiscoveredCalls() {
        return discoveredCalls;
    }

    @Override
    public void visit(MethodCallExpr n, String currentFilePath) {
        super.visit(n, currentFilePath);

        String methodName = n.getNameAsString();
        // Check for WebClient terminal methods: bodyToMono, bodyToFlux, toBodilessEntity, etc.
        if ("bodyToMono".equals(methodName) || "bodyToFlux".equals(methodName) || "body".equals(methodName)) {
            analyzeWebClientChain(n, currentFilePath);
        }
    }

    private void analyzeWebClientChain(MethodCallExpr terminalCall, String filePath) {
        String responseType = "java.lang.Object";
        if (terminalCall.getArguments().isNonEmpty()) {
            Expression firstArg = terminalCall.getArgument(0);
            if (firstArg instanceof ClassExpr classExpr) {
                responseType = classExpr.getTypeAsString();
            } else {
                responseType = firstArg.toString();
            }
        }

        String uriString = null;
        String httpMethod = "GET";

        // Traverse backward through the fluent call chain
        Optional<Expression> currentScope = terminalCall.getScope();
        while (currentScope.isPresent() && currentScope.get() instanceof MethodCallExpr call) {
            String name = call.getNameAsString();

            if ("uri".equals(name) && call.getArguments().isNonEmpty()) {
                Expression uriArg = call.getArgument(0);
                if (uriArg instanceof StringLiteralExpr strLit) {
                    uriString = strLit.getValue();
                } else {
                    uriString = uriArg.toString().replace("\"", "");
                }
            } else if ("get".equalsIgnoreCase(name)) {
                httpMethod = "GET";
            } else if ("post".equalsIgnoreCase(name)) {
                httpMethod = "POST";
            } else if ("put".equalsIgnoreCase(name)) {
                httpMethod = "PUT";
            } else if ("delete".equalsIgnoreCase(name)) {
                httpMethod = "DELETE";
            } else if ("patch".equalsIgnoreCase(name)) {
                httpMethod = "PATCH";
            } else if ("method".equalsIgnoreCase(name) && call.getArguments().isNonEmpty()) {
                httpMethod = call.getArgument(0).toString().replace("HttpMethod.", "").replace("\"", "");
            }

            currentScope = call.getScope();
        }

        if (uriString != null) {
            ParsedUri parsed = parseServiceAndPath(uriString);
            int line = terminalCall.getBegin().map(p -> p.line).orElse(1);

            discoveredCalls.add(new ServiceCall(
                    parsed.serviceName,
                    parsed.endpointPath,
                    httpMethod,
                    responseType,
                    filePath,
                    line,
                    "WebClient"
            ));
        }
    }

    public static class ParsedUri {
        public final String serviceName;
        public final String endpointPath;

        public ParsedUri(String serviceName, String endpointPath) {
            this.serviceName = serviceName;
            this.endpointPath = endpointPath;
        }
    }

    public static ParsedUri parseServiceAndPath(String uri) {
        String service = "unknown-service";
        String path = uri;

        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            try {
                // Handle Spring Cloud load balanced URIs e.g. "http://visits-service/pets/visits?petId={petId}"
                int schemeEnd = uri.indexOf("://") + 3;
                int pathStart = uri.indexOf('/', schemeEnd);
                if (pathStart != -1) {
                    service = uri.substring(schemeEnd, pathStart);
                    path = uri.substring(pathStart);
                } else {
                    service = uri.substring(schemeEnd);
                    path = "/";
                }
            } catch (Exception e) {
                // fallback
            }
        } else if (uri.startsWith("/")) {
            // Relative URI
            path = uri;
        }

        // Clean query params: /pets/visits?petId={petId} -> /pets/visits
        if (path.contains("?")) {
            path = path.substring(0, path.indexOf('?'));
        }

        return new ParsedUri(service, path);
    }
}
