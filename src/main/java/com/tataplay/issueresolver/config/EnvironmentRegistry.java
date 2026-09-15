package com.tataplay.issueresolver.config;

import com.tataplay.issueresolver.model.EnvironmentProfile;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EnvironmentRegistry {

    private static final String DEFAULT_ENVIRONMENT = "dev";

    private final Map<String, EnvironmentProfile> environments;

    public EnvironmentRegistry(Map<String, EnvironmentProfile> environments) {
        this.environments = environments != null ? environments : new LinkedHashMap<>();
    }

    public EnvironmentProfile getProfile(String environment) {
        String normalized = normalize(environment);
        EnvironmentProfile profile = environments.get(normalized);
        if (profile == null) {
            log.warn("Unknown environment '{}', falling back to {}", environment, DEFAULT_ENVIRONMENT);
            return environments.getOrDefault(DEFAULT_ENVIRONMENT, new EnvironmentProfile());
        }
        return profile;
    }

    public String normalize(String environment) {
        if (environment == null || environment.isBlank()) {
            return DEFAULT_ENVIRONMENT;
        }
        return environment.trim().toLowerCase(Locale.ROOT);
    }
}
