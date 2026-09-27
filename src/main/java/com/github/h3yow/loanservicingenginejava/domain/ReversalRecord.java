package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 还款冲正事实（不可变）：冲的是哪一笔、冲了多少、原因、何时登记与何时生效。
 */
public final class ReversalRecord {
    public final String idempotencyKey;
    public final long paymentSequence;
    public final BigDecimal amount;
    public final LocalDate effectiveDate;
    public final LocalDate submittedAt;
    public final long sequence;
    public final String reason;

    public ReversalRecord(String idempotencyKey, long paymentSequence, BigDecimal amount,
                          LocalDate effectiveDate, LocalDate submittedAt, long sequence,
                          String reason) {
        this.idempotencyKey = idempotencyKey;
        this.paymentSequence = paymentSequence;
        this.amount = amount;
        this.effectiveDate = effectiveDate;
        this.submittedAt = submittedAt;
        this.sequence = sequence;
        this.reason = reason;
    }

    public ReversalRecord copy() {
        return new ReversalRecord(idempotencyKey, paymentSequence, amount, effectiveDate,
                submittedAt, sequence, reason);
    }
}
