package com.tataplay.issueresolver.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.tataplay.issueresolver.index.EnvironmentIndexSnapshot;
import com.tataplay.issueresolver.index.SimpleCodeIndexService;
import com.tataplay.issueresolver.model.CallPathRole;
import com.tataplay.issueresolver.model.CallPathStep;
import com.tataplay.issueresolver.model.IncidentRequest;
import com.tataplay.issueresolver.testsupport.FixtureIndexSupport;
import com.tataplay.issueresolver.tool.DownstreamErrorParser.DownstreamError;
import com.tataplay.issueresolver.tool.ServiceDependencyTool.ApiMapping;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApiCallPathTracerTest {

    @Mock
    private ServiceDependencyTool serviceDependencyTool;

    @Mock
    private SimpleCodeIndexService codeIndexService;

    private ApiCallPathTracer tracer;
    private EnvironmentIndexSnapshot snapshot;

    @BeforeEach
    void setUp() throws Exception {
        tracer = new ApiCallPathTracer(serviceDependencyTool, codeIndexService, new ParseStackTraceTool());
        snapshot = FixtureIndexSupport.buildSnapshot();

        when(codeIndexService.findByClassName(any(), eq("CampaignChHundredServiceImpl")))
                .thenAnswer(invocation -> java.util.Optional.of(
                        snapshot.getClassNameIndex().get("CampaignChHundredServiceImpl")));
        when(codeIndexService.findByClassName(any(), eq("ChHundredCampaignServiceImpl")))
                .thenAnswer(invocation -> java.util.Optional.of(
                        snapshot.getClassNameIndex().get("ChHundredCampaignServiceImpl")));
        when(codeIndexService.readSnippet(any(), any(), any(), any(Integer.class))).thenReturn("snippet");

        ApiMapping mapping = new ApiMapping();
        mapping.setTargetService("campaign-management-service");
        mapping.setTargetPath("/api/v1/campaign-management/ch-100/get");
        when(serviceDependencyTool.resolveApiMapping("ad-management-service", "/api/v1/campaign-ch-100"))
                .thenReturn(mapping);
    }

    @Test
    void trace_campaignCh100_mapsAmsToCmsHandlers() {
        IncidentRequest request = IncidentRequest.builder()
                .service("ad-management-service")
                .apiPath("/api/v1/campaign-ch-100")
                .environment("dev")
                .stackTrace("")
                .build();

        DownstreamError downstreamError = DownstreamError.builder()
                .path("/campaign-management-service/api/v1/campaign-management/ch-100/get")
                .code(500)
                .build();

        List<CallPathStep> steps = tracer.trace(request, snapshot, downstreamError);

        assertThat(steps).isNotEmpty();
        assertThat(steps.stream().map(CallPathStep::getClassName))
                .contains("CampaignChHundredController", "CampaignChHundredServiceImpl", "ChHundredCampaignController");

        CallPathStep cmsController = steps.stream()
                .filter(step -> step.getRole() == CallPathRole.DOWNSTREAM_CONTROLLER)
                .findFirst()
                .orElseThrow();
        assertThat(cmsController.getLine()).isGreaterThan(1);
        assertThat(cmsController.getMethodName()).isEqualTo("getCampaign");
    }

    @Test
    void trace_usesStackFramesInsteadOfPrefixMatch() {
        when(codeIndexService.findByClassName(any(), eq("CampaignChHundredController")))
                .thenAnswer(invocation -> java.util.Optional.of(
                        snapshot.getClassNameIndex().get("CampaignChHundredController")));

        IncidentRequest request = IncidentRequest.builder()
                .service("ad-management-service")
                .apiPath("/api/v1/campaign-ch-100")
                .environment("dev")
                .stackTrace("""
                        java.lang.NullPointerException: Cannot invoke "ChHundredInventoryToken.getToken()" because "token" is null
                        \tat com.tataplay.admanagement.module.ch100.campaign.service.impl.CampaignChHundredServiceImpl.lambda$getCampaign$1(CampaignChHundredServiceImpl.java:356)
                        \tat com.tataplay.admanagement.module.ch100.campaign.service.impl.CampaignChHundredServiceImpl.getCampaign(CampaignChHundredServiceImpl.java:352)
                        \tat com.tataplay.admanagement.controller.CampaignChHundredController.getCampaign(CampaignChHundredController.java:72)
                        """)
                .build();

        List<CallPathStep> steps = tracer.trace(request, snapshot, DownstreamError.builder().build());

        assertThat(steps).extracting(CallPathStep::getClassName)
                .containsExactly(
                        "CampaignChHundredController",
                        "CampaignChHundredServiceImpl",
                        "CampaignChHundredServiceImpl");
        assertThat(steps).extracting(CallPathStep::getLine).containsExactly(72, 352, 356);
        assertThat(steps).extracting(CallPathStep::getMethodName)
                .containsExactly("getCampaign", "getCampaign", "getCampaign");
        assertThat(steps).extracting(CallPathStep::getClassName).doesNotContain("ChHundredPacksController");
    }
}
