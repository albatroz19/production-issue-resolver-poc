package com.tataplay.issueresolver.tool;

import com.tataplay.issueresolver.config.EnvironmentRegistry;
import com.tataplay.issueresolver.index.SimpleCodeIndexService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReadCodeSnippetTool {

    private final SimpleCodeIndexService codeIndexService;
    private final EnvironmentRegistry environmentRegistry;

    @Tool(description = "Read a code snippet centered on a specific line from an indexed repository file.")
    public String readCodeSnippet(String repo, String path, int line, String environment) {
        String normalizedEnvironment = environmentRegistry.normalize(environment);
        String snippet = codeIndexService.readSnippet(
                codeIndexService.prepareForEnvironment(normalizedEnvironment),
                repo,
                path,
                line);
        if (snippet.isBlank()) {
            return "No snippet found for repo=" + repo + " path=" + path + " line=" + line;
        }
        return "repo=" + repo + " path=" + path + " line=" + line + "\n" + snippet;
    }
}
