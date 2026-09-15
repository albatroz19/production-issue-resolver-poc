package com.tataplay.issueresolver.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DownstreamErrorParser {

    private static final Pattern JSON_FRAGMENT_PATTERN = Pattern.compile("\\{[^{}]+\\}");
    private static final Pattern PATH_PATTERN = Pattern.compile(
            "\"path\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE_PATTERN = Pattern.compile(
            "\"code\"\\s*:\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            "\"timestamp\"\\s*:\\s*\"?([0-9]+)\"?",
            Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;

    public DownstreamError parse(String... sources) {
        DownstreamError.DownstreamErrorBuilder builder = DownstreamError.builder();
        for (String source : sources) {
            if (source == null || source.isBlank()) {
                continue;
            }
            merge(builder, parseSource(source));
        }
        return builder.build();
    }

    private DownstreamError parseSource(String source) {
        DownstreamError fromJsonFragments = parseJsonFragments(source);
        if (fromJsonFragments.hasPath()) {
            return fromJsonFragments;
        }

        DownstreamError.DownstreamErrorBuilder builder = DownstreamError.builder();
        applyRegex(builder, source);
        return builder.build();
    }

    private DownstreamError parseJsonFragments(String source) {
        DownstreamError.DownstreamErrorBuilder builder = DownstreamError.builder();
        Matcher matcher = JSON_FRAGMENT_PATTERN.matcher(source);
        while (matcher.find()) {
            String fragment = matcher.group();
            try {
                JsonNode node = objectMapper.readTree(fragment);
                if (node.has("path")) {
                    builder.path(node.get("path").asText());
                }
                if (node.has("code")) {
                    builder.code(node.get("code").asInt());
                }
                if (node.has("timestamp")) {
                    builder.timestamp(node.get("timestamp").asText());
                }
            } catch (Exception ignored) {
                applyRegex(builder, fragment);
            }
        }
        return builder.build();
    }

    private void applyRegex(DownstreamError.DownstreamErrorBuilder builder, String source) {
        Matcher pathMatcher = PATH_PATTERN.matcher(source);
        if (pathMatcher.find()) {
            builder.path(pathMatcher.group(1));
        }
        Matcher codeMatcher = CODE_PATTERN.matcher(source);
        if (codeMatcher.find()) {
            builder.code(Integer.parseInt(codeMatcher.group(1)));
        }
        Matcher timestampMatcher = TIMESTAMP_PATTERN.matcher(source);
        if (timestampMatcher.find()) {
            builder.timestamp(timestampMatcher.group(1));
        }
    }

    private void merge(DownstreamError.DownstreamErrorBuilder target, DownstreamError parsed) {
        if (parsed.getPath() != null && !parsed.getPath().isBlank()) {
            target.path(parsed.getPath());
        }
        if (parsed.getCode() != null) {
            target.code(parsed.getCode());
        }
        if (parsed.getTimestamp() != null && !parsed.getTimestamp().isBlank()) {
            target.timestamp(parsed.getTimestamp());
        }
    }

    @Data
    @Builder
    public static class DownstreamError {
        private String path;
        private Integer code;
        private String timestamp;

        public boolean hasPath() {
            return path != null && !path.isBlank();
        }
    }
}
