package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 冲正结果。duplicate=true 表示命中幂等返回的既有结果。
 */
public final class ReversalOutcome {
    public final boolean duplicate;
    public final String idempotencyKey;
    public final long reversalSequence;
    public final long targetPaymentSequence;
    public final BigDecimal reversedAmount;
    public final BigDecimal cumulativeReversed;
    public final BigDecimal originalPaymentAmount;
    public final BigDecimal remainingEffectivePayment;
    public final BigDecimal remainingTotal;
    public final AccountStatus accountStatus;
    public final RiskState riskState;
    public final int overdueDays;
    public final LocalDate effectiveDate;

    public ReversalOutcome(boolean duplicate, String idempotencyKey, long reversalSequence,
                           long targetPaymentSequence, BigDecimal reversedAmount,
                           BigDecimal cumulativeReversed, BigDecimal originalPaymentAmount,
                           BigDecimal remainingEffectivePayment, BigDecimal remainingTotal,
                           AccountStatus accountStatus, RiskState riskState, int overdueDays,
                           LocalDate effectiveDate) {
        this.duplicate = duplicate;
        this.idempotencyKey = idempotencyKey;
        this.reversalSequence = reversalSequence;
        this.targetPaymentSequence = targetPaymentSequence;
        this.reversedAmount = reversedAmount;
        this.cumulativeReversed = cumulativeReversed;
        this.originalPaymentAmount = originalPaymentAmount;
        this.remainingEffectivePayment = remainingEffectivePayment;
        this.remainingTotal = remainingTotal;
        this.accountStatus = accountStatus;
        this.riskState = riskState;
        this.overdueDays = overdueDays;
        this.effectiveDate = effectiveDate;
    }
}
