package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 还款计划中的一期（应还款项）。 */
public record Installment(int periodNo,
                          LocalDate dueDate,
                          BigDecimal scheduledPrincipal,
                          BigDecimal scheduledInterest,
                          BigDecimal paidPrincipal,
                          BigDecimal paidInterest,
                          BigDecimal paidFee,
                          BigDecimal paidPenalty,
                          boolean settled) {
}
