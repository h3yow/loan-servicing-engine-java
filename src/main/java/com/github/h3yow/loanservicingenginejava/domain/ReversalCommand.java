package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 还款冲正指令：对已入账的某笔还款（paymentSequence）整笔或部分冲正。
 *
 * <p>effectiveDate 为空表示按提交时间与日切规则确定生效日期；
 * 冲正生效日不得早于被冲还款的生效日。</p>
 */
public final class ReversalCommand {
    public final String accountId;
    public final String idempotencyKey;
    public final long paymentSequence;
    public final BigDecimal amount;
    public final LocalDate effectiveDate;
    public final String reason;

    public ReversalCommand(String accountId, String idempotencyKey, long paymentSequence,
                           BigDecimal amount, LocalDate effectiveDate, String reason) {
        this.accountId = accountId;
        this.idempotencyKey = idempotencyKey;
        this.paymentSequence = paymentSequence;
        this.amount = amount;
        this.effectiveDate = effectiveDate;
        this.reason = reason;
    }
}
