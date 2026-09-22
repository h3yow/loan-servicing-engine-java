package com.github.h3yow.loanservicingenginejava.error;

/** 可区分的业务失败原因。 */
public enum ErrorCode {
    ACCOUNT_NOT_FOUND,
    ACCOUNT_NOT_PAYABLE,
    IDEMPOTENCY_CONFLICT,
    PAYMENT_EXCEEDS_DUE,
    ILLEGAL_TRANSITION,
    RISK_RULE_VERSION_NOT_FOUND,
    BACKDATE_NOT_SUPPORTED,
    INVALID_REQUEST
}
