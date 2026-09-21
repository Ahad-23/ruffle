package com.changedetector.registry.api;

import com.changedetector.registry.diff.SchemaDiffEngine;
import com.changedetector.registry.entity.Service;
import com.changedetector.registry.ingestion.OpenApiIngestionService;
import com.changedetector.registry.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ServiceController.class)
public class ServiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ServiceRepository serviceRepository;

    @MockBean
    private SpecVersionRepository specVersionRepository;

    @MockBean
    private EndpointRepository endpointRepository;

    @MockBean
    private SchemaFieldRepository schemaFieldRepository;

    @MockBean
    private OpenApiIngestionService ingestionService;

    @MockBean
    private SchemaDiffEngine diffEngine;

    @MockBean
    private SchemaChangeRepository schemaChangeRepository;

    @Test
    public void testCreateServiceReturnsBadRequestWhenNameIsBlank() throws Exception {
        ServiceController.CreateServiceRequest invalidReq = new ServiceController.CreateServiceRequest("", "http://test:8080");

        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testCreateServiceReturnsConflictWhenServiceAlreadyExists() throws Exception {
        ServiceController.CreateServiceRequest req = new ServiceController.CreateServiceRequest("existing-service", "http://test:8080");
        when(serviceRepository.findByName("existing-service")).thenReturn(Optional.of(new Service("existing-service", "http://test:8080")));

        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    @Test
    public void testCreateServiceReturnsCreatedWhenValid() throws Exception {
        ServiceController.CreateServiceRequest req = new ServiceController.CreateServiceRequest("new-service", "http://new-service:8080");
        Service saved = Service.builder().id(5L).name("new-service").baseUrl("http://new-service:8080").build();

        when(serviceRepository.findByName("new-service")).thenReturn(Optional.empty());
        when(serviceRepository.save(any(Service.class))).thenReturn(saved);

        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5L))
                .andExpect(jsonPath("$.name").value("new-service"));
    }

    @Test
    public void testGetServiceReturnsNotFoundWhenNonExistent() throws Exception {
        when(serviceRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/services/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void testGetServiceReturnsOkWhenFound() throws Exception {
        Service service = Service.builder().id(1L).name("sample-service").baseUrl("http://sample:8080").build();
        when(serviceRepository.findById(1L)).thenReturn(Optional.of(service));

        mockMvc.perform(get("/api/services/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.name").value("sample-service"));
    }
}
