package com.tataplay.issueresolver.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tataplay.issueresolver.client.PythonAgentClient;
import com.tataplay.issueresolver.client.PythonAgentException;
import com.tataplay.issueresolver.config.EnvironmentRegistry;
import com.tataplay.issueresolver.config.IssueResolverProperties;
import com.tataplay.issueresolver.index.EnvironmentIndexSnapshot;
import com.tataplay.issueresolver.index.SimpleCodeIndexService;
import com.tataplay.issueresolver.model.ConfidenceLevel;
import com.tataplay.issueresolver.model.DiagnosisResponse;
import com.tataplay.issueresolver.model.EnvironmentProfile;
import com.tataplay.issueresolver.model.IncidentRequest;
import com.tataplay.issueresolver.service.DiagnosisMerger;
import com.tataplay.issueresolver.tool.ApiCallPathTracer;
import com.tataplay.issueresolver.tool.DownstreamErrorParser;
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
class IssueResolverOrchestrationTest {

    @Mock
    private SimpleCodeIndexService codeIndexService;

    @Mock
    private PythonAgentClient pythonAgentClient;

    @Mock
    private ApiCallPathTracer apiCallPathTracer;

    private IssueResolverAgent agent;

    @BeforeEach
    void setUp() {
        IssueResolverProperties properties = new IssueResolverProperties();
        properties.setReposRoot("/Users/amitasharda/Desktop/Segmentation");
        properties.setMaxFilesPerRequest(3);
        properties.getPythonAgent().setEnabled(true);

        EnvironmentProfile devProfile = new EnvironmentProfile();
        devProfile.setGitBranches(Map.of("ad-management-service", "develop"));
        EnvironmentRegistry environmentRegistry = new EnvironmentRegistry(Map.of("dev", devProfile));

        ServiceDependencyTool serviceDependencyTool = new ServiceDependencyTool();
        ServiceDependencyTool.ServiceDefinition ams = new ServiceDependencyTool.ServiceDefinition();
        ams.setDependencies(List.of("campaign-management-service"));
        serviceDependencyTool.setServices(Map.of("ad-management-service", ams));

        when(codeIndexService.prepareForEnvironment(any())).thenReturn(EnvironmentIndexSnapshot.builder()
                .environment("dev")
                .indexedBranch("develop")
                .indexedCommit("abc123")
                .classNameIndex(Map.of())
                .allFiles(List.of())
                .build());

        agent = new IssueResolverAgent(
                properties,
                environmentRegistry,
                new ParseStackTraceTool(),
                new DownstreamErrorParser(new ObjectMapper()),
                codeIndexService,
                serviceDependencyTool,
                apiCallPathTracer,
                new ObjectMapper(),
                pythonAgentClient,
                new DiagnosisMerger(),
                null);
    }

    @Test
    void analyze_withPythonOrchestration_mergesJavaAndPythonDiagnosis() {
        when(pythonAgentClient.analyze(any(IncidentRequest.class))).thenReturn(
                DiagnosisResponse.builder()
                        .rootCause("Python LLM root cause")
                        .confidence(ConfidenceLevel.HIGH)
                        .suggestedFix("Python LLM fix")
                        .reasoningSteps(List.of("python reasoning"))
                        .build());

        IncidentRequest request = IncidentRequest.builder()
                .service("ad-management-service")
                .stackTrace("""
                        java.lang.NullPointerException
                            at com.tataplay.admanagement.utility.RestTemplateUtility.extractErrorMessage(RestTemplateUtility.java:48)
                        """)
                .build();

        DiagnosisResponse response = agent.analyze(request);

        verify(pythonAgentClient).analyze(request);
        assertThat(response.getRootCause()).isEqualTo("Python LLM root cause");
        assertThat(response.getReasoningSteps()).anyMatch(step -> step.startsWith("[java]"));
        assertThat(response.getReasoningSteps()).anyMatch(step -> step.startsWith("[python]"));
    }

    @Test
    void analyze_whenPythonUnavailable_failsFast() {
        when(pythonAgentClient.analyze(any(IncidentRequest.class)))
                .thenThrow(new PythonAgentException("Python agent unavailable"));

        IncidentRequest request = IncidentRequest.builder()
                .service("ad-management-service")
                .stackTrace("java.lang.NullPointerException")
                .build();

        assertThatThrownBy(() -> agent.analyze(request))
                .isInstanceOf(PythonAgentException.class);
    }
}
