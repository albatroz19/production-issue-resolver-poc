package com.tataplay.issueresolver.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ParseStackTraceToolTest {

    private final ParseStackTraceTool tool = new ParseStackTraceTool();

    @Test
    void parseStackTrace_extractsTopFrameAndException() {
        String stackTrace = """
                java.lang.NullPointerException: Cannot invoke "com.fasterxml.jackson.databind.JsonNode.asText()" because the return value is null
                    at com.tataplay.admanagement.utility.RestTemplateUtility.extractErrorMessage(RestTemplateUtility.java:48)
                    at com.tataplay.admanagement.utility.RestTemplateUtility.handleError(RestTemplateUtility.java:32)
                Caused by: org.springframework.web.client.HttpClientErrorException$BadRequest: 400 Bad Request
                """;

        ParseStackTraceTool.ParsedStackTrace parsed = tool.parseStackTrace(stackTrace);

        assertThat(parsed.getExceptionType()).isEqualTo("java.lang.NullPointerException");
        assertThat(parsed.getTopFrame().getClassName()).contains("RestTemplateUtility");
        assertThat(parsed.getTopFrame().getMethodName()).isEqualTo("extractErrorMessage");
        assertThat(parsed.getTopFrame().getLineNumber()).isEqualTo(48);
        assertThat(parsed.getCausedByChain()).isNotEmpty();
    }
}
