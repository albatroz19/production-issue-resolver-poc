package com.tataplay.issueresolver.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tataplay.issueresolver.client.PythonAgentClient;
import com.tataplay.issueresolver.config.EnvironmentRegistry;
import com.tataplay.issueresolver.config.IssueResolverProperties;
import com.tataplay.issueresolver.index.CodeSearchResult;
import com.tataplay.issueresolver.index.EnvironmentIndexSnapshot;
import com.tataplay.issueresolver.index.SimpleCodeIndexService;
import com.tataplay.issueresolver.model.AffectedFile;
import com.tataplay.issueresolver.model.CallPathRole;
import com.tataplay.issueresolver.model.CallPathStep;
import com.tataplay.issueresolver.model.ConfidenceLevel;
import com.tataplay.issueresolver.model.DiagnosisResponse;
import com.tataplay.issueresolver.model.IncidentRequest;
import com.tataplay.issueresolver.service.DiagnosisMerger;
import com.tataplay.issueresolver.tool.ApiCallPathTracer;
import com.tataplay.issueresolver.tool.DownstreamErrorParser;
import com.tataplay.issueresolver.tool.ParseStackTraceTool;
import com.tataplay.issueresolver.tool.ServiceDependencyTool;
import com.tataplay.issueresolver.tool.ServiceDependencyTool.ApiMapping;
import com.tataplay.issueresolver.util.LogRedactor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class IssueResolverAgent {

    private static final String SYSTEM_PROMPT = """
            You are a production issue diagnosis agent for Java Spring Boot microservices.
            Use the provided tools to gather evidence from stack traces and the local codebase index.
            Rules:
            - Prefer evidence from tool results over guessing.
            - Identify cross-service failures when a service proxies another (e.g. AMS -> CMS).
            - Separate symptom (e.g. NullPointerException) from root cause (e.g. missing JSON field).
            - Never suggest destructive production actions (restart, data delete, force push).
            - Return ONLY valid JSON matching this schema:
            {
              "rootCause": "string",
              "confidence": "HIGH|MEDIUM|LOW",
              "affectedFiles": [{"repo":"string","path":"string","lines":"string"}],
              "suggestedFix": "string",
              "reasoningSteps": ["string"],
              "relatedIncidents": ["string"]
            }
            """;

    private final IssueResolverProperties properties;
    private final EnvironmentRegistry environmentRegistry;
    private final ParseStackTraceTool parseStackTraceTool;
    private final DownstreamErrorParser downstreamErrorParser;
    private final SimpleCodeIndexService codeIndexService;
    private final ServiceDependencyTool serviceDependencyTool;
    private final ApiCallPathTracer apiCallPathTracer;
    private final ObjectMapper objectMapper;
    private final Optional<ChatClient> chatClient;
    private final PythonAgentClient pythonAgentClient;
    private final DiagnosisMerger diagnosisMerger;

    public IssueResolverAgent(
            IssueResolverProperties properties,
            EnvironmentRegistry environmentRegistry,
            ParseStackTraceTool parseStackTraceTool,
            DownstreamErrorParser downstreamErrorParser,
            SimpleCodeIndexService codeIndexService,
            ServiceDependencyTool serviceDependencyTool,
            ApiCallPathTracer apiCallPathTracer,
            ObjectMapper objectMapper,
            PythonAgentClient pythonAgentClient,
            DiagnosisMerger diagnosisMerger,
            @Autowired(required = false) ChatClient issueResolverChatClient) {
        this.properties = properties;
        this.environmentRegistry = environmentRegistry;
        this.parseStackTraceTool = parseStackTraceTool;
        this.downstreamErrorParser = downstreamErrorParser;
        this.codeIndexService = codeIndexService;
        this.serviceDependencyTool = serviceDependencyTool;
        this.apiCallPathTracer = apiCallPathTracer;
        this.objectMapper = objectMapper;
        this.pythonAgentClient = pythonAgentClient;
        this.diagnosisMerger = diagnosisMerger;
        this.chatClient = properties.getPythonAgent().isEnabled()
                ? Optional.empty()
                : Optional.ofNullable(issueResolverChatClient);
    }

    public DiagnosisResponse analyze(IncidentRequest request) {
        String environment = environmentRegistry.normalize(request.getEnvironment());
        EnvironmentIndexSnapshot indexSnapshot = codeIndexService.prepareForEnvironment(environment);
        List<String> reasoningSteps = new ArrayList<>();

        ParseStackTraceTool.ParsedStackTrace parsed = parseStackTraceTool.parseStackTrace(request.getStackTrace());
        reasoningSteps.add("Parsed exception: "
                + parsed.getExceptionType()
                + (parsed.getExceptionMessage() != null ? " - " + parsed.getExceptionMessage() : ""));

        ParseStackTraceTool.StackFrame applicationFrame = parsed.getApplicationTopFrame();
        if (applicationFrame != null) {
            reasoningSteps.add("Application frame: " + applicationFrame.getClassName() + "." + applicationFrame.getMethodName()
                    + "(" + applicationFrame.getFileName()
                    + (applicationFrame.getLineNumber() != null ? ":" + applicationFrame.getLineNumber() : "") + ")");
        } else if (parsed.getTopFrame() != null) {
            ParseStackTraceTool.StackFrame topFrame = parsed.getTopFrame();
            reasoningSteps.add("Top frame: " + topFrame.getClassName() + "." + topFrame.getMethodName()
                    + "(" + topFrame.getFileName()
                    + (topFrame.getLineNumber() != null ? ":" + topFrame.getLineNumber() : "") + ")");
        }

        String serviceDeps = serviceDependencyTool.getServiceDependencies(request.getService(), request.getApiPath());
        reasoningSteps.add("Service dependencies: " + summarize(serviceDeps));
        reasoningSteps.add("Indexed code from branch(es): " + indexSnapshot.getIndexedBranch()
                + " (commit " + indexSnapshot.getIndexedCommit() + ")");

        DiagnosisResponse javaDiagnosis = buildRuleBasedDiagnosis(request, parsed, indexSnapshot, reasoningSteps, environment);

        if (properties.getPythonAgent().isEnabled()) {
            DiagnosisResponse pythonDiagnosis = pythonAgentClient.analyze(request);
            return enrichMetadata(diagnosisMerger.merge(javaDiagnosis, pythonDiagnosis, properties.getMaxFilesPerRequest()),
                    environment, indexSnapshot, javaDiagnosis);
        }

        if (chatClient.isPresent()) {
            try {
                String userPrompt = buildUserPrompt(request, reasoningSteps);
                String response = chatClient.get()
                        .prompt()
                        .system(SYSTEM_PROMPT)
                        .user(userPrompt)
                        .call()
                        .content();
                DiagnosisResponse llmResponse = parseDiagnosisResponse(response);
                mergeReasoning(llmResponse, reasoningSteps);
                return enrichMetadata(llmResponse, environment, indexSnapshot, javaDiagnosis);
            } catch (Exception ex) {
                log.warn("LLM diagnosis failed, falling back to rule-based analysis: {}", ex.getMessage());
                reasoningSteps.add("LLM unavailable or failed; used rule-based fallback.");
            }
        } else {
            reasoningSteps.add("Azure OpenAI not configured; used rule-based fallback.");
        }

        return javaDiagnosis;
    }

    DiagnosisResponse buildRuleBasedDiagnosis(
            IncidentRequest request,
            ParseStackTraceTool.ParsedStackTrace parsed,
            EnvironmentIndexSnapshot indexSnapshot,
            List<String> reasoningSteps,
            String environment) {

        List<AffectedFile> affectedFiles = new ArrayList<>();
        List<CallPathStep> callPath = new ArrayList<>();
        String rootCause;
        String suggestedFix;
        ConfidenceLevel confidence = ConfidenceLevel.MEDIUM;
        String downstreamService = null;
        String downstreamPath = null;

        ParseStackTraceTool.StackFrame applicationFrame = parsed.getApplicationTopFrame();
        ParseStackTraceTool.StackFrame topFrame = parsed.getTopFrame();
        ParseStackTraceTool.StackFrame effectiveFrame = applicationFrame != null ? applicationFrame : topFrame;
        String methodHint = effectiveFrame != null ? effectiveFrame.getMethodName() : "";
        String classHint = effectiveFrame != null ? effectiveFrame.getClassName() : "";

        DownstreamErrorParser.DownstreamError downstreamError = downstreamErrorParser.parse(
                request.getStackTrace(),
                request.getRecentLogs(),
                request.getErrorMessage());
        if (downstreamError.hasPath()) {
            downstreamPath = downstreamError.getPath();
            downstreamService = extractServiceName(downstreamPath);
            reasoningSteps.add("Detected downstream error path: " + downstreamPath
                    + (downstreamError.getCode() != null ? " (HTTP " + downstreamError.getCode() + ")" : ""));
        }

        if (classHint.contains("RestTemplateUtility") && "extractErrorMessage".equals(methodHint)) {
            confidence = ConfidenceLevel.HIGH;
            rootCause = "AMS RestTemplateUtility.extractErrorMessage assumes the downstream CMS error JSON "
                    + "contains a 'message' field. CMS returned HTTP 400 without that field, causing a "
                    + "NullPointerException when parsing the error body.";
            suggestedFix = "Add null-safe parsing in RestTemplateUtility.extractErrorMessage: check JsonNode "
                    + "for missing/null 'message' before calling asText(), and fall back to raw body or status text. "
                    + "Also verify CMS ErrorHandler returns a consistent error shape for BusinessValidationException.";
            reasoningSteps.add("Matched golden case: NPE in RestTemplateUtility.extractErrorMessage during CMS proxy call.");

            codeIndexService.searchCodebase(indexSnapshot, "extractErrorMessage", "ad-management-service").stream()
                    .limit(properties.getMaxFilesPerRequest())
                    .forEach(result -> affectedFiles.add(toAffectedFile(result, effectiveFrame)));

            codeIndexService.findExceptionHandlers(indexSnapshot, "campaign-management-service").stream()
                    .limit(1)
                    .forEach(result -> affectedFiles.add(toAffectedFile(result, null)));
        } else if (matchesCampaignCh100CmsProxyFailure(request, downstreamError)) {
            confidence = ConfidenceLevel.HIGH;
            callPath = traceCallPath(request, indexSnapshot, downstreamError, reasoningSteps);
            CallPathStep cmsController = findStep(callPath, CallPathRole.DOWNSTREAM_CONTROLLER);
            CallPathStep cmsService = findStep(callPath, CallPathRole.DOWNSTREAM_SERVICE);
            rootCause = "CMS returned HTTP 500 on /api/v1/campaign-management/ch-100/get. "
                    + "AMS propagated the downstream failure via RestTemplate while handling /api/v1/campaign-ch-100.";
            if (cmsController != null) {
                rootCause += " CMS failure at "
                        + cmsController.getClassName() + "." + cmsController.getMethodName()
                        + " (line " + cmsController.getLine() + ")";
                if (cmsService != null) {
                    rootCause += " → investigate " + cmsService.getClassName() + "." + cmsService.getMethodName()
                            + " implementation.";
                }
            }
            suggestedFix = "Inspect CMS logs for /ch-100/get around the incident timestamp. "
                    + "Verify request params (offset, limit, id) and CMS-side validation/data issues in "
                    + (cmsController != null ? cmsController.getClassName() : "ChHundredCampaignController")
                    + " and related services.";
            reasoningSteps.add("Matched campaign-ch-100 CMS proxy failure pattern.");
            affectedFiles.addAll(toAffectedFilesFromCallPath(callPath));
        } else if (containsIgnoreCase(request, "businessvalidationexception")) {
            confidence = ConfidenceLevel.HIGH;
            rootCause = "CMS rejected the request with BusinessValidationException; AMS may not handle the "
                    + "400/500 response body shape correctly.";
            suggestedFix = "Inspect CMS ErrorHandler for the validation error payload and ensure AMS parses "
                    + "the response fields defensively.";
            reasoningSteps.add("Detected BusinessValidationException in incident text.");

            codeIndexService.findExceptionHandlers(indexSnapshot, "campaign-management-service").stream()
                    .limit(properties.getMaxFilesPerRequest())
                    .forEach(result -> affectedFiles.add(toAffectedFile(result, null)));
        } else if (classHint.contains("Ch100ScteSlotServiceImpl") || containsIgnoreCase(request, "parseTimeToMillis")) {
            confidence = ConfidenceLevel.HIGH;
            rootCause = "SCTE time matching in Ch100ScteSlotServiceImpl may ignore seconds in parseTimeToMillis, "
                    + "causing one SCTE marker to attach to multiple assets.";
            suggestedFix = "Ensure parseTimeToMillis parses hours, minutes, and seconds. Compare asset start "
                    + "times using full precision before attaching SCTE slots.";
            reasoningSteps.add("Matched SCTE slot timing issue pattern.");

            codeIndexService.searchCodebase(indexSnapshot, "parseTimeToMillis", "campaign-management-service").stream()
                    .limit(properties.getMaxFilesPerRequest())
                    .forEach(result -> affectedFiles.add(toAffectedFile(result, effectiveFrame)));
        } else {
            rootCause = "Unable to determine root cause without LLM. Review top stack frame and downstream service mapping.";
            suggestedFix = "Inspect the failing class/method and downstream service response for the incident API path.";
            confidence = ConfidenceLevel.LOW;

            if (effectiveFrame != null) {
                String simpleClass = classHint.contains(".")
                        ? classHint.substring(classHint.lastIndexOf('.') + 1)
                        : classHint;
                codeIndexService.findByClassName(indexSnapshot, simpleClass).ifPresent(file -> {
                    CodeSearchResult result = new CodeSearchResult(
                            file.repo(),
                            file.relativePath(),
                            file.className(),
                            effectiveFrame.getLineNumber() != null ? effectiveFrame.getLineNumber() : 1,
                            codeIndexService.readSnippet(
                                    indexSnapshot,
                                    file.repo(),
                                    file.relativePath(),
                                    effectiveFrame.getLineNumber() != null ? effectiveFrame.getLineNumber() : 1));
                    affectedFiles.add(toAffectedFile(result, effectiveFrame));
                });
            }
        }

        if (callPath.isEmpty() && shouldTraceCallPath(request, downstreamError)) {
            callPath = traceCallPath(request, indexSnapshot, downstreamError, reasoningSteps);
            if (!callPath.isEmpty() && affectedFiles.isEmpty()) {
                affectedFiles.addAll(toAffectedFilesFromCallPath(callPath));
            }
        }

        return DiagnosisResponse.builder()
                .rootCause(rootCause)
                .confidence(confidence)
                .affectedFiles(affectedFiles.stream().limit(properties.getMaxFilesPerRequest()).toList())
                .suggestedFix(suggestedFix)
                .reasoningSteps(new ArrayList<>(reasoningSteps))
                .relatedIncidents(List.of())
                .environment(environment)
                .indexedBranch(indexSnapshot.getIndexedBranch())
                .indexedCommit(indexSnapshot.getIndexedCommit())
                .downstreamService(downstreamService)
                .downstreamPath(downstreamPath)
                .callPath(callPath)
                .build();
    }

    private boolean shouldTraceCallPath(
            IncidentRequest request, DownstreamErrorParser.DownstreamError downstreamError) {
        if (downstreamError != null && downstreamError.hasPath()) {
            return true;
        }
        if (request.getApiPath() != null && !request.getApiPath().isBlank()) {
            ApiMapping mapping = serviceDependencyTool.resolveApiMapping(request.getService(), request.getApiPath());
            return mapping != null;
        }
        return false;
    }

    private List<CallPathStep> traceCallPath(
            IncidentRequest request,
            EnvironmentIndexSnapshot indexSnapshot,
            DownstreamErrorParser.DownstreamError downstreamError,
            List<String> reasoningSteps) {
        List<CallPathStep> steps = apiCallPathTracer.trace(request, indexSnapshot, downstreamError);
        if (!steps.isEmpty()) {
            reasoningSteps.add("Traced call path: " + formatCallPathSummary(steps));
        }
        return steps;
    }

    private String formatCallPathSummary(List<CallPathStep> steps) {
        return steps.stream()
                .map(step -> step.getClassName() + "." + step.getMethodName() + " (" + step.getLine() + ")")
                .reduce((left, right) -> left + " → " + right)
                .orElse("none");
    }

    private CallPathStep findStep(List<CallPathStep> steps, CallPathRole role) {
        return steps.stream()
                .filter(step -> step.getRole() == role)
                .findFirst()
                .orElse(null);
    }

    private List<AffectedFile> toAffectedFilesFromCallPath(List<CallPathStep> steps) {
        List<AffectedFile> files = new ArrayList<>();
        for (CallPathStep step : steps) {
            files.add(AffectedFile.builder()
                    .repo(step.getRepo())
                    .path(step.getPath())
                    .lines(String.valueOf(step.getLine()))
                    .role(step.getRole())
                    .build());
        }
        return files;
    }

    private boolean matchesCampaignCh100CmsProxyFailure(
            IncidentRequest request, DownstreamErrorParser.DownstreamError downstreamError) {
        boolean httpServerError = containsIgnoreCase(request, "httpservererrorexception");
        boolean campaignApi = request.getApiPath() != null
                && request.getApiPath().startsWith("/api/v1/campaign-ch-100");
        boolean downstreamCh100Get = downstreamError.hasPath()
                && downstreamError.getPath().contains("ch-100/get");
        return httpServerError && campaignApi && downstreamCh100Get;
    }

    private String extractServiceName(String downstreamPath) {
        if (downstreamPath == null) {
            return null;
        }
        if (downstreamPath.contains("campaign-management-service")) {
            return "campaign-management-service";
        }
        if (downstreamPath.contains("ad-management-service")) {
            return "ad-management-service";
        }
        return null;
    }

    private DiagnosisResponse enrichMetadata(
            DiagnosisResponse response,
            String environment,
            EnvironmentIndexSnapshot indexSnapshot,
            DiagnosisResponse javaDiagnosis) {
        if (response.getEnvironment() == null) {
            response.setEnvironment(environment);
        }
        if (response.getIndexedBranch() == null) {
            response.setIndexedBranch(indexSnapshot.getIndexedBranch());
        }
        if (response.getIndexedCommit() == null) {
            response.setIndexedCommit(indexSnapshot.getIndexedCommit());
        }
        if (response.getDownstreamPath() == null) {
            response.setDownstreamPath(javaDiagnosis.getDownstreamPath());
        }
        if (response.getDownstreamService() == null) {
            response.setDownstreamService(javaDiagnosis.getDownstreamService());
        }
        if (response.getCallPath() == null || response.getCallPath().isEmpty()) {
            response.setCallPath(javaDiagnosis.getCallPath());
        }
        return response;
    }

    private boolean containsIgnoreCase(IncidentRequest request, String value) {
        String haystack = (request.getStackTrace() == null ? "" : request.getStackTrace())
                + (request.getRecentLogs() == null ? "" : request.getRecentLogs())
                + (request.getErrorMessage() == null ? "" : request.getErrorMessage());
        return haystack.toLowerCase().contains(value.toLowerCase());
    }

    private AffectedFile toAffectedFile(CodeSearchResult result, ParseStackTraceTool.StackFrame topFrame) {
        String lines = topFrame != null && topFrame.getLineNumber() != null
                ? String.valueOf(topFrame.getLineNumber())
                : String.valueOf(result.matchLine());
        return AffectedFile.builder()
                .repo(result.repo())
                .path(result.path())
                .lines(lines)
                .build();
    }

    private String buildUserPrompt(IncidentRequest request, List<String> reasoningSteps) {
        StringBuilder builder = new StringBuilder();
        builder.append("Incident details:\n");
        builder.append("service: ").append(request.getService()).append('\n');
        builder.append("environment: ").append(request.getEnvironment()).append('\n');
        builder.append("apiPath: ").append(request.getApiPath()).append('\n');
        builder.append("httpStatus: ").append(request.getHttpStatus()).append('\n');
        builder.append("errorMessage: ").append(request.getErrorMessage()).append('\n');
        builder.append("relatedServices: ").append(request.getRelatedServices()).append('\n');
        builder.append("stackTrace:\n").append(request.getStackTrace()).append('\n');
        if (request.getRecentLogs() != null && !request.getRecentLogs().isBlank()) {
            builder.append("recentLogs:\n")
                    .append(LogRedactor.redact(request.getRecentLogs()))
                    .append('\n');
        }
        builder.append("preliminaryReasoning:\n");
        reasoningSteps.forEach(step -> builder.append("- ").append(step).append('\n'));
        builder.append("\nUse tools to investigate, then return JSON only.");
        return builder.toString();
    }

    private DiagnosisResponse parseDiagnosisResponse(String response) throws JsonProcessingException {
        String json = extractJson(response);
        return objectMapper.readValue(json, DiagnosisResponse.class);
    }

    private String extractJson(String response) {
        if (response == null) {
            return "{}";
        }
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return response;
    }

    private void mergeReasoning(DiagnosisResponse response, List<String> preliminarySteps) {
        List<String> merged = new ArrayList<>(preliminarySteps);
        if (response.getReasoningSteps() != null) {
            merged.addAll(response.getReasoningSteps());
        }
        response.setReasoningSteps(merged);
    }

    private String summarize(String value) {
        if (value == null) {
            return "";
        }
        return value.lines().findFirst().orElse(value);
    }
}
