package com.github.h3yow.loanservicingenginejava.domain;

/**
 * 可区分的业务拒绝原因。
 */
public enum RejectReason {
    ACCOUNT_NOT_FOUND,
    ACCOUNT_NOT_POSTABLE,
    IDEMPOTENCY_KEY_CONFLICT,
    PAYMENT_AMOUNT_INVALID,
    OVERPAYMENT,
    ILLEGAL_RISK_TRANSITION,
    RULE_VERSION_UNKNOWN,
    VALIDATION_ERROR
}
