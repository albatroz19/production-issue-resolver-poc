package com.tataplay.issueresolver.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DownstreamErrorParserTest {

    private final DownstreamErrorParser parser = new DownstreamErrorParser(new ObjectMapper());

    @Test
    void parse_extractsEmbeddedJsonFromLogLine() {
        String source = """
                500 : "{"code":500,"path":"/campaign-management-service/api/v1/campaign-management/ch-100/get","timestamp":"1789453997041"}"
                """;

        DownstreamErrorParser.DownstreamError error = parser.parse(source);

        assertThat(error.getPath()).contains("ch-100/get");
        assertThat(error.getCode()).isEqualTo(500);
        assertThat(error.getTimestamp()).isEqualTo("1789453997041");
    }
}
