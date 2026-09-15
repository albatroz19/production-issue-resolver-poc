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
        assertThat(parsed.getApplicationTopFrame().getClassName()).contains("RestTemplateUtility");
        assertThat(parsed.getTopFrame().getMethodName()).isEqualTo("extractErrorMessage");
        assertThat(parsed.getTopFrame().getLineNumber()).isEqualTo(48);
        assertThat(parsed.getCausedByChain()).isNotEmpty();
    }

    @Test
    void parseStackTrace_skipsFrameworkFramesForApplicationTopFrame() {
        String stackTrace = """
                org.springframework.web.client.HttpServerErrorException$InternalServerError: 500
                    at org.springframework.web.client.HttpServerErrorException.create(HttpServerErrorException.java:102)
                    at com.tataplay.admanagement.module.ch100.campaign.service.impl.CampaignChHundredServiceImpl.getCampaign(CampaignChHundredServiceImpl.java:535)
                """;

        ParseStackTraceTool.ParsedStackTrace parsed = tool.parseStackTrace(stackTrace);

        assertThat(parsed.getTopFrame().getClassName()).contains("HttpServerErrorException");
        assertThat(parsed.getApplicationTopFrame().getClassName())
                .isEqualTo("com.tataplay.admanagement.module.ch100.campaign.service.impl.CampaignChHundredServiceImpl");
    }
}
