package com.github.h3yow.loanservicingenginejava.domain;

/**
 * 幂等键所属的资金操作类型：用于跨类型串用键时明确拒绝（键冲突）。
 */
public enum IdempotencyKind {
    PAYMENT,
    REVERSAL
}
