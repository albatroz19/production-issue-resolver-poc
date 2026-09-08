package com.tataplay.issueresolver.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "issue-resolver")
public class IssueResolverProperties {

    private String reposRoot = "/Users/amitasharda/Desktop/Segmentation";
    private List<RepoConfig> repos = new ArrayList<>();
    private int maxSnippetLines = 150;
    private int maxFilesPerRequest = 3;
    private AgentConfig agent = new AgentConfig();
    private String apiKey = "";
    private PythonAgentProperties pythonAgent = new PythonAgentProperties();

    @Data
    public static class RepoConfig {
        private String name;
        private String path;
    }

    @Data
    public static class AgentConfig {
        private int maxSteps = 8;
    }
}
