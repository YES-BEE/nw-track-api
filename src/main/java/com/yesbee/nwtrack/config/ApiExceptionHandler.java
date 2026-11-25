package com.yesbee.nwtrack.config;

import com.yesbee.nwtrack.shared.BusinessRuleException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException ex) {
        ProblemDetail detail=ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,ex.getMessage());
        detail.setProperty("code","ACCESS_DENIED");return detail;
    }
    @ExceptionHandler(EmptyResultDataAccessException.class)
    ProblemDetail notFound(EmptyResultDataAccessException ex) {
        ProblemDetail detail=ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,"Requested resource was not found");detail.setTitle("Not found");detail.setProperty("code","NOT_FOUND");return detail;
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(DataIntegrityViolationException ex) {
        ProblemDetail detail=ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,"The operation violates a uniqueness, reference, or state constraint");detail.setTitle("Data conflict");detail.setProperty("code","DATA_CONFLICT");return detail;
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException ex) {
        ProblemDetail detail=ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,ex.getMessage());detail.setTitle("Invalid request");detail.setProperty("code","INVALID_ARGUMENT");return detail;
    }
    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail unauthorized(AuthenticationException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        detail.setTitle("Authentication failed"); detail.setProperty("code", "AUTHENTICATION_FAILED"); return detail;
    }
    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail businessRule(BusinessRuleException ex, HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        detail.setTitle("Business rule violation");
        detail.setType(URI.create("urn:nwtrack:error:" + ex.code().toLowerCase()));
        detail.setProperty("code", ex.code());
        detail.setProperty("path", request.getRequestURI());
        return detail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(e -> e.getField(), e -> e.getDefaultMessage() == null ? "invalid" : e.getDefaultMessage(), (a, b) -> a));
        detail.setProperty("code", "VALIDATION_FAILED");
        detail.setProperty("fieldErrors", errors);
        return detail;
    }
}
