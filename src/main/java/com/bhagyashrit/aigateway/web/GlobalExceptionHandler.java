package com.bhagyashrit.aigateway.web;

import com.bhagyashrit.aigateway.model.ChatDtos.ErrorBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps framework and unexpected exceptions to OpenAI-style JSON errors. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> badJson(HttpMessageNotReadableException e) {
        return err(HttpStatus.BAD_REQUEST, "invalid JSON body", "invalid_request_error", "invalid_json");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorBody> missingHeader(MissingRequestHeaderException e) {
        return err(HttpStatus.BAD_REQUEST, "missing required header: " + e.getHeaderName(),
                "invalid_request_error", "missing_header");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception e) {
        log.error("unhandled_error", e);
        return err(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error",
                "server_error", "internal_error");
    }

    private static ResponseEntity<ErrorBody> err(HttpStatus status, String message, String type, String code) {
        return ResponseEntity.status(status)
                .body(new ErrorBody(new ErrorBody.ErrorDetail(message, type, code)));
    }
}
