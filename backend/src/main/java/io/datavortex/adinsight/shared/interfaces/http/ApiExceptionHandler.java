package io.datavortex.adinsight.shared.interfaces.http;

import io.datavortex.adinsight.shared.domain.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> business(BusinessException exception, HttpServletRequest request) {
        int status =
                switch (exception.code()) {
                    case FORBIDDEN -> 403;
                    case NOT_FOUND -> 404;
                    case IDEMPOTENCY_CONFLICT, LEASE_LOST -> 409;
                };
        return ResponseEntity.status(status)
                .body(
                        new ApiError(
                                exception.code().name(),
                                exception.getMessage(),
                                (String) request.getAttribute("requestId")));
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        MissingRequestHeaderException.class,
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class
    })
    ResponseEntity<ApiError> invalidInput(HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(
                        new ApiError(
                                "INVALID_REQUEST",
                                "Invalid request fields or headers",
                                (String) request.getAttribute("requestId")));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> databaseUnavailable(HttpServletRequest request) {
        return ResponseEntity.status(503)
                .body(
                        new ApiError(
                                "DATABASE_UNAVAILABLE",
                                "Data dependency unavailable",
                                (String) request.getAttribute("requestId")));
    }
}
