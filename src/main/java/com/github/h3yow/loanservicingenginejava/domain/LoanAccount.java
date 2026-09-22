package com.github.h3yow.loanservicingenginejava.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地账本中的贷款账户（聚合根）。
 */
public final class LoanAccount {
    public final String accountId;
    public final LoanTerms terms;
    public final List<Installment> installments;
    public AccountStatus status;
    public RiskState riskState;
    public String ruleVersion;
    public LocalDate riskStateDate;

    public LoanAccount(String accountId, LoanTerms terms) {
        this.accountId = accountId;
        this.terms = terms;
        this.installments = new ArrayList<>();
        this.status = AccountStatus.ACTIVE;
        this.riskState = RiskState.NORMAL;
        this.ruleVersion = null;
        this.riskStateDate = null;
    }
}
