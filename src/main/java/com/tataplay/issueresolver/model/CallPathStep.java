package com.tataplay.issueresolver.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallPathStep {

    private String repo;
    private String className;
    private String methodName;
    private String path;
    private int line;
    private CallPathRole role;
    private String snippet;
}
