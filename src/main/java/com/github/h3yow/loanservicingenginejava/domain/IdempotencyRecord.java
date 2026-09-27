package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;

/**
 * 幂等键登记：用于重复提交识别与同键不同请求（标识冲突）拒绝。
 *
 * <p>{@code kind} 区分该键属于还款（PAYMENT）还是冲正（REVERSAL）：
 * 同一键串到另一类操作也属于标识冲突，必须拒绝。</p>
 */
public final class IdempotencyRecord {
    public final String accountId;
    public final BigDecimal amount;
    public final long paymentSequence;
    public final String kind;
    /** 冲正专属：被冲还款序号（还款登记为 -1）。 */
    public final long targetSequence;

    public IdempotencyRecord(String accountId, BigDecimal amount, long paymentSequence) {
        this(accountId, amount, paymentSequence, "PAYMENT", -1L);
    }

    public IdempotencyRecord(String accountId, BigDecimal amount, long paymentSequence,
                             String kind, long targetSequence) {
        this.accountId = accountId;
        this.amount = amount;
        this.paymentSequence = paymentSequence;
        this.kind = kind;
        this.targetSequence = targetSequence;
    }
}
