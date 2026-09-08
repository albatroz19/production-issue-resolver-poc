package com.tataplay.issueresolver.index;

import java.util.List;
import java.util.Optional;

public record CodeSearchResult(
        String repo,
        String path,
        String className,
        int matchLine,
        String snippet
) {
}
