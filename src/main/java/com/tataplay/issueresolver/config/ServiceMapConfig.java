package com.tataplay.issueresolver.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tataplay.issueresolver.model.DiagnosisResponse;
import com.tataplay.issueresolver.tool.ServiceDependencyTool;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

@Configuration
public class ServiceMapConfig {

    @Bean
    @ConfigurationProperties(prefix = "services")
    public ServiceDependencyTool serviceDependencyTool() throws IOException {
        ServiceDependencyTool tool = new ServiceDependencyTool();
        Yaml yaml = new Yaml();
        ClassPathResource resource = new ClassPathResource("service-map.yml");
        Map<String, Object> loaded = yaml.load(resource.getInputStream());
        Object services = loaded.get("services");
        if (services != null) {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(services);
            Map<String, ServiceDependencyTool.ServiceDefinition> serviceMap = mapper.readValue(
                    json,
                    mapper.getTypeFactory().constructMapType(
                            Map.class, String.class, ServiceDependencyTool.ServiceDefinition.class));
            tool.setServices(serviceMap);
        }
        return tool;
    }
}
