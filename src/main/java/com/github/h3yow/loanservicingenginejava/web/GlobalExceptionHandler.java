package com.github.h3yow.loanservicingenginejava.web;

import com.github.h3yow.loanservicingenginejava.error.LoanEngineException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 把可区分的业务错误码映射为稳定的 HTTP 响应体。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(LoanEngineException.class)
    public ResponseEntity<Map<String, String>> handle(LoanEngineException ex) {
        HttpStatus status = switch (ex.getErrorCode()) {
            case ACCOUNT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IDEMPOTENCY_CONFLICT, ILLEGAL_TRANSITION,
                 ACCOUNT_NOT_PAYABLE, PAYMENT_EXCEEDS_DUE,
                 BACKDATE_NOT_SUPPORTED, RISK_RULE_VERSION_NOT_FOUND,
                 INVALID_REQUEST -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(Map.of(
                "errorCode", ex.getErrorCode().name(),
                "message", ex.getMessage() == null ? "" : ex.getMessage()));
    }
}
