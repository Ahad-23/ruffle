package com.changedetector.analyzer.model;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceCall {
    private String providerService;
    private String endpointPath;
    private String httpMethod;
    private String responseTypeName; // e.g. "VisitDetails" or "java.util.Map" or "java.lang.Object"
    private String sourceFile;
    private int sourceLine;
    private String clientType; // "WebClient", "RestTemplate", "FeignClient"
}
