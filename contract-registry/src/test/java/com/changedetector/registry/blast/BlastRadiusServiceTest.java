package com.changedetector.registry.blast;

import com.changedetector.registry.diff.SchemaDiffEngine;
import com.changedetector.registry.entity.*;
import com.changedetector.registry.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class BlastRadiusServiceTest {

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private SpecVersionRepository specVersionRepository;

    @Mock
    private SchemaChangeRepository schemaChangeRepository;

    @Mock
    private ConsumerUsageRepository consumerUsageRepository;

    @Mock
    private BlastReportRepository blastReportRepository;

    @Mock
    private SchemaDiffEngine schemaDiffEngine;

    private ObjectMapper objectMapper;
    private BlastRadiusService blastRadiusService;

    @BeforeEach
    public void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        blastRadiusService = new BlastRadiusService(
                serviceRepository,
                specVersionRepository,
                schemaChangeRepository,
                consumerUsageRepository,
                blastReportRepository,
                schemaDiffEngine,
                objectMapper
        );
    }

    @Test
    public void testCalculateBlastRadiusWithCategorizedImpacts() {
        Service provider = Service.builder().id(1L).name("orders-service").build();
        SpecVersion oldVersion = SpecVersion.builder().id(10L).versionTag("v1.0.0").build();
        SpecVersion newVersion = SpecVersion.builder().id(20L).versionTag("v2.0.0").build();

        when(serviceRepository.findById(1L)).thenReturn(Optional.of(provider));
        when(specVersionRepository.findById(10L)).thenReturn(Optional.of(oldVersion));
        when(specVersionRepository.findById(20L)).thenReturn(Optional.of(newVersion));

        // Schema changes
        SchemaChange removedFieldChange = SchemaChange.builder()
                .id(101L)
                .changeType("FIELD_REMOVED")
                .endpointPath("/orders/{orderId}")
                .httpMethod("GET")
                .fieldPath("totalAmount")
                .severity("BREAKING")
                .build();

        SchemaChange typeChangedChange = SchemaChange.builder()
                .id(102L)
                .changeType("TYPE_CHANGED")
                .endpointPath("/orders/{orderId}")
                .httpMethod("GET")
                .fieldPath("status")
                .oldValue("string")
                .newValue("integer")
                .severity("BREAKING")
                .build();

        SchemaChange infoChange = SchemaChange.builder()
                .id(103L)
                .changeType("OPTIONAL_FIELD_ADDED")
                .endpointPath("/orders/{orderId}")
                .httpMethod("GET")
                .fieldPath("discountCode")
                .severity("INFO")
                .build();

        when(schemaChangeRepository.findByOldSpecVersionIdAndNewSpecVersionId(10L, 20L))
                .thenReturn(List.of(removedFieldChange, typeChangedChange, infoChange));

        // Consumer usages
        ConsumerUsage confirmedUsage = ConsumerUsage.builder()
                .consumerService("payment-service")
                .providerService("orders-service")
                .endpointPath("/orders/{id}") // Notice path variable difference {id} vs {orderId}
                .fieldPath("totalAmount")
                .evidenceType("CONFIRMED")
                .sourceFile("PaymentProcessor.java")
                .sourceLine(45)
                .evidenceDetail("order.getTotalAmount()")
                .build();

        ConsumerUsage likelyUsage = ConsumerUsage.builder()
                .consumerService("analytics-service")
                .providerService("orders-service")
                .endpointPath("/orders/{orderId}?includeDetails=true") // Notice query param
                .fieldPath("status")
                .evidenceType("LIKELY")
                .sourceFile("AnalyticsWorker.java")
                .sourceLine(88)
                .evidenceDetail("json.get(\"status\")")
                .build();

        ConsumerUsage unknownUsage = ConsumerUsage.builder()
                .consumerService("legacy-client")
                .providerService("orders-service")
                .endpointPath("/orders/{orderId}")
                .fieldPath(null) // Unresolvable field path -> UNKNOWN
                .evidenceType("UNKNOWN")
                .sourceFile("LegacyGateway.java")
                .sourceLine(12)
                .evidenceDetail("Raw RestTemplate call")
                .build();

        when(consumerUsageRepository.findByProviderService("orders-service"))
                .thenReturn(List.of(confirmedUsage, likelyUsage, unknownUsage));

        // Execute
        BlastRadiusService.BlastRadiusReport report = blastRadiusService.calculateBlastRadius(1L, 10L, 20L);

        // Verify counts
        assertNotNull(report);
        assertEquals("orders-service", report.getProviderService());
        assertEquals(1, report.getConfirmedCount());
        assertEquals(1, report.getLikelyCount());
        assertEquals(2, report.getUnknownCount()); // legacy-client impacted by both breaking changes
        assertFalse(report.getSafeChanges().isEmpty());

        // Verify report entity was saved
        verify(blastReportRepository, times(1)).save(any(BlastReport.class));

        // Verify markdown report
        String markdown = blastRadiusService.formatMarkdownReport(report);
        assertTrue(markdown.contains("payment-service"));
        assertTrue(markdown.contains("PaymentProcessor.java:45"));
        assertTrue(markdown.contains("analytics-service"));
        assertTrue(markdown.contains("legacy-client"));
        assertTrue(markdown.contains("totalAmount"));
    }

    @Test
    public void testFormatMarkdownReportWhenNoBreakingChanges() {
        BlastRadiusService.BlastRadiusReport report = BlastRadiusService.BlastRadiusReport.builder()
                .providerService("notifications-service")
                .oldVersionTag("v1.0.0")
                .newVersionTag("v1.1.0")
                .confirmedCount(0)
                .likelyCount(0)
                .unknownCount(0)
                .confirmedImpacts(List.of())
                .likelyImpacts(List.of())
                .unknownImpacts(List.of())
                .safeChanges(List.of(
                        new BlastRadiusService.SchemaChangeSummary("OPTIONAL_FIELD_ADDED", "/notifications", "GET", "badge", "INFO")
                ))
                .build();

        String markdown = blastRadiusService.formatMarkdownReport(report);
        assertTrue(markdown.contains("No internal consumer services are broken by this change!"));
        assertTrue(markdown.contains("View Unaffected / Safe Changes (1)"));
    }
}
