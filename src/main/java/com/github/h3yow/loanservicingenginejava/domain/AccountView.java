package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 账户查询视图：账户事实 + 指定日期重放得到的派生口径与分摊明细。
 */
public final class AccountView {
    public final String accountId;
    public final AccountStatus status;
    public final RiskState riskState;
    public final String ruleVersion;
    public final LocalDate riskStateDate;
    public final LocalDate asOf;
    public final List<Installment> installments;
    public final BigDecimal remainingPrincipal;
    public final BigDecimal remainingInterest;
    public final BigDecimal remainingFee;
    public final BigDecimal outstandingPenalty;
    public final BigDecimal remainingTotal;
    public final List<PaymentRecord> payments;
    public final List<ReversalRecord> reversals;
    public final List<RiskTransitionRecord> riskHistory;

    public AccountView(String accountId, AccountStatus status, RiskState riskState,
                       String ruleVersion, LocalDate riskStateDate, LocalDate asOf,
                       List<Installment> installments,
                       BigDecimal remainingPrincipal, BigDecimal remainingInterest,
                       BigDecimal remainingFee, BigDecimal outstandingPenalty,
                       BigDecimal remainingTotal,
                       List<PaymentRecord> payments,
                       List<ReversalRecord> reversals,
                       List<RiskTransitionRecord> riskHistory) {
        this.accountId = accountId;
        this.status = status;
        this.riskState = riskState;
        this.ruleVersion = ruleVersion;
        this.riskStateDate = riskStateDate;
        this.asOf = asOf;
        this.installments = installments;
        this.remainingPrincipal = remainingPrincipal;
        this.remainingInterest = remainingInterest;
        this.remainingFee = remainingFee;
        this.outstandingPenalty = outstandingPenalty;
        this.remainingTotal = remainingTotal;
        this.payments = payments;
        this.reversals = reversals;
        this.riskHistory = riskHistory;
    }
}
