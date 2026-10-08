package com.tataplay.issueresolver.service;

import com.tataplay.issueresolver.model.AffectedFile;
import com.tataplay.issueresolver.model.CallPathStep;
import com.tataplay.issueresolver.model.ConfidenceLevel;
import com.tataplay.issueresolver.model.DiagnosisResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class DiagnosisMerger {

    public DiagnosisResponse merge(DiagnosisResponse javaDiagnosis, DiagnosisResponse pythonDiagnosis, int maxFiles) {
        ConfidenceLevel javaConfidence = javaDiagnosis.getConfidence() != null
                ? javaDiagnosis.getConfidence()
                : ConfidenceLevel.LOW;
        ConfidenceLevel pythonConfidence = pythonDiagnosis.getConfidence() != null
                ? pythonDiagnosis.getConfidence()
                : ConfidenceLevel.LOW;

        ConfidenceLevel mergedConfidence = maxConfidence(javaConfidence, pythonConfidence);
        DiagnosisResponse preferred = preferJavaDiagnosis(javaConfidence, pythonConfidence)
                ? javaDiagnosis
                : pythonDiagnosis;

        return DiagnosisResponse.builder()
                .rootCause(preferred.getRootCause())
                .confidence(mergedConfidence)
                .affectedFiles(mergeAffectedFiles(javaDiagnosis, pythonDiagnosis, maxFiles))
                .suggestedFix(preferred.getSuggestedFix())
                .reasoningSteps(mergeReasoningSteps(javaDiagnosis, pythonDiagnosis))
                .relatedIncidents(mergeRelatedIncidents(javaDiagnosis, pythonDiagnosis))
                .environment(firstNonBlank(javaDiagnosis.getEnvironment(), pythonDiagnosis.getEnvironment()))
                .indexedBranch(firstNonBlank(javaDiagnosis.getIndexedBranch(), pythonDiagnosis.getIndexedBranch()))
                .indexedCommit(firstNonBlank(javaDiagnosis.getIndexedCommit(), pythonDiagnosis.getIndexedCommit()))
                .downstreamService(firstNonBlank(javaDiagnosis.getDownstreamService(), pythonDiagnosis.getDownstreamService()))
                .downstreamPath(firstNonBlank(javaDiagnosis.getDownstreamPath(), pythonDiagnosis.getDownstreamPath()))
                .callPath(mergeCallPath(javaDiagnosis, pythonDiagnosis))
                .build();
    }

    private boolean preferJavaDiagnosis(ConfidenceLevel javaConfidence, ConfidenceLevel pythonConfidence) {
        if (javaConfidence == ConfidenceLevel.HIGH && pythonConfidence == ConfidenceLevel.HIGH) {
            return true;
        }
        return javaConfidence.ordinal() < pythonConfidence.ordinal();
    }

    private ConfidenceLevel maxConfidence(ConfidenceLevel left, ConfidenceLevel right) {
        return left.ordinal() <= right.ordinal() ? left : right;
    }

    private List<AffectedFile> mergeAffectedFiles(
            DiagnosisResponse javaDiagnosis, DiagnosisResponse pythonDiagnosis, int maxFiles) {
        Map<String, AffectedFile> merged = new LinkedHashMap<>();
        addAffectedFiles(merged, javaDiagnosis.getAffectedFiles());
        addAffectedFiles(merged, pythonDiagnosis.getAffectedFiles());
        return merged.values().stream().limit(maxFiles).toList();
    }

    private void addAffectedFiles(Map<String, AffectedFile> merged, List<AffectedFile> files) {
        if (files == null) {
            return;
        }
        for (AffectedFile file : files) {
            String key = file.getRepo() + "|" + file.getPath();
            merged.putIfAbsent(key, file);
        }
    }

    private List<String> mergeReasoningSteps(DiagnosisResponse javaDiagnosis, DiagnosisResponse pythonDiagnosis) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> merged = new ArrayList<>();
        addTaggedSteps(merged, seen, "java", javaDiagnosis.getReasoningSteps());
        addTaggedSteps(merged, seen, "python", pythonDiagnosis.getReasoningSteps());
        return merged;
    }

    private void addTaggedSteps(List<String> merged, Set<String> seen, String source, List<String> steps) {
        if (steps == null) {
            return;
        }
        for (String step : steps) {
            String tagged = "[" + source + "] " + step;
            if (seen.add(tagged)) {
                merged.add(tagged);
            }
        }
    }

    private List<String> mergeRelatedIncidents(DiagnosisResponse javaDiagnosis, DiagnosisResponse pythonDiagnosis) {
        Set<String> merged = new LinkedHashSet<>();
        if (javaDiagnosis.getRelatedIncidents() != null) {
            merged.addAll(javaDiagnosis.getRelatedIncidents());
        }
        if (pythonDiagnosis.getRelatedIncidents() != null) {
            merged.addAll(pythonDiagnosis.getRelatedIncidents());
        }
        return new ArrayList<>(merged);
    }

    private String firstNonBlank(String left, String right) {
        if (left != null && !left.isBlank()) {
            return left;
        }
        return right;
    }

    private List<CallPathStep> mergeCallPath(DiagnosisResponse javaDiagnosis, DiagnosisResponse pythonDiagnosis) {
        if (javaDiagnosis.getCallPath() != null && !javaDiagnosis.getCallPath().isEmpty()) {
            return javaDiagnosis.getCallPath();
        }
        return pythonDiagnosis.getCallPath() != null ? pythonDiagnosis.getCallPath() : List.of();
    }
}
