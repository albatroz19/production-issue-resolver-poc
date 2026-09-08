package com.tataplay.issueresolver.config;

import lombok.Data;

@Data
public class PythonAgentProperties {

    private boolean enabled = true;
    private String baseUrl = "http://localhost:8098";
    private String path = "/api/v1/incidents/analyze/llm";
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 60000;
}
