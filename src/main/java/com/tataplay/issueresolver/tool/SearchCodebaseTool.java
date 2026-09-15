package com.tataplay.issueresolver.tool;

import com.tataplay.issueresolver.config.EnvironmentRegistry;
import com.tataplay.issueresolver.index.CodeSearchResult;
import com.tataplay.issueresolver.index.EnvironmentIndexSnapshot;
import com.tataplay.issueresolver.index.RankedCodeSearchService;
import com.tataplay.issueresolver.index.SimpleCodeIndexService;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchCodebaseTool {

    private final SimpleCodeIndexService codeIndexService;
    private final RankedCodeSearchService rankedCodeSearchService;
    private final EnvironmentRegistry environmentRegistry;

    @Tool(description = "Search indexed Java source files for a symbol, method name, or text fragment. "
            + "Optionally filter by repo name such as ad-management-service or campaign-management-service.")
    public String searchCodebase(String query, String repoFilter) {
        return searchCodebase(query, repoFilter, "dev", "ranked");
    }

    public String searchCodebase(String query, String repoFilter, String environment, String searchMode) {
        String normalizedEnvironment = environmentRegistry.normalize(environment);
        EnvironmentIndexSnapshot snapshot = codeIndexService.prepareForEnvironment(normalizedEnvironment);

        List<CodeSearchResult> results = useRankedSearch(searchMode)
                ? rankedCodeSearchService.search(snapshot, query, repoFilter)
                : codeIndexService.searchCodebase(snapshot, query, repoFilter);

        if (results.isEmpty() && useRankedSearch(searchMode)) {
            results = codeIndexService.searchCodebase(snapshot, query, repoFilter);
        }

        if (results.isEmpty()) {
            return "No matches found for query: " + query;
        }
        return results.stream()
                .map(result -> String.format(
                        "repo=%s path=%s class=%s line=%d%n%s",
                        result.repo(),
                        result.path(),
                        result.className(),
                        result.matchLine(),
                        result.snippet()))
                .collect(Collectors.joining("\n---\n"));
    }

    private boolean useRankedSearch(String searchMode) {
        return searchMode == null
                || searchMode.isBlank()
                || "ranked".equalsIgnoreCase(searchMode);
    }
}
