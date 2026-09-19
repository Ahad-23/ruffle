package com.changedetector.analyzer.ast;

import com.changedetector.analyzer.model.ServiceCall;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.ArrayList;
import java.util.List;

public class RestTemplateCallVisitor extends VoidVisitorAdapter<String> {

    private final List<ServiceCall> discoveredCalls = new ArrayList<>();

    public List<ServiceCall> getDiscoveredCalls() {
        return discoveredCalls;
    }

    @Override
    public void visit(MethodCallExpr n, String currentFilePath) {
        super.visit(n, currentFilePath);

        String name = n.getNameAsString();
        if (isRestTemplateMethod(name) && n.getArguments().isNonEmpty()) {
            analyzeRestTemplateCall(n, name, currentFilePath);
        }
    }

    private boolean isRestTemplateMethod(String name) {
        return "getForObject".equals(name) || "getForEntity".equals(name)
                || "postForObject".equals(name) || "postForEntity".equals(name)
                || "put".equals(name) || "delete".equals(name)
                || "exchange".equals(name);
    }

    private void analyzeRestTemplateCall(MethodCallExpr call, String methodName, String filePath) {
        String uriString = null;
        String responseType = "java.lang.Object";
        String httpMethod = "GET";

        if (methodName.startsWith("get")) {
            httpMethod = "GET";
        } else if (methodName.startsWith("post")) {
            httpMethod = "POST";
        } else if (methodName.startsWith("put")) {
            httpMethod = "PUT";
        } else if (methodName.startsWith("delete")) {
            httpMethod = "DELETE";
        }

        // First argument is usually the URI (or second in some exchange overloads)
        Expression firstArg = call.getArgument(0);
        if (firstArg instanceof StringLiteralExpr str) {
            uriString = str.getValue();
        } else {
            uriString = firstArg.toString().replace("\"", "");
        }

        // Look for ClassExpr among remaining arguments for response type
        for (int i = 1; i < call.getArguments().size(); i++) {
            Expression arg = call.getArgument(i);
            if (arg instanceof ClassExpr classExpr) {
                responseType = classExpr.getTypeAsString();
                break;
            }
            if (arg.toString().contains("HttpMethod.")) {
                httpMethod = arg.toString().replace("HttpMethod.", "").replace("\"", "");
            }
        }

        if (uriString != null) {
            WebClientCallVisitor.ParsedUri parsed = WebClientCallVisitor.parseServiceAndPath(uriString);
            int line = call.getBegin().map(p -> p.line).orElse(1);

            discoveredCalls.add(new ServiceCall(
                    parsed.serviceName,
                    parsed.endpointPath,
                    httpMethod,
                    responseType,
                    filePath,
                    line,
                    "RestTemplate"
            ));
        }
    }
}
