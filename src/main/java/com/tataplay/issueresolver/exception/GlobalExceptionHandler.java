package com.tataplay.issueresolver.exception;

import com.tataplay.issueresolver.client.PythonAgentException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PythonAgentException.class)
    public ResponseEntity<Map<String, String>> handlePythonAgentException(PythonAgentException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("detail", "Python agent unavailable"));
    }
}
