package com.tataplay.issueresolver.index;

import com.tataplay.issueresolver.config.IssueResolverProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SimpleCodeIndexService {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "(?:public\\s+)?(?:class|interface|enum|record)\\s+(\\w+)");

    private final IssueResolverProperties properties;
    private final Map<String, IndexedFile> classNameIndex = new HashMap<>();
    private final List<IndexedFile> allFiles = new ArrayList<>();

    public SimpleCodeIndexService(IssueResolverProperties properties) {
        this.properties = properties;
        buildIndex();
    }

    public Optional<IndexedFile> findByClassName(String className) {
        if (className == null || className.isBlank()) {
            return Optional.empty();
        }
        String simpleName = className.contains(".")
                ? className.substring(className.lastIndexOf('.') + 1)
                : className;
        return Optional.ofNullable(classNameIndex.get(simpleName));
    }

    public List<CodeSearchResult> searchCodebase(String query, String repoFilter) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        List<CodeSearchResult> results = new ArrayList<>();

        for (IndexedFile file : allFiles) {
            if (repoFilter != null && !repoFilter.isBlank()
                    && !file.repo().equalsIgnoreCase(repoFilter)) {
                continue;
            }
            try {
                List<String> lines = Files.readAllLines(Paths.get(file.absolutePath()), StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                        results.add(new CodeSearchResult(
                                file.repo(),
                                file.relativePath(),
                                file.className(),
                                i + 1,
                                extractSnippet(lines, i + 1, properties.getMaxSnippetLines())));
                        break;
                    }
                }
            } catch (IOException ex) {
                log.debug("Unable to read file {}: {}", file.absolutePath(), ex.getMessage());
            }
        }

        return results.stream()
                .sorted(Comparator.comparingInt(CodeSearchResult::matchLine))
                .limit(properties.getMaxFilesPerRequest() * 3)
                .collect(Collectors.toList());
    }

    public List<CodeSearchResult> findExceptionHandlers(String serviceName) {
        List<String> patterns = List.of(
                "@restcontrolleradvice",
                "@controlleradvice",
                "extends responseentityexceptionhandler");
        List<CodeSearchResult> results = new ArrayList<>();

        for (IndexedFile file : allFiles) {
            if (serviceName != null && !serviceName.isBlank()
                    && !file.repo().equalsIgnoreCase(serviceName)) {
                continue;
            }
            try {
                String content = Files.readString(Paths.get(file.absolutePath()), StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT);
                boolean matches = patterns.stream().anyMatch(content::contains);
                if (matches) {
                    List<String> lines = Files.readAllLines(Paths.get(file.absolutePath()), StandardCharsets.UTF_8);
                    int matchLine = findFirstMatchingLine(lines, patterns);
                    results.add(new CodeSearchResult(
                            file.repo(),
                            file.relativePath(),
                            file.className(),
                            matchLine,
                            extractSnippet(lines, matchLine, properties.getMaxSnippetLines())));
                }
            } catch (IOException ex) {
                log.debug("Unable to read file {}: {}", file.absolutePath(), ex.getMessage());
            }
        }

        return results.stream().limit(properties.getMaxFilesPerRequest()).collect(Collectors.toList());
    }

    public String readSnippet(String repo, String relativePath, int centerLine) {
        Optional<IndexedFile> file = allFiles.stream()
                .filter(f -> f.repo().equals(repo) && f.relativePath().equals(relativePath))
                .findFirst();
        if (file.isEmpty()) {
            return "";
        }
        try {
            List<String> lines = Files.readAllLines(Paths.get(file.get().absolutePath()), StandardCharsets.UTF_8);
            return extractSnippet(lines, centerLine, properties.getMaxSnippetLines());
        } catch (IOException ex) {
            log.debug("Unable to read snippet for {}: {}", relativePath, ex.getMessage());
            return "";
        }
    }

    public int indexedFileCount() {
        return allFiles.size();
    }

    private void buildIndex() {
        Path reposRoot = Paths.get(properties.getReposRoot());
        for (IssueResolverProperties.RepoConfig repo : properties.getRepos()) {
            Path repoPath = reposRoot.resolve(repo.getPath());
            if (!Files.isDirectory(repoPath)) {
                log.warn("Repo path not found, skipping index: {}", repoPath);
                continue;
            }
            Path javaRoot = repoPath.resolve("src/main/java");
            if (!Files.isDirectory(javaRoot)) {
                log.warn("Java source root not found for repo {}: {}", repo.getName(), javaRoot);
                continue;
            }
            try (Stream<Path> paths = Files.walk(javaRoot)) {
                paths.filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> indexFile(repo.getName(), repoPath, javaRoot, path));
            } catch (IOException ex) {
                log.warn("Failed to walk repo {}: {}", repo.getName(), ex.getMessage());
            }
        }
        log.info("Code index built with {} Java files across {} repos",
                allFiles.size(), properties.getRepos().size());
    }

    private void indexFile(String repoName, Path repoPath, Path javaRoot, Path filePath) {
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            String packageName = extractPackage(content);
            String className = extractClassName(content, filePath);
            String relativePath = javaRoot.relativize(filePath).toString();
            IndexedFile indexedFile = new IndexedFile(
                    repoName,
                    filePath.toString(),
                    relativePath,
                    className,
                    packageName);
            allFiles.add(indexedFile);
            classNameIndex.putIfAbsent(className, indexedFile);
        } catch (IOException ex) {
            log.debug("Failed to index {}: {}", filePath, ex.getMessage());
        }
    }

    private String extractPackage(String content) {
        Matcher matcher = PACKAGE_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String extractClassName(String content, Path filePath) {
        Matcher matcher = CLASS_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        String fileName = filePath.getFileName().toString();
        return fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }

    private int findFirstMatchingLine(List<String> lines, List<String> patterns) {
        for (int i = 0; i < lines.size(); i++) {
            String lower = lines.get(i).toLowerCase(Locale.ROOT);
            for (String pattern : patterns) {
                if (lower.contains(pattern)) {
                    return i + 1;
                }
            }
        }
        return 1;
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
}
