package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;

/**
 * 幂等键登记：用于重复提交识别与同键不同请求（标识冲突）拒绝。
 */
public final class IdempotencyRecord {
    public final String accountId;
    public final BigDecimal amount;
    public final long paymentSequence;

    public IdempotencyRecord(String accountId, BigDecimal amount, long paymentSequence) {
        this.accountId = accountId;
        this.amount = amount;
        this.paymentSequence = paymentSequence;
    }
}
