package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;

/**
 * 幂等键登记：用于重复提交识别与同键不同请求（标识冲突）拒绝。
 *
 * <p>{@code kind} 区分还款与冲正；对还款 {@code refSequence} 为还款流水序号，
 * 对冲正为"被冲还款序号"（目标还款）；冲正自身的流水序号在冲正账本中按键可查。</p>
 */
public final class IdempotencyRecord {
    public final IdempotencyKind kind;
    public final String accountId;
    public final BigDecimal amount;
    public final long refSequence;

    public IdempotencyRecord(IdempotencyKind kind, String accountId, BigDecimal amount,
                             long refSequence) {
        this.kind = kind;
        this.accountId = accountId;
        this.amount = amount;
        this.refSequence = refSequence;
    }
}
