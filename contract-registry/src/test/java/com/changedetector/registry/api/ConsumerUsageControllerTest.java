package com.changedetector.registry.api;

import com.changedetector.registry.entity.ConsumerUsage;
import com.changedetector.registry.repository.ConsumerUsageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ConsumerUsageController.class)
public class ConsumerUsageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ConsumerUsageRepository consumerUsageRepository;

    @Test
    public void testRegisterUsageReturnsBadRequestWhenMissingConsumerOrFindings() throws Exception {
        // Missing findings (empty list)
        ConsumerUsageController.RegisterUsageRequest req1 = new ConsumerUsageController.RegisterUsageRequest(
                "gateway-service", "scan-1", false, List.of()
        );

        mockMvc.perform(post("/api/consumer-usage/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isBadRequest());

        // Blank consumer service
        ConsumerUsageController.UsageFindingDto finding = new ConsumerUsageController.UsageFindingDto(
                "provider", "/path", "GET", "field", "CONFIRMED", "File.java", 10, "detail"
        );
        ConsumerUsageController.RegisterUsageRequest req2 = new ConsumerUsageController.RegisterUsageRequest(
                "", "scan-1", false, List.of(finding)
        );

        mockMvc.perform(post("/api/consumer-usage/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testRegisterUsageSucceedsWhenValid() throws Exception {
        ConsumerUsageController.UsageFindingDto finding = new ConsumerUsageController.UsageFindingDto(
                "customers-service", "/owners/{id}", "GET", "firstName", "CONFIRMED", "Gateway.java", 35, "getter"
        );
        ConsumerUsageController.RegisterUsageRequest req = new ConsumerUsageController.RegisterUsageRequest(
                "api-gateway", "scan-100", true, List.of(finding)
        );

        ConsumerUsage savedEntity = ConsumerUsage.builder()
                .id(1L)
                .consumerService("api-gateway")
                .providerService("customers-service")
                .build();
        when(consumerUsageRepository.saveAll(anyList())).thenReturn(List.of(savedEntity));

        mockMvc.perform(post("/api/consumer-usage/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumerService").value("api-gateway"))
                .andExpect(jsonPath("$.registeredCount").value(1))
                .andExpect(jsonPath("$.scanId").value("scan-100"));
    }

    @Test
    public void testListUsagesFiltersByProviderService() throws Exception {
        ConsumerUsage usage = ConsumerUsage.builder()
                .id(1L)
                .consumerService("api-gateway")
                .providerService("customers-service")
                .endpointPath("/owners/{id}")
                .build();
        when(consumerUsageRepository.findByProviderService("customers-service")).thenReturn(List.of(usage));

        mockMvc.perform(get("/api/consumer-usage?providerService=customers-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].providerService").value("customers-service"));
    }
}
