package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;

/** 任意时刻的账户余额构成。 */
public record BalanceSnapshot(BigDecimal outstandingPrincipal,
                              BigDecimal outstandingInterest,
                              BigDecimal outstandingFee,
                              BigDecimal accruedPenalty,
                              BigDecimal totalDue) {
}
