package com.tataplay.issueresolver.model;

import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentRequest {

    @NotBlank
    private String service;

    private String environment;

    private String apiPath;

    private Integer httpStatus;

    private String errorMessage;

    @NotBlank
    private String stackTrace;

    private String recentLogs;

    @Builder.Default
    private List<String> relatedServices = new ArrayList<>();
}
