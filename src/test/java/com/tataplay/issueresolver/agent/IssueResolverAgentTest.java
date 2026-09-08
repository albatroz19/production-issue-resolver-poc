package com.tataplay.issueresolver.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tataplay.issueresolver.client.PythonAgentClient;
import com.tataplay.issueresolver.config.IssueResolverProperties;
import com.tataplay.issueresolver.index.SimpleCodeIndexService;
import com.tataplay.issueresolver.model.ConfidenceLevel;
import com.tataplay.issueresolver.model.DiagnosisResponse;
import com.tataplay.issueresolver.model.IncidentRequest;
import com.tataplay.issueresolver.service.DiagnosisMerger;
import com.tataplay.issueresolver.tool.ParseStackTraceTool;
import com.tataplay.issueresolver.tool.ServiceDependencyTool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IssueResolverAgentTest {

    @Mock
    private SimpleCodeIndexService codeIndexService;

    @Mock
    private PythonAgentClient pythonAgentClient;

    private IssueResolverAgent agent;

    @BeforeEach
    void setUp() {
        IssueResolverProperties properties = new IssueResolverProperties();
        properties.setReposRoot("/Users/amitasharda/Desktop/Segmentation");
        properties.setRepos(List.of(
                repo("campaign-management-service", "campaign-management-service"),
                repo("ad-management-service", "ad-management-service")));
        properties.setMaxFilesPerRequest(3);
        properties.getPythonAgent().setEnabled(false);

        ServiceDependencyTool serviceDependencyTool = new ServiceDependencyTool();
        ServiceDependencyTool.ServiceDefinition ams = new ServiceDependencyTool.ServiceDefinition();
        ams.setDependencies(List.of("campaign-management-service"));
        ServiceDependencyTool.ApiMapping mapping = new ServiceDependencyTool.ApiMapping();
        mapping.setTargetService("campaign-management-service");
        mapping.setTargetPath("/api/v1/ch-100/multi-channel/filler-slots");
        ams.setApiMappings(Map.of(
                "/api/v1/campaign-ch-100/multi-channel/filler-slots", mapping));
        serviceDependencyTool.setServices(Map.of("ad-management-service", ams));

        agent = new IssueResolverAgent(
                properties,
                new ParseStackTraceTool(),
                codeIndexService,
                serviceDependencyTool,
                new ObjectMapper(),
                pythonAgentClient,
                new DiagnosisMerger(),
                null);
    }

    @Test
    void analyze_goldenRestTemplateUtilityNpeCase_usesRuleBasedFallback() {
        when(codeIndexService.searchCodebase(anyString(), anyString())).thenReturn(List.of());
        when(codeIndexService.findExceptionHandlers(anyString())).thenReturn(List.of());

        IncidentRequest request = IncidentRequest.builder()
                .service("ad-management-service")
                .environment("dev")
                .apiPath("/api/v1/campaign-ch-100/multi-channel/filler-slots")
                .httpStatus(500)
                .errorMessage("NullPointerException")
                .relatedServices(List.of("campaign-management-service"))
                .stackTrace("""
                        java.lang.NullPointerException: Cannot invoke "com.fasterxml.jackson.databind.JsonNode.asText()" because the return value is null
                            at com.tataplay.admanagement.utility.RestTemplateUtility.extractErrorMessage(RestTemplateUtility.java:48)
                            at com.tataplay.admanagement.utility.RestTemplateUtility.handleError(RestTemplateUtility.java:32)
                        """)
                .recentLogs("CMS responded with HTTP 400 and body {\"code\":\"VALIDATION_ERROR\"}")
                .build();

        DiagnosisResponse response = agent.analyze(request);

        assertThat(response.getConfidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(response.getRootCause()).containsIgnoringCase("message");
        assertThat(response.getSuggestedFix()).containsIgnoringCase("RestTemplateUtility");
        assertThat(response.getReasoningSteps())
                .anyMatch(step -> step.toLowerCase().contains("resttemplateutility"));
    }

    private static IssueResolverProperties.RepoConfig repo(String name, String path) {
        IssueResolverProperties.RepoConfig config = new IssueResolverProperties.RepoConfig();
        config.setName(name);
        config.setPath(path);
        return config;
    }
}
