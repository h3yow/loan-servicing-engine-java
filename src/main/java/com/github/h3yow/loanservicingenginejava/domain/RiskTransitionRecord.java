package com.github.h3yow.loanservicingenginejava.domain;

import java.time.LocalDate;

/**
 * 风险状态流转历史条目（保留判定时使用的规则版本）。
 */
public final class RiskTransitionRecord {
    public final LocalDate effectiveDate;
    public final RiskState from;
    public final RiskState to;
    public final String ruleVersion;
    public final String reason;

    public RiskTransitionRecord(LocalDate effectiveDate, RiskState from, RiskState to,
                                String ruleVersion, String reason) {
        this.effectiveDate = effectiveDate;
        this.from = from;
        this.to = to;
        this.ruleVersion = ruleVersion;
        this.reason = reason;
    }
}
