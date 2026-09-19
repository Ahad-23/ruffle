package com.changedetector.analyzer.ast;

import com.changedetector.analyzer.model.ServiceCall;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class FeignClientVisitor extends VoidVisitorAdapter<String> {

    private final List<ServiceCall> discoveredCalls = new ArrayList<>();

    public List<ServiceCall> getDiscoveredCalls() {
        return discoveredCalls;
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration n, String currentFilePath) {
        super.visit(n, currentFilePath);

        Optional<AnnotationExpr> feignAnn = n.getAnnotationByName("FeignClient");
        if (feignAnn.isEmpty()) return;

        String serviceName = extractServiceName(feignAnn.get());
        String basePath = extractBasePath(feignAnn.get());

        for (MethodDeclaration method : n.getMethods()) {
            analyzeFeignMethod(method, serviceName, basePath, currentFilePath);
        }
    }

    private void analyzeFeignMethod(MethodDeclaration method, String serviceName, String basePath, String filePath) {
        String httpMethod = "GET";
        String path = "";

        if (method.getAnnotationByName("GetMapping").isPresent()) {
            httpMethod = "GET";
            path = extractPath(method.getAnnotationByName("GetMapping").get());
        } else if (method.getAnnotationByName("PostMapping").isPresent()) {
            httpMethod = "POST";
            path = extractPath(method.getAnnotationByName("PostMapping").get());
        } else if (method.getAnnotationByName("PutMapping").isPresent()) {
            httpMethod = "PUT";
            path = extractPath(method.getAnnotationByName("PutMapping").get());
        } else if (method.getAnnotationByName("DeleteMapping").isPresent()) {
            httpMethod = "DELETE";
            path = extractPath(method.getAnnotationByName("DeleteMapping").get());
        } else if (method.getAnnotationByName("RequestMapping").isPresent()) {
            path = extractPath(method.getAnnotationByName("RequestMapping").get());
        } else {
            return;
        }

        String fullPath = basePath + (path.startsWith("/") ? path : "/" + path);
        if (!fullPath.startsWith("/")) fullPath = "/" + fullPath;

        String returnType = method.getTypeAsString();
        int line = method.getBegin().map(p -> p.line).orElse(1);

        discoveredCalls.add(new ServiceCall(
                serviceName,
                fullPath,
                httpMethod,
                returnType,
                filePath,
                line,
                "FeignClient"
        ));
    }

    private String extractServiceName(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            return single.getMemberValue().asStringLiteralExpr().getValue();
        }
        if (ann instanceof NormalAnnotationExpr norm) {
            for (MemberValuePair pair : norm.getPairs()) {
                if ("name".equals(pair.getNameAsString()) || "value".equals(pair.getNameAsString())) {
                    return pair.getValue().toString().replace("\"", "");
                }
            }
        }
        return "unknown-service";
    }

    private String extractBasePath(AnnotationExpr ann) {
        if (ann instanceof NormalAnnotationExpr norm) {
            for (MemberValuePair pair : norm.getPairs()) {
                if ("path".equals(pair.getNameAsString())) {
                    return pair.getValue().toString().replace("\"", "");
                }
            }
        }
        return "";
    }

    private String extractPath(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            return single.getMemberValue().toString().replace("\"", "");
        }
        if (ann instanceof NormalAnnotationExpr norm) {
            for (MemberValuePair pair : norm.getPairs()) {
                if ("value".equals(pair.getNameAsString()) || "path".equals(pair.getNameAsString())) {
                    return pair.getValue().toString().replace("\"", "");
                }
            }
        }
        return "/";
    }
}
