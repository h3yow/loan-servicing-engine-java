package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;

/**
 * 冲正结果。duplicate=true 表示命中幂等返回的既有冲正结果。
 */
public final class ReversalOutcome {
    public final boolean duplicate;
    public final String idempotencyKey;
    public final long paymentSequence;
    public final BigDecimal reversedAmount;
    public final BigDecimal remainingReversible;
    public final BigDecimal remainingTotal;
    public final AccountStatus accountStatus;
    public final RiskState riskState;

    public ReversalOutcome(boolean duplicate, String idempotencyKey, long paymentSequence,
                           BigDecimal reversedAmount, BigDecimal remainingReversible,
                           BigDecimal remainingTotal, AccountStatus accountStatus,
                           RiskState riskState) {
        this.duplicate = duplicate;
        this.idempotencyKey = idempotencyKey;
        this.paymentSequence = paymentSequence;
        this.reversedAmount = reversedAmount;
        this.remainingReversible = remainingReversible;
        this.remainingTotal = remainingTotal;
        this.accountStatus = accountStatus;
        this.riskState = riskState;
    }
}
