package com.tataplay.issueresolver.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.tataplay.issueresolver.config.IssueResolverProperties;
import com.tataplay.issueresolver.testsupport.FixtureIndexSupport;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RankedCodeSearchServiceTest {

    private RankedCodeSearchService rankedCodeSearchService;
    private EnvironmentIndexSnapshot snapshot;

    @BeforeEach
    void setUp() throws Exception {
        IssueResolverProperties properties = new IssueResolverProperties();
        properties.setMaxSnippetLines(20);
        properties.setMaxFilesPerRequest(3);
        rankedCodeSearchService = new RankedCodeSearchService(properties);
        snapshot = FixtureIndexSupport.buildSnapshot();
    }

    @Test
    void search_getCampaignCh100_ranksRelevantControllersFirst() {
        List<CodeSearchResult> results = rankedCodeSearchService.search(
                snapshot, "getCampaign ch-100", "campaign-management-service");

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).className()).isEqualTo("ChHundredCampaignController");
    }
}
