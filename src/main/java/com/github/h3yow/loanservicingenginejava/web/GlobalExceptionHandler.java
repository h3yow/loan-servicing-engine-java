package com.github.h3yow.loanservicingenginejava.web;

import com.github.h3yow.loanservicingenginejava.domain.RejectReason;
import com.github.h3yow.loanservicingenginejava.service.ServicingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 业务拒绝统一映射为 422 + 可区分错误码。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ServicingException.class)
    public ResponseEntity<Map<String, String>> handle(ServicingException ex) {
        HttpStatus status = ex.getReason() == RejectReason.ACCOUNT_NOT_FOUND
                ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(Map.of(
                "reason", ex.getReason().name(),
                "message", ex.getMessage()));
    }
}
