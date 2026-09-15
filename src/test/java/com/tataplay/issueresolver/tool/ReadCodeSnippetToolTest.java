package com.tataplay.issueresolver.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.tataplay.issueresolver.config.EnvironmentRegistry;
import com.tataplay.issueresolver.index.EnvironmentIndexSnapshot;
import com.tataplay.issueresolver.index.SimpleCodeIndexService;
import com.tataplay.issueresolver.model.EnvironmentProfile;
import com.tataplay.issueresolver.testsupport.FixtureIndexSupport;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadCodeSnippetToolTest {

    @Mock
    private SimpleCodeIndexService codeIndexService;

    private ReadCodeSnippetTool readCodeSnippetTool;

    @BeforeEach
    void setUp() throws Exception {
        EnvironmentProfile devProfile = new EnvironmentProfile();
        EnvironmentRegistry environmentRegistry = new EnvironmentRegistry(Map.of("dev", devProfile));
        readCodeSnippetTool = new ReadCodeSnippetTool(codeIndexService, environmentRegistry);

        EnvironmentIndexSnapshot snapshot = FixtureIndexSupport.buildSnapshot();
        when(codeIndexService.prepareForEnvironment(any())).thenReturn(snapshot);
        when(codeIndexService.readSnippet(eq(snapshot), eq("campaign-management-service"), any(), eq(17)))
                .thenReturn("  17| @PostMapping(\"/get\")");
    }

    @Test
    void readCodeSnippet_returnsCenteredSnippet() {
        String response = readCodeSnippetTool.readCodeSnippet(
                "campaign-management-service",
                "com/tataplay/cms/controller/ChHundredCampaignController.java",
                17,
                "dev");

        assertThat(response).contains("line=17");
        assertThat(response).contains("@PostMapping");
    }
}
