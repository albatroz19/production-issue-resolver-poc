package com.tataplay.issueresolver.index;

import java.util.Optional;

public record IndexedFile(
        String repo,
        String absolutePath,
        String relativePath,
        String className,
        String packageName
) {
}
