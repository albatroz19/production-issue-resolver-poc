package com.tataplay.issueresolver.tool;

import com.tataplay.issueresolver.config.EnvironmentRegistry;
import com.tataplay.issueresolver.index.EnvironmentIndexSnapshot;
import com.tataplay.issueresolver.index.SimpleCodeIndexService;
import com.tataplay.issueresolver.model.CallPathStep;
import com.tataplay.issueresolver.model.IncidentRequest;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TraceApiCallPathTool {

    private final ApiCallPathTracer apiCallPathTracer;
    private final SimpleCodeIndexService codeIndexService;
    private final EnvironmentRegistry environmentRegistry;
    private final DownstreamErrorParser downstreamErrorParser;

    @Tool(description = "Trace API call path from entry controller through proxy to downstream handler.")
    public String traceApiCallPath(String service, String apiPath, String environment) {
        String normalizedEnvironment = environmentRegistry.normalize(environment);
        EnvironmentIndexSnapshot snapshot = codeIndexService.prepareForEnvironment(normalizedEnvironment);
        IncidentRequest request = IncidentRequest.builder()
                .service(service)
                .apiPath(apiPath)
                .environment(normalizedEnvironment)
                .stackTrace("")
                .build();

        return apiCallPathTracer.trace(request, snapshot, downstreamErrorParser.parse(apiPath)).stream()
                .map(this::formatStep)
                .collect(Collectors.joining("\n---\n"));
    }

    private String formatStep(CallPathStep step) {
        return String.format(
                "role=%s repo=%s class=%s method=%s line=%d path=%s%n%s",
                step.getRole(),
                step.getRepo(),
                step.getClassName(),
                step.getMethodName(),
                step.getLine(),
                step.getPath(),
                step.getSnippet());
    }
}
