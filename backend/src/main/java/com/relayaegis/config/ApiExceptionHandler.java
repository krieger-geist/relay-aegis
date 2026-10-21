package com.relayaegis.config;

import com.relayaegis.dto.ApiDtos.ErrorDto;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorDto> handleStatus(ResponseStatusException exception) {
        HttpStatus status=HttpStatus.valueOf(exception.getStatusCode().value());
        String message=exception.getReason()==null?status.getReasonPhrase():exception.getReason();
        return ResponseEntity.status(status).body(new ErrorDto(message,status.value(),Instant.now()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorDto> handleValidation(MethodArgumentNotValidException exception) {
        String message=exception.getBindingResult().getFieldErrors().stream().map(this::fieldMessage).distinct().collect(Collectors.joining("; "));
        if (message.isBlank()) message="Request validation failed.";
        Map<String,String> fields=new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()==null?"is invalid":error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ErrorDto(message,400,Instant.now(),fields.isEmpty()?null:fields));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorDto> handleUnreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(new ErrorDto("Request body is missing or is not valid JSON.",400,Instant.now()));
    }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorDto> handleAccessDenied(AccessDeniedException exception) {
        // Method-security denials. Only authenticated callers reach controllers (except public endpoints), so this is a 403.
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorDto("You do not have permission to perform this action.",403,Instant.now()));
    }
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorDto> handleNotFound(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorDto("API endpoint not found.",404,Instant.now()));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorDto> handleUnexpected(Exception exception) {
        // Log details locally, but never expose stack traces or configuration secrets to the browser.
        log.error("Unhandled API exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorDto("An unexpected server error occurred. Check backend logs for the incident details.",500,Instant.now()));
    }
    private String fieldMessage(FieldError error) { return error.getField()+": "+(error.getDefaultMessage()==null?"is invalid":error.getDefaultMessage()); }
}
