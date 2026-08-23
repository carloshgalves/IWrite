package com.iwrite.health.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.health.service.DatabaseHealthService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PingControllerTest {

    private final DatabaseHealthService databaseHealthService = mock(DatabaseHealthService.class);

    private final MockMvc mockMvc =
            MockMvcBuilders.standaloneSetup(new PingController(databaseHealthService)).build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void pingReturnsOkWhenDatabaseIsHealthy() throws Exception {
        when(databaseHealthService.isHealthy()).thenReturn(true);

        String responseBody = mockMvc.perform(get("/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.service").value("iwrite"))
                .andExpect(jsonPath("$.database").value("up"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode response = objectMapper.readTree(responseBody);

        assertDoesNotThrow(
                () -> Instant.parse(response.get("timestamp").asText())
        );
    }

    @Test
    void pingReturnsServiceUnavailableWhenDatabaseIsUnhealthy() throws Exception {
        when(databaseHealthService.isHealthy()).thenReturn(false);

        String responseBody = mockMvc.perform(get("/ping"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("unavailable"))
                .andExpect(jsonPath("$.service").value("iwrite"))
                .andExpect(jsonPath("$.database").value("down"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode response = objectMapper.readTree(responseBody);

        assertDoesNotThrow(
                () -> Instant.parse(response.get("timestamp").asText())
        );
    }
}
