package com.tataplay.issueresolver.model;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

@Data
public class EnvironmentProfile {

    private String apiBaseUrl;
    private Map<String, String> gitBranches = new LinkedHashMap<>();
}
