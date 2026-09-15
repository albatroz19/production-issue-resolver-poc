package com.tataplay.issueresolver.index;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class EnvironmentIndexSnapshot {

    String environment;
    String indexedBranch;
    String indexedCommit;
    Map<String, IndexedFile> classNameIndex;
    List<IndexedFile> allFiles;
}
