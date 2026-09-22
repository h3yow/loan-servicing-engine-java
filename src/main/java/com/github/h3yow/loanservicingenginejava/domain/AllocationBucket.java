package com.github.h3yow.loanservicingenginejava.domain;

/**
 * 还款分摊桶。默认分摊顺序：罚息 -> 费用 -> 利息 -> 本金。
 */
public enum AllocationBucket {
    PENALTY,
    FEE,
    INTEREST,
    PRINCIPAL
}
