package com.changedetector.analyzer.ast;

import com.changedetector.analyzer.model.EvidenceType;
import com.changedetector.analyzer.model.FieldUsageFinding;
import com.changedetector.analyzer.model.ServiceCall;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.*;

public class FieldAccessVisitor extends VoidVisitorAdapter<String> {

    private final Map<String, ServiceCall> responseTypeToCallMap; // simpleName or fqcn -> ServiceCall
    private final List<FieldUsageFinding> findings = new ArrayList<>();
    private final Set<String> matchedCalls = new HashSet<>();

    public FieldAccessVisitor(List<ServiceCall> serviceCalls) {
        this.responseTypeToCallMap = new HashMap<>();
        for (ServiceCall call : serviceCalls) {
            String type = cleanType(call.getResponseTypeName());
            if (!type.isEmpty() && !"Object".equals(type) && !"void".equals(type)) {
                responseTypeToCallMap.put(type, call);
                // If package qualified, also put simple name
                if (type.contains(".")) {
                    responseTypeToCallMap.put(type.substring(type.lastIndexOf('.') + 1), call);
                }
            }
        }
    }

    public List<FieldUsageFinding> getFindings() {
        return findings;
    }

    public Set<String> getMatchedCalls() {
        return matchedCalls;
    }

    private String cleanType(String type) {
        if (type == null) return "";
        // Strip generics e.g. List<VisitDetails> -> VisitDetails, Flux<VisitDetails> -> VisitDetails
        String clean = type;
        if (clean.contains("<") && clean.contains(">")) {
            clean = clean.substring(clean.indexOf('<') + 1, clean.lastIndexOf('>')).trim();
        }
        return clean.trim();
    }

    @Override
    public void visit(MethodCallExpr n, String currentFilePath) {
        super.visit(n, currentFilePath);

        String methodName = n.getNameAsString();
        int line = n.getBegin().map(p -> p.line).orElse(1);

        // Pattern 1: Typed Getter / Record accessor call
        // e.g. owner.getFirstName(), visit.getPetId(), owner.firstName()
        if (n.getScope().isPresent()) {
            String scopeName = n.getScope().get().toString();

            // Check if method is a getter
            if (isGetter(methodName)) {
                String fieldName = extractFieldNameFromGetter(methodName);
                checkAndRecordTypedAccess(scopeName, fieldName, currentFilePath, line, "Method call: " + scopeName + "." + methodName + "()");
            }

            // Pattern 2: Map or JsonNode get/path
            if (("get".equals(methodName) || "path".equals(methodName)) && n.getArguments().size() == 1) {
                if (n.getArgument(0) instanceof StringLiteralExpr strLit) {
                    String accessedKey = strLit.getValue();
                    recordLikelyAccess(accessedKey, currentFilePath, line, "Map/JsonNode access: " + scopeName + "." + methodName + "(\"" + accessedKey + "\")");
                }
            }
        }
    }

    @Override
    public void visit(MethodReferenceExpr n, String currentFilePath) {
        super.visit(n, currentFilePath);

        // Pattern 3: Method reference e.g. VisitDetails::getPetId or VisitDetails::petId
        String identifier = n.getIdentifier();
        String typeScope = n.getScope().toString();
        int line = n.getBegin().map(p -> p.line).orElse(1);

        String cleanScope = cleanType(typeScope);
        ServiceCall call = responseTypeToCallMap.get(cleanScope);
        if (call != null) {
            String fieldName = isGetter(identifier) ? extractFieldNameFromGetter(identifier) : identifier;
            matchedCalls.add(callKey(call));
            findings.add(new FieldUsageFinding(
                    call.getProviderService(),
                    call.getEndpointPath(),
                    call.getHttpMethod(),
                    fieldName,
                    EvidenceType.CONFIRMED,
                    currentFilePath,
                    line,
                    "Method reference: " + typeScope + "::" + identifier
            ));
        }
    }

    private void checkAndRecordTypedAccess(String scopeVar, String fieldName, String filePath, int line, String detail) {
        // Try matching against any registered response types
        for (Map.Entry<String, ServiceCall> entry : responseTypeToCallMap.entrySet()) {
            ServiceCall call = entry.getValue();
            matchedCalls.add(callKey(call));
            findings.add(new FieldUsageFinding(
                    call.getProviderService(),
                    call.getEndpointPath(),
                    call.getHttpMethod(),
                    fieldName,
                    EvidenceType.CONFIRMED,
                    filePath,
                    line,
                    detail
            ));
            break; // Matched first corresponding service call for this target scope
        }
    }

    private void recordLikelyAccess(String fieldName, String filePath, int line, String detail) {
        // Match against calls that returned Map or generic types
        for (ServiceCall call : responseTypeToCallMap.values()) {
            matchedCalls.add(callKey(call));
            findings.add(new FieldUsageFinding(
                    call.getProviderService(),
                    call.getEndpointPath(),
                    call.getHttpMethod(),
                    fieldName,
                    EvidenceType.LIKELY,
                    filePath,
                    line,
                    detail
            ));
        }
    }

    private boolean isGetter(String name) {
        if (name.startsWith("get") && name.length() > 3 && Character.isUpperCase(name.charAt(3))) {
            return true;
        }
        if (name.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2))) {
            return true;
        }
        return false;
    }

    private String extractFieldNameFromGetter(String getterName) {
        if (getterName.startsWith("get")) {
            String raw = getterName.substring(3);
            return Character.toLowerCase(raw.charAt(0)) + raw.substring(1);
        }
        if (getterName.startsWith("is")) {
            String raw = getterName.substring(2);
            return Character.toLowerCase(raw.charAt(0)) + raw.substring(1);
        }
        return getterName;
    }

    public static String callKey(ServiceCall call) {
        return call.getProviderService() + ":" + call.getEndpointPath() + ":" + call.getSourceFile() + ":" + call.getSourceLine();
    }
}
