package com.tataplay.issueresolver.tool;

import com.tataplay.issueresolver.index.EnvironmentIndexSnapshot;
import com.tataplay.issueresolver.index.IndexedFile;
import com.tataplay.issueresolver.index.SimpleCodeIndexService;
import com.tataplay.issueresolver.model.CallPathRole;
import com.tataplay.issueresolver.model.CallPathStep;
import com.tataplay.issueresolver.model.IncidentRequest;
import com.tataplay.issueresolver.tool.DownstreamErrorParser.DownstreamError;
import com.tataplay.issueresolver.tool.ServiceDependencyTool.ApiMapping;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ApiCallPathTracer {

    private static final Pattern REQUEST_MAPPING_PATTERN = Pattern.compile(
            "@RequestMapping\\s*\\(\\s*(?:path\\s*=\\s*)?[\"']([^\"']+)[\"']");
    private static final Pattern GET_MAPPING_PATTERN = Pattern.compile("@GetMapping(?:\\s*\\(\\s*)?(?:[\"']([^\"']*)[\"'])?");
    private static final Pattern POST_MAPPING_PATTERN = Pattern.compile("@PostMapping(?:\\s*\\(\\s*)?(?:[\"']([^\"']*)[\"'])?");
    private static final Pattern SERVICE_CALL_PATTERN = Pattern.compile(
            "([\\w]+Service)\\.([\\w]+)\\s*\\(");
    private static final Pattern PROXY_URL_PATTERN = Pattern.compile(
            "(getCampaignServiceUrl\\(\\)\\s*\\+\\s*\"([^\"]+)\"|restTemplate\\.exchange\\s*\\()",
            Pattern.CASE_INSENSITIVE);

    private final ServiceDependencyTool serviceDependencyTool;
    private final SimpleCodeIndexService codeIndexService;
    private final ParseStackTraceTool parseStackTraceTool;

    public List<CallPathStep> trace(
            IncidentRequest request,
            EnvironmentIndexSnapshot snapshot,
            DownstreamError downstreamError) {
        List<CallPathStep> fromStack = traceFromStack(request, snapshot);
        if (!fromStack.isEmpty()) {
            return fromStack;
        }

        List<CallPathStep> steps = new ArrayList<>();
        if (request.getService() == null || request.getApiPath() == null || request.getApiPath().isBlank()) {
            return steps;
        }

        String apiPath = stripQueryString(request.getApiPath());
        ApiMapping mapping = serviceDependencyTool.resolveApiMapping(request.getService(), apiPath);
        String targetService = mapping != null ? mapping.getTargetService() : guessTargetService(downstreamError);
        String targetPath = mapping != null ? mapping.getTargetPath() : extractPathSuffix(downstreamError);

        findEntryController(snapshot, request.getService(), apiPath).ifPresent(controllerStep -> {
            steps.add(controllerStep);
            findEntryService(snapshot, request.getService(), controllerStep).ifPresent(steps::add);
        });

        findProxyCall(snapshot, request.getService(), targetPath).ifPresent(steps::add);

        if (targetService != null && targetPath != null) {
            findDownstreamController(snapshot, targetService, targetPath).ifPresent(controllerStep -> {
                steps.add(controllerStep);
                findDownstreamService(snapshot, targetService, controllerStep).ifPresent(steps::add);
            });
        }

        return steps;
    }

    private List<CallPathStep> traceFromStack(IncidentRequest request, EnvironmentIndexSnapshot snapshot) {
        if (request.getStackTrace() == null || request.getStackTrace().isBlank()) {
            return List.of();
        }
        ParseStackTraceTool.ParsedStackTrace parsed = parseStackTraceTool.parseStackTrace(request.getStackTrace());
        List<ParseStackTraceTool.StackFrame> applicationFrames = new ArrayList<>(parsed.getFrames().stream()
                .filter(frame -> frame.getClassName() != null && frame.getClassName().startsWith("com.tataplay."))
                .toList());
        java.util.Collections.reverse(applicationFrames);
        if (applicationFrames.isEmpty()) {
            return List.of();
        }

        List<CallPathStep> steps = new ArrayList<>();
        for (ParseStackTraceTool.StackFrame frame : applicationFrames) {
            String simpleName = frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1);
            Optional<IndexedFile> indexed = codeIndexService.findByClassName(snapshot, simpleName);
            if (indexed.isEmpty()) {
                continue;
            }
            IndexedFile file = indexed.get();
            int line = frame.getLineNumber() != null ? frame.getLineNumber() : 1;
            String methodName = displayMethodName(frame.getMethodName());
            steps.add(buildStep(snapshot, file, line, methodName, roleFor(file, request.getService())));
        }
        return steps;
    }

    private String displayMethodName(String methodName) {
        if (methodName == null || methodName.isBlank()) {
            return "unknown";
        }
        if (methodName.startsWith("lambda$") && methodName.contains("$")) {
            String inner = methodName.substring("lambda$".length());
            int end = inner.indexOf('$');
            if (end > 0) {
                return inner.substring(0, end);
            }
        }
        return methodName;
    }

    private CallPathRole roleFor(IndexedFile file, String requestService) {
        boolean entryRepo = requestService == null || requestService.equalsIgnoreCase(file.repo());
        if (file.className().endsWith("Controller")) {
            return entryRepo ? CallPathRole.ENTRY_CONTROLLER : CallPathRole.DOWNSTREAM_CONTROLLER;
        }
        return entryRepo ? CallPathRole.ENTRY_SERVICE : CallPathRole.DOWNSTREAM_SERVICE;
    }

    private Optional<CallPathStep> findEntryController(
            EnvironmentIndexSnapshot snapshot, String repo, String apiPath) {
        String pathSegment = lastSegment(apiPath);
        for (IndexedFile file : snapshot.getAllFiles()) {
            if (!file.repo().equalsIgnoreCase(repo) || !file.className().endsWith("Controller")) {
                continue;
            }
            try {
                List<String> lines = readLines(file);
                if (!matchesControllerPath(lines, apiPath, pathSegment)) {
                    continue;
                }
                int mappingLine = findControllerMappingLine(lines, apiPath, pathSegment);
                String methodName = extractMethodNameNearLine(lines, mappingLine, "get");
                return Optional.of(buildStep(
                        snapshot, file, mappingLine, methodName, CallPathRole.ENTRY_CONTROLLER));
            } catch (IOException ignored) {
                // continue
            }
        }
        return Optional.empty();
    }

    private Optional<CallPathStep> findEntryService(
            EnvironmentIndexSnapshot snapshot, String repo, CallPathStep controllerStep) {
        try {
            IndexedFile controllerFile = findFile(snapshot, controllerStep);
            List<String> lines = readLines(controllerFile);
            Matcher matcher = SERVICE_CALL_PATTERN.matcher(String.join("\n", lines));
            if (!matcher.find()) {
                return Optional.empty();
            }
            String serviceBean = matcher.group(1);
            String methodName = matcher.group(2);
            String implClassName = toImplClassName(serviceBean);
            return codeIndexService.findByClassName(snapshot, implClassName).map(file -> {
                int line = findMethodLine(readLinesSafe(file), methodName);
                return buildStep(snapshot, file, line, methodName, CallPathRole.ENTRY_SERVICE);
            });
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private Optional<CallPathStep> findProxyCall(
            EnvironmentIndexSnapshot snapshot, String repo, String targetPath) {
        if (targetPath == null || targetPath.isBlank()) {
            return Optional.empty();
        }
        String suffix = lastSegment(targetPath);
        for (IndexedFile file : snapshot.getAllFiles()) {
            if (!file.repo().equalsIgnoreCase(repo) || !file.className().endsWith("ServiceImpl")) {
                continue;
            }
            try {
                List<String> lines = readLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.contains(suffix) && (line.contains("getCampaignServiceUrl")
                            || line.toLowerCase(Locale.ROOT).contains("resttemplate"))) {
                        return Optional.of(buildStep(
                                snapshot, file, i + 1, "proxyCall", CallPathRole.PROXY_CALL));
                    }
                }
            } catch (IOException ignored) {
                // continue
            }
        }
        return Optional.empty();
    }

    private Optional<CallPathStep> findDownstreamController(
            EnvironmentIndexSnapshot snapshot, String repo, String targetPath) {
        String suffix = lastSegment(targetPath);
        for (IndexedFile file : snapshot.getAllFiles()) {
            if (!file.repo().equalsIgnoreCase(repo) || !file.className().endsWith("Controller")) {
                continue;
            }
            try {
                List<String> lines = readLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if ((line.contains("@PostMapping") || line.contains("@GetMapping"))
                            && line.contains("\"" + suffix + "\"")) {
                        String methodName = extractMethodNameNearLine(lines, i + 1, suffix.contains("get") ? "get" : "");
                        return Optional.of(buildStep(
                                snapshot, file, i + 1, methodName, CallPathRole.DOWNSTREAM_CONTROLLER));
                    }
                }
            } catch (IOException ignored) {
                // continue
            }
        }
        return Optional.empty();
    }

    private Optional<CallPathStep> findDownstreamService(
            EnvironmentIndexSnapshot snapshot, String repo, CallPathStep controllerStep) {
        try {
            IndexedFile controllerFile = findFile(snapshot, controllerStep);
            List<String> lines = readLines(controllerFile);
            Matcher matcher = SERVICE_CALL_PATTERN.matcher(String.join("\n", lines));
            if (!matcher.find()) {
                return Optional.empty();
            }
            String serviceBean = matcher.group(1);
            String methodName = matcher.group(2);
            String implClassName = toImplClassName(serviceBean);
            return codeIndexService.findByClassName(snapshot, implClassName).map(file -> {
                int line = findMethodLine(readLinesSafe(file), methodName);
                return buildStep(snapshot, file, line, methodName, CallPathRole.DOWNSTREAM_SERVICE);
            });
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private CallPathStep buildStep(
            EnvironmentIndexSnapshot snapshot,
            IndexedFile file,
            int line,
            String methodName,
            CallPathRole role) {
        return CallPathStep.builder()
                .repo(file.repo())
                .className(file.className())
                .methodName(methodName)
                .path(file.relativePath())
                .line(line)
                .role(role)
                .snippet(codeIndexService.readSnippet(snapshot, file.repo(), file.relativePath(), line))
                .build();
    }

    private IndexedFile findFile(EnvironmentIndexSnapshot snapshot, CallPathStep step) {
        return snapshot.getAllFiles().stream()
                .filter(file -> file.repo().equals(step.getRepo()) && file.relativePath().equals(step.getPath()))
                .findFirst()
                .orElseThrow();
    }

    private boolean matchesControllerPath(List<String> lines, String apiPath, String pathSegment) {
        String classMapping = extractClassRequestMapping(lines);
        if (classMapping == null) {
            return false;
        }
        String normalizedMapping = normalizePath(classMapping);
        String normalizedApi = normalizePath(apiPath);
        return normalizedApi.equals(normalizedMapping) || normalizedApi.startsWith(normalizedMapping + "/");
    }

    private String extractClassRequestMapping(List<String> lines) {
        for (String line : lines) {
            Matcher matcher = REQUEST_MAPPING_PATTERN.matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String trimmed = path.trim();
        if (trimmed.length() > 1 && trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private int findControllerMappingLine(List<String> lines, String apiPath, String pathSegment) {
        String normalizedApi = normalizePath(apiPath);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher matcher = REQUEST_MAPPING_PATTERN.matcher(line);
            if (matcher.find() && normalizePath(matcher.group(1)).equals(normalizedApi)) {
                int methodLine = findHandlerMethodLine(lines, i + 1);
                return methodLine > 0 ? methodLine : i + 1;
            }
        }
        return 1;
    }

    private int findHandlerMethodLine(List<String> lines, int afterMappingIndex) {
        for (int i = afterMappingIndex; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.contains("public ") && line.contains("(")) {
                return i + 1;
            }
        }
        return -1;
    }

    private String extractMethodNameNearLine(List<String> lines, int mappingLine, String hint) {
        for (int i = mappingLine; i < Math.min(lines.size(), mappingLine + 5); i++) {
            String line = lines.get(i).trim();
            if (line.contains("public ") && line.contains("(")) {
                Matcher matcher = Pattern.compile("public\\s+[\\w<>,\\s]+\\s+(\\w+)\\s*\\(").matcher(line);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        return hint.isBlank() ? "handler" : hint;
    }

    private int findMethodLine(List<String> lines, String methodName) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(methodName + "(")) {
                return i + 1;
            }
        }
        return 1;
    }

    private String toImplClassName(String serviceBean) {
        if (serviceBean == null || serviceBean.isBlank()) {
            return "UnknownServiceImpl";
        }
        String className = Character.toUpperCase(serviceBean.charAt(0)) + serviceBean.substring(1);
        return className.endsWith("Impl") ? className : className + "Impl";
    }

    private String guessTargetService(DownstreamError downstreamError) {
        if (downstreamError != null && downstreamError.getPath() != null
                && downstreamError.getPath().contains("campaign-management-service")) {
            return "campaign-management-service";
        }
        return null;
    }

    private String extractPathSuffix(DownstreamError downstreamError) {
        if (downstreamError == null || downstreamError.getPath() == null) {
            return null;
        }
        String path = downstreamError.getPath();
        int apiIndex = path.indexOf("/api/");
        return apiIndex >= 0 ? path.substring(apiIndex) : path;
    }

    private String stripQueryString(String apiPath) {
        int queryIndex = apiPath.indexOf('?');
        return queryIndex >= 0 ? apiPath.substring(0, queryIndex) : apiPath;
    }

    private String lastSegment(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash) : trimmed;
    }

    private List<String> readLines(IndexedFile file) throws IOException {
        return Files.readAllLines(Paths.get(file.absolutePath()), StandardCharsets.UTF_8);
    }

    private List<String> readLinesSafe(IndexedFile file) {
        try {
            return readLines(file);
        } catch (IOException ex) {
            return List.of();
        }
    }
}
