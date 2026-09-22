package com.github.h3yow.loanservicingenginejava.error;

/** 领域规则违反异常，携带可区分的错误码。 */
public class LoanEngineException extends RuntimeException {

    private final ErrorCode errorCode;

    public LoanEngineException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
