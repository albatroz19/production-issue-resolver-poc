package com.tataplay.issueresolver.model;

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
public class DiagnosisResponse {

    private String rootCause;
    private ConfidenceLevel confidence;
    @Builder.Default
    private List<AffectedFile> affectedFiles = new ArrayList<>();
    private String suggestedFix;
    @Builder.Default
    private List<String> reasoningSteps = new ArrayList<>();
    @Builder.Default
    private List<String> relatedIncidents = new ArrayList<>();
    private String environment;
    private String indexedBranch;
    private String indexedCommit;
    private String downstreamService;
    private String downstreamPath;
    @Builder.Default
    private List<CallPathStep> callPath = new ArrayList<>();
}
