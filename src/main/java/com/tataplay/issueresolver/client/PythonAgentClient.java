package com.tataplay.issueresolver.client;

import com.tataplay.issueresolver.config.IssueResolverProperties;
import com.tataplay.issueresolver.config.PythonAgentProperties;
import com.tataplay.issueresolver.model.DiagnosisResponse;
import com.tataplay.issueresolver.model.IncidentRequest;
import java.time.Duration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class PythonAgentClient {

    private static final String ORCHESTRATOR_HEADER = "X-ORCHESTRATOR";
    private static final String ORCHESTRATOR_VALUE = "java-poc";
    private static final String API_KEY_HEADER = "X-POC-API-KEY";

    private final IssueResolverProperties properties;
    private final RestTemplate restTemplate;

    public PythonAgentClient(IssueResolverProperties properties, RestTemplateBuilder restTemplateBuilder) {
        this.properties = properties;
        PythonAgentProperties pythonAgent = properties.getPythonAgent();
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofMillis(pythonAgent.getConnectTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(pythonAgent.getReadTimeoutMs()))
                .build();
    }

    public DiagnosisResponse analyze(IncidentRequest request) {
        PythonAgentProperties pythonAgent = properties.getPythonAgent();
        String url = pythonAgent.getBaseUrl() + pythonAgent.getPath();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(ORCHESTRATOR_HEADER, ORCHESTRATOR_VALUE);
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            headers.set(API_KEY_HEADER, properties.getApiKey());
        }

        try {
            ResponseEntity<DiagnosisResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    DiagnosisResponse.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new PythonAgentException("Python agent returned non-success status: " + response.getStatusCode());
            }
            return response.getBody();
        } catch (RestClientException ex) {
            throw new PythonAgentException("Python agent unavailable", ex);
        }
    }
}
