package com.tataplay.issueresolver.util;

import java.util.regex.Pattern;

public final class LogRedactor {

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?i)(authorization|api[_-]?key|token|cookie|bearer)\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern JWT_PATTERN = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    private LogRedactor() {
    }

    public static String redact(String logs) {
        if (logs == null || logs.isBlank()) {
            return logs;
        }
        String redacted = TOKEN_PATTERN.matcher(logs).replaceAll("$1=[REDACTED]");
        return JWT_PATTERN.matcher(redacted).replaceAll("[REDACTED_JWT]");
    }
}
