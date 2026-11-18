package com.thermogrid.telemetry.api;

import com.thermogrid.telemetry.service.TelemetryQueryService.InvalidQueryException;
import com.thermogrid.telemetry.service.TelemetryQueryService.RackNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Same error body as the other services. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RackNotFoundException.class)
    ResponseEntity<Dtos.ApiError> notFound(RackNotFoundException e, HttpServletRequest req) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), req);
    }

    @ExceptionHandler(InvalidQueryException.class)
    ResponseEntity<Dtos.ApiError> invalid(InvalidQueryException e, HttpServletRequest req) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Dtos.ApiError> badType(MethodArgumentTypeMismatchException e, HttpServletRequest req) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid value for parameter " + e.getName(), req);
    }

    private static ResponseEntity<Dtos.ApiError> error(HttpStatus status, String code, String message,
                                                       HttpServletRequest req) {
        return ResponseEntity.status(status)
                .body(new Dtos.ApiError(Instant.now(), status.value(), code, message, req.getRequestURI()));
    }
}
