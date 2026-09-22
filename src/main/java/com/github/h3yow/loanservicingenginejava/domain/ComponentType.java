package com.github.h3yow.loanservicingenginejava.domain;

/** 还款分摊的资金构成部分。分摊顺序：FEE -> PENALTY -> INTEREST -> PRINCIPAL。 */
public enum ComponentType {
    FEE,
    PENALTY,
    INTEREST,
    PRINCIPAL
}
