package com.github.h3yow.loanservicingenginejava.domain;

/**
 * 贷款账户生命周期状态。只有 ACTIVE 账户可入账。
 */
public enum AccountStatus {
    ACTIVE,
    SETTLED,
    CLOSED
}
