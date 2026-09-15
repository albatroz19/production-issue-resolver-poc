package com.tataplay.issueresolver.index;

import com.tataplay.issueresolver.config.IssueResolverProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RankedCodeSearchService {

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "public", "private", "protected", "class", "interface", "return", "void", "new", "import", "package",
            "static", "final", "throws", "throw", "if", "else", "for", "while", "try", "catch", "this", "null");

    private static final Pattern CAMEL_CASE_SPLIT = Pattern.compile("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    private final IssueResolverProperties properties;

    public List<CodeSearchResult> search(EnvironmentIndexSnapshot snapshot, String query, String repoFilter) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        List<ScoredResult> scored = new ArrayList<>();
        int totalFiles = snapshot.getAllFiles().size();

        for (IndexedFile file : snapshot.getAllFiles()) {
            if (repoFilter != null && !repoFilter.isBlank()
                    && !file.repo().equalsIgnoreCase(repoFilter)) {
                continue;
            }
            try {
                List<String> lines = Files.readAllLines(Paths.get(file.absolutePath()), StandardCharsets.UTF_8);
                double score = scoreFile(file, lines, queryTokens, totalFiles);
                if (score <= 0) {
                    continue;
                }
                int matchLine = findBestLine(lines, queryTokens);
                scored.add(new ScoredResult(
                        new CodeSearchResult(
                                file.repo(),
                                file.relativePath(),
                                file.className(),
                                matchLine,
                                extractSnippet(lines, matchLine, properties.getMaxSnippetLines())),
                        score));
            } catch (IOException ignored) {
                // skip unreadable file
            }
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredResult::score).reversed())
                .limit(properties.getMaxFilesPerRequest() * 3)
                .map(ScoredResult::result)
                .collect(Collectors.toList());
    }

    private double scoreFile(IndexedFile file, List<String> lines, List<String> queryTokens, int totalFiles) {
        String content = String.join("\n", lines).toLowerCase(Locale.ROOT);
        List<String> fileTokens = tokenize(content);
        double score = 0;

        for (String token : queryTokens) {
            if (file.className().equalsIgnoreCase(token)) {
                score += 10;
            }
            if (file.className().toLowerCase(Locale.ROOT).contains(token)) {
                score += 4;
            }
            if (content.contains(token)) {
                score += 1 + Math.log1p(countOccurrences(content, token));
            }
            if (fileTokens.contains(token)) {
                score += 2;
            }
            if (token.contains("/") && content.contains(token)) {
                score += 3;
            }
        }

        if (score > 0) {
            score += 2.0 / Math.max(1, Math.log1p(totalFiles));
        }
        return score;
    }

    private int findBestLine(List<String> lines, List<String> queryTokens) {
        int bestLine = 1;
        int bestScore = 0;
        for (int i = 0; i < lines.size(); i++) {
            String lower = lines.get(i).toLowerCase(Locale.ROOT);
            int lineScore = 0;
            for (String token : queryTokens) {
                if (lower.contains(token)) {
                    lineScore++;
                }
            }
            if (lineScore > bestScore) {
                bestScore = lineScore;
                bestLine = i + 1;
            }
        }
        return bestLine;
    }

    private List<String> tokenize(String value) {
        List<String> tokens = new ArrayList<>();
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/_\\-]+", " ");
        for (String part : normalized.split("\\s+")) {
            if (part.isBlank() || JAVA_KEYWORDS.contains(part)) {
                continue;
            }
            tokens.add(part);
            for (String camelPart : CAMEL_CASE_SPLIT.split(part)) {
                if (!camelPart.isBlank() && !JAVA_KEYWORDS.contains(camelPart.toLowerCase(Locale.ROOT))) {
                    tokens.add(camelPart.toLowerCase(Locale.ROOT));
                }
            }
        }
        return tokens.stream().distinct().collect(Collectors.toList());
    }

    private int countOccurrences(String content, String token) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    private String extractSnippet(List<String> lines, int centerLine, int maxLines) {
        if (lines.isEmpty()) {
            return "";
        }
        int half = maxLines / 2;
        int start = Math.max(1, centerLine - half);
        int end = Math.min(lines.size(), start + maxLines - 1);
        start = Math.max(1, end - maxLines + 1);

        StringBuilder builder = new StringBuilder();
        for (int lineNumber = start; lineNumber <= end; lineNumber++) {
            builder.append(String.format("%4d| %s%n", lineNumber, lines.get(lineNumber - 1)));
        }
        return builder.toString().trim();
    }

    private record ScoredResult(CodeSearchResult result, double score) {}
}
