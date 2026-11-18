package com.thermogrid.workload.api;

import com.thermogrid.workload.service.WorkloadExceptions.ConflictException;
import com.thermogrid.workload.service.WorkloadExceptions.InvalidRequestException;
import com.thermogrid.workload.service.WorkloadExceptions.NotFoundException;
import com.thermogrid.workload.service.WorkloadExceptions.RemovedException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Same error body as the other services. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Dtos.ApiError> notFound(NotFoundException e, HttpServletRequest req) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), req);
    }

    @ExceptionHandler({ConflictException.class, ObjectOptimisticLockingFailureException.class})
    ResponseEntity<Dtos.ApiError> conflict(Exception e, HttpServletRequest req) {
        String message = e instanceof ConflictException ? e.getMessage()
                : "Workload was changed concurrently; nothing applied";
        return error(HttpStatus.CONFLICT, "CONFLICT", message, req);
    }

    @ExceptionHandler(RemovedException.class)
    ResponseEntity<Dtos.ApiError> removed(RemovedException e, HttpServletRequest req) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "WORKLOAD_REMOVED", e.getMessage(), req);
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<Dtos.ApiError> invalid(InvalidRequestException e, HttpServletRequest req) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Dtos.ApiError> validation(MethodArgumentNotValidException e, HttpServletRequest req) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage()).sorted().collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message, req);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Dtos.ApiError> methodValidation(HandlerMethodValidationException e, HttpServletRequest req) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid request body", req);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Dtos.ApiError> unreadable(Exception e, HttpServletRequest req) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Malformed request or invalid value", req);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Dtos.ApiError> integrity(DataIntegrityViolationException e, HttpServletRequest req) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Value rejected by the database", req);
    }

    private static ResponseEntity<Dtos.ApiError> error(HttpStatus status, String code, String message,
                                                       HttpServletRequest req) {
        return ResponseEntity.status(status)
                .body(new Dtos.ApiError(Instant.now(), status.value(), code, message, req.getRequestURI()));
    }
}
