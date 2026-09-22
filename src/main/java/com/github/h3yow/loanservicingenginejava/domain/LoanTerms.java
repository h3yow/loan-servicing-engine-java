package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 贷款合同要素。
 */
public final class LoanTerms {
    public final BigDecimal principal;
    public final BigDecimal annualRate;
    public final int installmentCount;
    public final LocalDate originationDate;
    public final LocalDate firstDueDate;
    public final RepaymentMethod method;
    public final BigDecimal feeRate;

    public LoanTerms(BigDecimal principal, BigDecimal annualRate, int installmentCount,
                     LocalDate originationDate, LocalDate firstDueDate,
                     RepaymentMethod method, BigDecimal feeRate) {
        this.principal = principal;
        this.annualRate = annualRate;
        this.installmentCount = installmentCount;
        this.originationDate = originationDate;
        this.firstDueDate = firstDueDate;
        this.method = method;
        this.feeRate = feeRate;
    }
}
