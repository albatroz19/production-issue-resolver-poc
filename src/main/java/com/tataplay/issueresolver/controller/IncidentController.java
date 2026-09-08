package com.tataplay.issueresolver.controller;

import com.tataplay.issueresolver.agent.IssueResolverAgent;
import com.tataplay.issueresolver.model.DiagnosisResponse;
import com.tataplay.issueresolver.model.IncidentRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private final IssueResolverAgent issueResolverAgent;

    @PostMapping("/analyze")
    public ResponseEntity<DiagnosisResponse> analyze(@Valid @RequestBody IncidentRequest request) {
        return ResponseEntity.ok(issueResolverAgent.analyze(request));
    }
}
