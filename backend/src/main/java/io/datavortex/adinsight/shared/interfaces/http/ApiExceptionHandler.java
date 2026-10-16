package io.datavortex.adinsight.shared.interfaces.http;

import io.datavortex.adinsight.shared.domain.BusinessException;
import io.datavortex.adinsight.shared.domain.InvalidInputException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> business(BusinessException exception, HttpServletRequest request) {
        int status =
                switch (exception.code()) {
                    case FORBIDDEN -> 403;
                    case NOT_FOUND -> 404;
                    case WAREHOUSE_UNAVAILABLE -> 503;
                    case IDEMPOTENCY_CONFLICT,
                            LEASE_LOST,
                            OPTIMISTIC_CONFLICT,
                            RESOURCE_CONFLICT,
                            BUDGET_PERIOD_CONFLICT ->
                            409;
                };
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        new ApiError(
                                exception.code().name(),
                                exception.getMessage(),
                                (String) request.getAttribute("requestId")));
    }

    @ExceptionHandler(InvalidInputException.class)
    ResponseEntity<ApiError> invalidInput(HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        new ApiError(
                                "INVALID_REQUEST",
                                "Invalid request fields or headers",
                                (String) request.getAttribute("requestId")));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> databaseUnavailable(HttpServletRequest request) {
        LOG.error("code=DATABASE_UNAVAILABLE dependency=mysql");
        return ResponseEntity.status(503)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        new ApiError(
                                "DATABASE_UNAVAILABLE",
                                "Data dependency unavailable",
                                (String) request.getAttribute("requestId")));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> denied(HttpServletRequest request) {
        return ResponseEntity.status(403)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        new ApiError(
                                "FORBIDDEN",
                                "Access denied",
                                (String) request.getAttribute("requestId")));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        // Messages/stacks may contain SQL, credentials or raw request data.
        LOG.error("code=INTERNAL_ERROR exceptionType={}", exception.getClass().getSimpleName());
        return ResponseEntity.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        new ApiError(
                                "INTERNAL_ERROR",
                                "An unexpected error occurred",
                                (String) request.getAttribute("requestId")));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String code =
                switch (status.value()) {
                    case 400 -> "INVALID_REQUEST";
                    case 404 -> "NOT_FOUND";
                    case 405 -> "METHOD_NOT_ALLOWED";
                    case 406 -> "NOT_ACCEPTABLE";
                    case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                    default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "INVALID_REQUEST";
                };
        String message =
                switch (status.value()) {
                    case 400 -> "Invalid request fields or headers";
                    case 404 -> "Resource not found";
                    case 405 -> "HTTP method not allowed";
                    case 406 -> "Requested response format unavailable";
                    case 415 -> "Unsupported request content type";
                    default -> "Request could not be completed";
                };
        List<FieldViolation> details = List.of();
        if (exception instanceof MethodArgumentNotValidException invalid) {
            details =
                    invalid.getBindingResult().getFieldErrors().stream()
                            .map(field -> new FieldViolation(field.getField(), "Invalid value"))
                            .distinct()
                            .sorted(java.util.Comparator.comparing(FieldViolation::field))
                            .toList();
        }
        var errorHeaders = new HttpHeaders();
        errorHeaders.putAll(headers);
        errorHeaders.setContentType(MediaType.APPLICATION_JSON);
        return super.handleExceptionInternal(
                exception,
                new ApiError(
                        code,
                        message,
                        (String) request.getAttribute("requestId", WebRequest.SCOPE_REQUEST),
                        details),
                errorHeaders,
                status,
                request);
    }
}
