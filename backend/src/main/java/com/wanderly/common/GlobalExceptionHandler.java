package com.wanderly.common;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain errors to RFC 7807 problem details. Validation errors are already rendered as
 * problem details by Spring (spring.mvc.problemdetails.enabled).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handle(ApiException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        if (ex.code() != null) {
            problem.setProperty("code", ex.code());
        }
        return ResponseEntity.status(ex.status()).body(problem);
    }
}
