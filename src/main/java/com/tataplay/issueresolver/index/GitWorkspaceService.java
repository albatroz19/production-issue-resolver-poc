package com.tataplay.issueresolver.index;

import com.tataplay.issueresolver.config.EnvironmentRegistry;
import com.tataplay.issueresolver.config.IssueResolverProperties;
import com.tataplay.issueresolver.model.EnvironmentProfile;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitWorkspaceService {

    private final IssueResolverProperties properties;
    private final EnvironmentRegistry environmentRegistry;

    public GitWorkspaceResult prepareWorkspace(String environment) {
        String normalizedEnvironment = environmentRegistry.normalize(environment);
        EnvironmentProfile profile = environmentRegistry.getProfile(normalizedEnvironment);
        List<String> branches = new ArrayList<>();
        List<String> commits = new ArrayList<>();

        if (!properties.isGitCheckoutEnabled()) {
            log.debug("Git checkout disabled; using current working tree for environment {}", normalizedEnvironment);
            return GitWorkspaceResult.builder()
                    .environment(normalizedEnvironment)
                    .indexedBranch("current")
                    .indexedCommit("local")
                    .build();
        }

        Path reposRoot = Paths.get(properties.getReposRoot());
        for (IssueResolverProperties.RepoConfig repo : properties.getRepos()) {
            String branch = profile.getGitBranches().getOrDefault(repo.getName(), "main");
            Path repoPath = reposRoot.resolve(repo.getPath());
            branches.add(repo.getName() + ":" + branch);
            if (!repoPath.toFile().isDirectory()) {
                log.warn("Repo path not found for git checkout: {}", repoPath);
                commits.add("missing");
                continue;
            }
            checkoutBranch(repoPath, branch);
            commits.add(readShortCommit(repoPath));
        }

        return GitWorkspaceResult.builder()
                .environment(normalizedEnvironment)
                .indexedBranch(branches.stream().collect(Collectors.joining(", ")))
                .indexedCommit(commits.stream().collect(Collectors.joining(", ")))
                .build();
    }

    private void checkoutBranch(Path repoPath, String branch) {
        try {
            runGit(repoPath, "fetch", "origin", branch);
        } catch (Exception ex) {
            log.debug("git fetch skipped for {}: {}", repoPath, ex.getMessage());
        }
        try {
            runGit(repoPath, "checkout", branch);
            log.info("Checked out branch {} in {}", branch, repoPath);
        } catch (Exception ex) {
            log.warn("Unable to checkout branch {} in {}: {}", branch, repoPath, ex.getMessage());
        }
    }

    private String readShortCommit(Path repoPath) {
        try {
            return runGit(repoPath, "rev-parse", "--short", "HEAD").trim();
        } catch (Exception ex) {
            log.warn("Unable to read commit for {}: {}", repoPath, ex.getMessage());
            return "unknown";
        }
    }

    private String runGit(Path repoPath, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repoPath.toString());
        command.addAll(List.of(args));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
        }
        return output;
    }

    @lombok.Value
    @lombok.Builder
    public static class GitWorkspaceResult {
        String environment;
        String indexedBranch;
        String indexedCommit;
    }
}
