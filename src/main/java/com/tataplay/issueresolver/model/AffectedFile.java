package com.tataplay.issueresolver.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AffectedFile {

    private String repo;
    private String path;
    private String lines;
}
