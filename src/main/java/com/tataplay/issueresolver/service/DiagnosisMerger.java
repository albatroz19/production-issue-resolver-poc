package com.tataplay.issueresolver.service;

import com.tataplay.issueresolver.model.AffectedFile;
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
        DiagnosisResponse preferred = pythonConfidence.ordinal() <= javaConfidence.ordinal()
                ? pythonDiagnosis
                : javaDiagnosis;

        return DiagnosisResponse.builder()
                .rootCause(preferred.getRootCause())
                .confidence(mergedConfidence)
                .affectedFiles(mergeAffectedFiles(javaDiagnosis, pythonDiagnosis, maxFiles))
                .suggestedFix(preferred.getSuggestedFix())
                .reasoningSteps(mergeReasoningSteps(javaDiagnosis, pythonDiagnosis))
                .relatedIncidents(mergeRelatedIncidents(javaDiagnosis, pythonDiagnosis))
                .build();
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
}
