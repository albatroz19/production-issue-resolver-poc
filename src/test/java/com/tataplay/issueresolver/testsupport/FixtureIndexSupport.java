package com.tataplay.issueresolver.testsupport;

import com.tataplay.issueresolver.index.EnvironmentIndexSnapshot;
import com.tataplay.issueresolver.index.IndexedFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class FixtureIndexSupport {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "(?:public\\s+)?(?:class|interface|enum|record)\\s+(\\w+)");

    private FixtureIndexSupport() {
    }

    public static Path fixturesRoot() {
        return Paths.get("src/test/resources/fixtures").toAbsolutePath().normalize();
    }

    public static EnvironmentIndexSnapshot buildSnapshot() throws IOException {
        Path root = fixturesRoot();
        Map<String, IndexedFile> classNameIndex = new HashMap<>();
        List<IndexedFile> allFiles = new ArrayList<>();

        indexRepo(root, "ad-management-service", classNameIndex, allFiles);
        indexRepo(root, "campaign-management-service", classNameIndex, allFiles);

        return EnvironmentIndexSnapshot.builder()
                .environment("dev")
                .indexedBranch("fixtures")
                .indexedCommit("fixture")
                .classNameIndex(classNameIndex)
                .allFiles(allFiles)
                .build();
    }

    private static void indexRepo(
            Path root,
            String repoName,
            Map<String, IndexedFile> classNameIndex,
            List<IndexedFile> allFiles) throws IOException {
        Path javaRoot = root.resolve(repoName).resolve("src/main/java");
        if (!Files.isDirectory(javaRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(javaRoot)) {
            paths.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    Matcher packageMatcher = PACKAGE_PATTERN.matcher(content);
                    Matcher classMatcher = CLASS_PATTERN.matcher(content);
                    String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
                    String className = classMatcher.find() ? classMatcher.group(1) : path.getFileName().toString().replace(".java", "");
                    IndexedFile indexed = new IndexedFile(
                            repoName,
                            path.toString(),
                            javaRoot.relativize(path).toString(),
                            className,
                            packageName);
                    allFiles.add(indexed);
                    classNameIndex.put(className, indexed);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
        }
    }
}
