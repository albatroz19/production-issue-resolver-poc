package com.tataplay.issueresolver.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tataplay.issueresolver.client.PythonAgentClient;
import com.tataplay.issueresolver.config.EnvironmentRegistry;
import com.tataplay.issueresolver.config.IssueResolverProperties;
import com.tataplay.issueresolver.index.EnvironmentIndexSnapshot;
import com.tataplay.issueresolver.index.IndexedFile;
import com.tataplay.issueresolver.index.SimpleCodeIndexService;
import com.tataplay.issueresolver.model.CallPathRole;
import com.tataplay.issueresolver.model.CallPathStep;
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
class IssueResolverAgentTest {

    @Mock
    private SimpleCodeIndexService codeIndexService;

    @Mock
    private PythonAgentClient pythonAgentClient;

    @Mock
    private ApiCallPathTracer apiCallPathTracer;

    private IssueResolverAgent agent;
    private EnvironmentIndexSnapshot indexSnapshot;

    @BeforeEach
    void setUp() {
        IssueResolverProperties properties = new IssueResolverProperties();
        properties.setReposRoot("/Users/amitasharda/Desktop/Segmentation");
        properties.setRepos(List.of(
                repo("campaign-management-service", "campaign-management-service"),
                repo("ad-management-service", "ad-management-service")));
        properties.setMaxFilesPerRequest(3);
        properties.getPythonAgent().setEnabled(false);

        EnvironmentProfile devProfile = new EnvironmentProfile();
        devProfile.setGitBranches(Map.of(
                "ad-management-service", "develop",
                "campaign-management-service", "develop"));
        EnvironmentRegistry environmentRegistry = new EnvironmentRegistry(Map.of("dev", devProfile));

        ServiceDependencyTool serviceDependencyTool = new ServiceDependencyTool();
        ServiceDependencyTool.ServiceDefinition ams = new ServiceDependencyTool.ServiceDefinition();
        ams.setDependencies(List.of("campaign-management-service"));
        ServiceDependencyTool.ApiMapping fillerMapping = new ServiceDependencyTool.ApiMapping();
        fillerMapping.setTargetService("campaign-management-service");
        fillerMapping.setTargetPath("/api/v1/ch-100/multi-channel/filler-slots");
        ServiceDependencyTool.ApiMapping ch100Mapping = new ServiceDependencyTool.ApiMapping();
        ch100Mapping.setTargetService("campaign-management-service");
        ch100Mapping.setTargetPath("/api/v1/campaign-management/ch-100/get");
        ams.setApiMappings(Map.of(
                "/api/v1/campaign-ch-100/multi-channel/filler-slots", fillerMapping,
                "/api/v1/campaign-ch-100", ch100Mapping));
        serviceDependencyTool.setServices(Map.of("ad-management-service", ams));

        indexSnapshot = EnvironmentIndexSnapshot.builder()
                .environment("dev")
                .indexedBranch("ad-management-service:develop")
                .indexedCommit("abc123")
                .classNameIndex(Map.of(
                        "CampaignChHundredServiceImpl", indexedFile("ad-management-service", "CampaignChHundredServiceImpl.java"),
                        "CampaignChHundredController", indexedFile("ad-management-service", "CampaignChHundredController.java"),
                        "ChHundredCampaignController", indexedFile("campaign-management-service", "ChHundredCampaignController.java")))
                .allFiles(List.of())
                .build();

        when(codeIndexService.prepareForEnvironment(any())).thenReturn(indexSnapshot);

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
    void analyze_goldenRestTemplateUtilityNpeCase_usesRuleBasedFallback() {
        when(codeIndexService.searchCodebase(any(), eq("extractErrorMessage"), eq("ad-management-service")))
                .thenReturn(List.of());
        when(codeIndexService.findExceptionHandlers(any(), eq("campaign-management-service")))
                .thenReturn(List.of());

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
        assertThat(response.getEnvironment()).isEqualTo("dev");
        assertThat(response.getIndexedBranch()).contains("develop");
        assertThat(response.getReasoningSteps())
                .anyMatch(step -> step.toLowerCase().contains("resttemplateutility"));
    }

    @Test
    void analyze_campaignCh100CmsProxyFailure_matchesHighConfidenceRule() {
        when(apiCallPathTracer.trace(any(), any(), any())).thenReturn(List.of(
                CallPathStep.builder()
                        .repo("ad-management-service")
                        .className("CampaignChHundredController")
                        .methodName("getCampaign")
                        .path("CampaignChHundredController.java")
                        .line(55)
                        .role(CallPathRole.ENTRY_CONTROLLER)
                        .build(),
                CallPathStep.builder()
                        .repo("ad-management-service")
                        .className("CampaignChHundredServiceImpl")
                        .methodName("getCampaign")
                        .path("CampaignChHundredServiceImpl.java")
                        .line(535)
                        .role(CallPathRole.PROXY_CALL)
                        .build(),
                CallPathStep.builder()
                        .repo("campaign-management-service")
                        .className("ChHundredCampaignController")
                        .methodName("getCampaign")
                        .path("ChHundredCampaignController.java")
                        .line(72)
                        .role(CallPathRole.DOWNSTREAM_CONTROLLER)
                        .build(),
                CallPathStep.builder()
                        .repo("campaign-management-service")
                        .className("ChHundredCampaignServiceImpl")
                        .methodName("getCampaign")
                        .path("ChHundredCampaignServiceImpl.java")
                        .line(8)
                        .role(CallPathRole.DOWNSTREAM_SERVICE)
                        .build()));

        IncidentRequest request = IncidentRequest.builder()
                .service("ad-management-service")
                .environment("dev")
                .apiPath("/api/v1/campaign-ch-100")
                .httpStatus(500)
                .errorMessage("BusinessValidationException")
                .relatedServices(List.of("campaign-management-service"))
                .stackTrace("""
                        org.springframework.web.client.HttpServerErrorException$InternalServerError: 500 : "{"code":500,"path":"/campaign-management-service/api/v1/campaign-management/ch-100/get","timestamp":"1789453997041"}"
                            at org.springframework.web.client.HttpServerErrorException.create(HttpServerErrorException.java:102)
                        """)
                .build();

        DiagnosisResponse response = agent.analyze(request);

        assertThat(response.getConfidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(response.getRootCause()).containsIgnoringCase("CMS returned HTTP 500");
        assertThat(response.getDownstreamPath()).contains("ch-100/get");
        assertThat(response.getDownstreamService()).isEqualTo("campaign-management-service");
        assertThat(response.getAffectedFiles()).isNotEmpty();
        assertThat(response.getCallPath()).hasSize(4);
        assertThat(response.getCallPath())
                .anyMatch(step -> step.getClassName().equals("ChHundredCampaignController")
                        && step.getLine() == 72);
        assertThat(response.getRootCause()).contains("ChHundredCampaignController.getCampaign");
    }

    private static IssueResolverProperties.RepoConfig repo(String name, String path) {
        IssueResolverProperties.RepoConfig config = new IssueResolverProperties.RepoConfig();
        config.setName(name);
        config.setPath(path);
        return config;
    }

    private static IndexedFile indexedFile(String repo, String path) {
        return new IndexedFile(repo, "/tmp/" + path, path, path.replace(".java", ""), "com.tataplay.test");
    }
}
