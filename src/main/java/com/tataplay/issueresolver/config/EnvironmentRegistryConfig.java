package com.tataplay.issueresolver.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tataplay.issueresolver.model.EnvironmentProfile;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

@Configuration
public class EnvironmentRegistryConfig {

    @Bean
    public EnvironmentRegistry environmentRegistry() throws IOException {
        Yaml yaml = new Yaml();
        ClassPathResource resource = new ClassPathResource("environment-registry.yml");
        Map<String, Object> loaded = yaml.load(resource.getInputStream());
        Object environments = loaded.get("environments");
        if (environments == null) {
            return new EnvironmentRegistry(Map.of());
        }
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(environments);
        Map<String, EnvironmentProfile> profileMap = mapper.readValue(
                json,
                mapper.getTypeFactory().constructMapType(Map.class, String.class, EnvironmentProfile.class));
        return new EnvironmentRegistry(profileMap);
    }
}
