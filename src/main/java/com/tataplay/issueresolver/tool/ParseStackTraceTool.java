package com.tataplay.issueresolver.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Component;

@Component
public class ParseStackTraceTool {

    private static final Pattern FRAME_PATTERN = Pattern.compile(
            "at\\s+([\\w.$]+)\\.([\\w$]+)\\(([^:)]+)(?::(\\d+))?\\)");
    private static final Pattern EXCEPTION_PATTERN = Pattern.compile("^([\\w.$]+(?:Exception|Error)):\\s*(.*)$");

    public ParsedStackTrace parseStackTrace(String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return ParsedStackTrace.builder().build();
        }

        List<String> lines = stackTrace.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<StackFrame> frames = new ArrayList<>();
        List<String> causedByChain = new ArrayList<>();
        String exceptionType = null;
        String exceptionMessage = null;

        for (String line : lines) {
            Matcher frameMatcher = FRAME_PATTERN.matcher(line);
            if (frameMatcher.find()) {
                frames.add(StackFrame.builder()
                        .className(frameMatcher.group(1))
                        .methodName(frameMatcher.group(2))
                        .fileName(frameMatcher.group(3))
                        .lineNumber(frameMatcher.group(4) != null ? Integer.parseInt(frameMatcher.group(4)) : null)
                        .build());
                continue;
            }

            Matcher exceptionMatcher = EXCEPTION_PATTERN.matcher(line);
            if (exceptionMatcher.find()) {
                if (exceptionType == null) {
                    exceptionType = exceptionMatcher.group(1);
                    exceptionMessage = exceptionMatcher.group(2);
                }
                continue;
            }

            if (line.startsWith("Caused by:")) {
                causedByChain.add(line.substring("Caused by:".length()).trim());
            }
        }

        if (exceptionType == null && !lines.isEmpty()) {
            String firstLine = lines.get(0);
            int colonIndex = firstLine.indexOf(':');
            exceptionType = colonIndex > 0 ? firstLine.substring(0, colonIndex).trim() : firstLine;
            if (colonIndex > 0 && colonIndex + 1 < firstLine.length()) {
                exceptionMessage = firstLine.substring(colonIndex + 1).trim();
            }
        }

        StackFrame topFrame = frames.isEmpty() ? null : frames.get(0);

        return ParsedStackTrace.builder()
                .exceptionType(exceptionType)
                .exceptionMessage(exceptionMessage)
                .topFrame(topFrame)
                .frames(frames)
                .causedByChain(causedByChain)
                .build();
    }

    @Data
    @Builder
    public static class ParsedStackTrace {
        private String exceptionType;
        private String exceptionMessage;
        private StackFrame topFrame;
        @Builder.Default
        private List<StackFrame> frames = new ArrayList<>();
        @Builder.Default
        private List<String> causedByChain = new ArrayList<>();
    }

    @Data
    @Builder
    public static class StackFrame {
        private String className;
        private String methodName;
        private String fileName;
        private Integer lineNumber;
    }
}
