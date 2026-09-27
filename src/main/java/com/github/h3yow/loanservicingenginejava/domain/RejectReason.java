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
    /** 冲正目标还款不存在或不属于该账户（标识串户/串还款）。 */
    REVERSAL_TARGET_NOT_FOUND,
    /** 冲正金额非法（<= 0 等）。 */
    REVERSAL_AMOUNT_INVALID,
    /** 累计冲正金额超过原还款金额（含对同一笔还款的重复冲正）。 */
    REVERSAL_EXCEEDS_PAYMENT,
    VALIDATION_ERROR
}
