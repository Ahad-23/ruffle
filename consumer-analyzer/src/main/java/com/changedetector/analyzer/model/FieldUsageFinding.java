package com.changedetector.analyzer.model;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FieldUsageFinding {
    private String providerService;
    private String endpointPath;
    private String httpMethod;
    private String fieldPath;
    private EvidenceType evidenceType;
    private String sourceFile;
    private int sourceLine;
    private String evidenceDetail;

    @Override
    public String toString() {
        return "[" + evidenceType + "] " + providerService + " " + (httpMethod != null ? httpMethod + " " : "")
                + endpointPath + (fieldPath != null ? " (field: " + fieldPath + ")" : "")
                + " at " + sourceFile + ":" + sourceLine + " - " + evidenceDetail;
    }
}
