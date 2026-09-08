package com.tataplay.issueresolver.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentToolsFacade {

    private final ParseStackTraceTool parseStackTraceTool;
    private final SearchCodebaseTool searchCodebaseTool;
    private final ServiceDependencyTool serviceDependencyTool;
    private final FindExceptionHandlersTool findExceptionHandlersTool;

    @Tool(description = "Parse a Java stack trace to extract exception type, message, top frame, "
            + "and Caused by chain.")
    public String parseStackTrace(String stackTrace) {
        ParseStackTraceTool.ParsedStackTrace parsed = parseStackTraceTool.parseStackTrace(stackTrace);
        StringBuilder builder = new StringBuilder();
        builder.append("exceptionType: ").append(parsed.getExceptionType()).append('\n');
        builder.append("exceptionMessage: ").append(parsed.getExceptionMessage()).append('\n');
        if (parsed.getTopFrame() != null) {
            ParseStackTraceTool.StackFrame frame = parsed.getTopFrame();
            builder.append("topFrame: ")
                    .append(frame.getClassName()).append('.')
                    .append(frame.getMethodName()).append('(')
                    .append(frame.getFileName());
            if (frame.getLineNumber() != null) {
                builder.append(':').append(frame.getLineNumber());
            }
            builder.append(')').append('\n');
        }
        if (!parsed.getCausedByChain().isEmpty()) {
            builder.append("causedByChain: ").append(parsed.getCausedByChain()).append('\n');
        }
        return builder.toString().trim();
    }

    @Tool(description = "Search indexed Java source files for a symbol, method name, or text fragment.")
    public String searchCodebase(String query, String repoFilter) {
        return searchCodebaseTool.searchCodebase(query, repoFilter);
    }

    @Tool(description = "Return downstream service dependencies and API path mappings.")
    public String getServiceDependencies(String serviceName, String apiPath) {
        return serviceDependencyTool.getServiceDependencies(serviceName, apiPath);
    }

    @Tool(description = "Find exception handler classes in a service repository.")
    public String findExceptionHandlers(String serviceName) {
        return findExceptionHandlersTool.findExceptionHandlers(serviceName);
    }
}
