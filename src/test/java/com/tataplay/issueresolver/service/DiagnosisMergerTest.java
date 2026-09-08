package com.tataplay.issueresolver.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tataplay.issueresolver.model.AffectedFile;
import com.tataplay.issueresolver.model.ConfidenceLevel;
import com.tataplay.issueresolver.model.DiagnosisResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiagnosisMergerTest {

    private final DiagnosisMerger merger = new DiagnosisMerger();

    @Test
    void merge_prefersHigherConfidenceAndPythonOnTie() {
        DiagnosisResponse javaDiagnosis = DiagnosisResponse.builder()
                .rootCause("Java root cause")
                .confidence(ConfidenceLevel.MEDIUM)
                .suggestedFix("Java fix")
                .affectedFiles(List.of(file("ad-management-service", "A.java", "10")))
                .reasoningSteps(List.of("java step"))
                .relatedIncidents(List.of("incident-a"))
                .build();

        DiagnosisResponse pythonDiagnosis = DiagnosisResponse.builder()
                .rootCause("Python root cause")
                .confidence(ConfidenceLevel.MEDIUM)
                .suggestedFix("Python fix")
                .affectedFiles(List.of(file("campaign-management-service", "B.java", "20")))
                .reasoningSteps(List.of("python step"))
                .relatedIncidents(List.of("incident-b"))
                .build();

        DiagnosisResponse merged = merger.merge(javaDiagnosis, pythonDiagnosis, 3);

        assertThat(merged.getRootCause()).isEqualTo("Python root cause");
        assertThat(merged.getSuggestedFix()).isEqualTo("Python fix");
        assertThat(merged.getConfidence()).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(merged.getAffectedFiles()).hasSize(2);
        assertThat(merged.getReasoningSteps()).containsExactly("[java] java step", "[python] python step");
        assertThat(merged.getRelatedIncidents()).containsExactlyInAnyOrder("incident-a", "incident-b");
    }

    @Test
    void merge_usesMaxConfidenceAcrossSources() {
        DiagnosisResponse javaDiagnosis = DiagnosisResponse.builder()
                .rootCause("Java root cause")
                .confidence(ConfidenceLevel.HIGH)
                .suggestedFix("Java fix")
                .reasoningSteps(List.of("java step"))
                .build();

        DiagnosisResponse pythonDiagnosis = DiagnosisResponse.builder()
                .rootCause("Python root cause")
                .confidence(ConfidenceLevel.LOW)
                .suggestedFix("Python fix")
                .reasoningSteps(List.of("python step"))
                .build();

        DiagnosisResponse merged = merger.merge(javaDiagnosis, pythonDiagnosis, 3);

        assertThat(merged.getConfidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(merged.getRootCause()).isEqualTo("Java root cause");
    }

    @Test
    void merge_deduplicatesAffectedFilesByRepoAndPath() {
        DiagnosisResponse javaDiagnosis = DiagnosisResponse.builder()
                .rootCause("Java")
                .confidence(ConfidenceLevel.LOW)
                .suggestedFix("Java fix")
                .affectedFiles(List.of(file("ad-management-service", "Same.java", "1")))
                .reasoningSteps(List.of())
                .build();

        DiagnosisResponse pythonDiagnosis = DiagnosisResponse.builder()
                .rootCause("Python")
                .confidence(ConfidenceLevel.LOW)
                .suggestedFix("Python fix")
                .affectedFiles(List.of(
                        file("ad-management-service", "Same.java", "2"),
                        file("campaign-management-service", "Other.java", "3")))
                .reasoningSteps(List.of())
                .build();

        DiagnosisResponse merged = merger.merge(javaDiagnosis, pythonDiagnosis, 3);

        assertThat(merged.getAffectedFiles()).hasSize(2);
        assertThat(merged.getAffectedFiles().get(0).getPath()).isEqualTo("Same.java");
    }

    private static AffectedFile file(String repo, String path, String lines) {
        return AffectedFile.builder().repo(repo).path(path).lines(lines).build();
    }
}
