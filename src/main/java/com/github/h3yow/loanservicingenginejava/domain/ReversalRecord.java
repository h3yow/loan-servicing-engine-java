package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 还款冲正事实流水（不可变）：冲的哪一笔还款、冲了多少钱、原因、何时生效。
 *
 * <p>冲正不删除原还款；重放时按 {@code effectiveDate} 与目标还款序号，
 * 把 {@code amount} 从原还款的有效金额中扣减后整体重述重算。</p>
 */
public final class ReversalRecord {
    public final String idempotencyKey;
    public final long targetPaymentSequence;
    public final BigDecimal amount;
    public final LocalDate effectiveDate;
    public final LocalDate submittedAt;
    public final long sequence;
    public final String reason;

    public ReversalRecord(String idempotencyKey, long targetPaymentSequence, BigDecimal amount,
                          LocalDate effectiveDate, LocalDate submittedAt, long sequence,
                          String reason) {
        this.idempotencyKey = idempotencyKey;
        this.targetPaymentSequence = targetPaymentSequence;
        this.amount = amount;
        this.effectiveDate = effectiveDate;
        this.submittedAt = submittedAt;
        this.sequence = sequence;
        this.reason = reason;
    }
}
