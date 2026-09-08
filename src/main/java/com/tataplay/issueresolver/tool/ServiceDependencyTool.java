package com.tataplay.issueresolver.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.ai.tool.annotation.Tool;

public class ServiceDependencyTool {

    private Map<String, ServiceDefinition> services = new LinkedHashMap<>();

    @Tool(description = "Return downstream service dependencies and API path mappings for a given service "
            + "and optional API path. Use this to trace cross-service failures.")
    public String getServiceDependencies(String serviceName, String apiPath) {
        ServiceDefinition service = services.get(serviceName);
        if (service == null) {
            return "No service mapping found for: " + serviceName;
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Service: ").append(serviceName).append('\n');
        if (service.getDescription() != null) {
            builder.append("Description: ").append(service.getDescription()).append('\n');
        }
        builder.append("Dependencies: ").append(service.getDependencies()).append('\n');

        if (apiPath != null && !apiPath.isBlank() && service.getApiMappings() != null) {
            ApiMapping mapping = findBestMapping(service.getApiMappings(), apiPath);
            if (mapping != null) {
                builder.append("API mapping for ").append(apiPath).append(":\n");
                builder.append("  targetService: ").append(mapping.getTargetService()).append('\n');
                builder.append("  targetPath: ").append(mapping.getTargetPath()).append('\n');
                if (mapping.getDescription() != null) {
                    builder.append("  description: ").append(mapping.getDescription()).append('\n');
                }
            } else {
                builder.append("No explicit API mapping found for path: ").append(apiPath).append('\n');
            }
        }

        return builder.toString().trim();
    }

    public Map<String, ServiceDefinition> getServices() {
        return services;
    }

    public void setServices(Map<String, ServiceDefinition> services) {
        this.services = services;
    }

    private ApiMapping findBestMapping(Map<String, ApiMapping> mappings, String apiPath) {
        if (mappings.containsKey(apiPath)) {
            return mappings.get(apiPath);
        }
        return mappings.entrySet().stream()
                .filter(entry -> apiPath.startsWith(entry.getKey()) || entry.getKey().startsWith(apiPath))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    @Data
    public static class ServiceDefinition {
        private String description;
        private List<String> dependencies = new ArrayList<>();
        private Map<String, ApiMapping> apiMappings = new LinkedHashMap<>();
    }

    @Data
    public static class ApiMapping {
        private String targetService;
        private String targetPath;
        private String description;
    }
}
