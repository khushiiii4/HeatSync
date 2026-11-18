package com.thermogrid.simulator.api;

import com.thermogrid.simulator.failure.FailureExceptions.FailureAlreadyClearedException;
import com.thermogrid.simulator.failure.FailureExceptions.FailureNotFoundException;
import com.thermogrid.simulator.failure.FailureExceptions.InvalidFailureException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({InvalidFailureException.class, InvalidControlException.class})
    ResponseEntity<ApiError> badRequest(RuntimeException e, HttpServletRequest req) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage(), req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpServletRequest req) {
        return error(HttpStatus.BAD_REQUEST, "Request body is missing or is not valid JSON of the expected shape", req);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> badParam(MethodArgumentTypeMismatchException e, HttpServletRequest req) {
        return error(HttpStatus.BAD_REQUEST, "Invalid value for parameter " + e.getName(), req);
    }

    @ExceptionHandler(FailureNotFoundException.class)
    ResponseEntity<ApiError> notFound(FailureNotFoundException e, HttpServletRequest req) {
        return error(HttpStatus.NOT_FOUND, e.getMessage(), req);
    }

    @ExceptionHandler(FailureAlreadyClearedException.class)
    ResponseEntity<ApiError> conflict(FailureAlreadyClearedException e, HttpServletRequest req) {
        return error(HttpStatus.CONFLICT, e.getMessage(), req);
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String message, HttpServletRequest req) {
        String code = status == HttpStatus.BAD_REQUEST ? "BAD_REQUEST" : status.name();
        return ResponseEntity.status(status)
                .body(new ApiError(Instant.now(), status.value(), code, message, req.getRequestURI()));
    }
}
