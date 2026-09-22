package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 已入账还款流水（不可变事实：键、金额、生效日、提交日、序号）。
 *
 * <p>{@code lines} 不是事实、可在重放时按生效日重新分摊（补录/回溯后历史分摊随之重算），
 * 故设计为可变字段；它是"计划 + 流水"的派生物，而非独立事实来源。</p>
 */
public final class PaymentRecord {
    public final String idempotencyKey;
    public final BigDecimal amount;
    public final LocalDate effectiveDate;
    public final LocalDate submittedAt;
    public final long sequence;
    public List<AllocationLine> lines;

    public PaymentRecord(String idempotencyKey, BigDecimal amount, LocalDate effectiveDate,
                         LocalDate submittedAt, long sequence, List<AllocationLine> lines) {
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.effectiveDate = effectiveDate;
        this.submittedAt = submittedAt;
        this.sequence = sequence;
        this.lines = lines;
    }
}
