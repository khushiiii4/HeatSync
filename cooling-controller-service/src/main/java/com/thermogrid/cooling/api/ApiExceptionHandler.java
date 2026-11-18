package com.thermogrid.cooling.api;

import com.thermogrid.cooling.service.CoolingControlService.UnknownZoneException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Same error body as the other services. */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ApiError(Instant timestamp, int status, String error, String message, String path) {
    }

    @ExceptionHandler(UnknownZoneException.class)
    ResponseEntity<ApiError> notFound(UnknownZoneException e, HttpServletRequest req) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException e, HttpServletRequest req) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage()).sorted().collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", msg, req);
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> badRequest(Exception e, HttpServletRequest req) {
        String msg = e instanceof IllegalArgumentException ? e.getMessage() : "Malformed request or invalid value";
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", msg, req);
    }

    private static ResponseEntity<ApiError> error(HttpStatus s, String code, String msg, HttpServletRequest req) {
        return ResponseEntity.status(s).body(new ApiError(Instant.now(), s.value(), code, msg, req.getRequestURI()));
    }
}
