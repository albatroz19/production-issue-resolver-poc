package com.tataplay.issueresolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tataplay.issueresolver.model.IncidentRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IncidentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void analyzeEndpoint_returnsDiagnosis() throws Exception {
        IncidentRequest request = IncidentRequest.builder()
                .service("ad-management-service")
                .apiPath("/api/v1/campaign-ch-100/multi-channel/filler-slots")
                .httpStatus(500)
                .errorMessage("NullPointerException")
                .relatedServices(List.of("campaign-management-service"))
                .stackTrace("""
                        java.lang.NullPointerException
                            at com.tataplay.admanagement.utility.RestTemplateUtility.extractErrorMessage(RestTemplateUtility.java:48)
                        """)
                .build();

        String response = mockMvc.perform(post("/api/v1/incidents/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).contains("rootCause");
        assertThat(response).contains("confidence");
    }
}
