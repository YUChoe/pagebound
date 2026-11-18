package io.github.yuchoe.pagebound.server.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import io.github.yuchoe.pagebound.core.WikiException;

/**
 * 오류 응답: { code, message, ...details } (예: VERSION_CONFLICT + currentVersion/currentContent).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(WikiException.class)
    public ResponseEntity<Map<String, Object>> wiki(WikiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("message", e.getMessage());
        body.putAll(e.details());
        return ResponseEntity.status(e.status()).body(body);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(Map.of("code", "TOO_LARGE", "message", "upload exceeds size limit"));
    }
}
