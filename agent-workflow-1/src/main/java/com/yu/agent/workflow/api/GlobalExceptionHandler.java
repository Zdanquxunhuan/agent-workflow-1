package com.yu.agent.workflow.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage()).collect(Collectors.joining(", "));
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> malformedRequest(Exception ex, HttpServletRequest request) {
        String message = ex instanceof MissingRequestHeaderException header
                ? "Required header is missing: " + header.getHeaderName()
                : "Request body is missing or malformed";
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException ex, HttpServletRequest request) {
        HttpStatus status = switch (ex.code()) {
            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "TASK_NOT_FOUND", "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "MODEL_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "RESULT_NOT_VERIFIED" -> HttpStatus.CONFLICT;
            case "INVALID_STATE", "APPROVAL_REQUIRED", "IDEMPOTENCY_CONFLICT" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return response(status, ex.code(), ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> illegal(IllegalArgumentException ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unknown(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Request could not be completed", request);
    }

    private ResponseEntity<ApiResponse<Void>> response(HttpStatus status, String code, String message, HttpServletRequest request) {
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) traceId = UUID.randomUUID().toString();
        return ResponseEntity.status(status).body(ApiResponse.error(code, message, traceId));
    }
}
