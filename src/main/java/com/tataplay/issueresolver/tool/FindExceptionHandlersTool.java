package com.tataplay.issueresolver.tool;

import com.tataplay.issueresolver.index.CodeSearchResult;
import com.tataplay.issueresolver.index.SimpleCodeIndexService;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FindExceptionHandlersTool {

    private final SimpleCodeIndexService codeIndexService;

    @Tool(description = "Find @RestControllerAdvice or @ControllerAdvice exception handler classes "
            + "in the given service repository.")
    public String findExceptionHandlers(String serviceName) {
        List<CodeSearchResult> results = codeIndexService.findExceptionHandlers(serviceName);
        if (results.isEmpty()) {
            return "No exception handlers found for service: " + serviceName;
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
}
