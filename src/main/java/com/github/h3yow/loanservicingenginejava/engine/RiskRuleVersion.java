package com.github.h3yow.loanservicingenginejava.engine;

import com.github.h3yow.loanservicingenginejava.domain.RiskState;

import java.time.LocalDate;
import java.util.Map;

/**
 * 带版本的风险分类规则：不同版本对应不同逾期天数阈值，规则升级不改写历史判定。
 */
public final class RiskRuleVersion {
    public final String version;
    public final LocalDate effectiveDate;
    public final Map<RiskState, Integer> minOverdueDays;

    public RiskRuleVersion(String version, LocalDate effectiveDate,
                           Map<RiskState, Integer> minOverdueDays) {
        this.version = version;
        this.effectiveDate = effectiveDate;
        this.minOverdueDays = Map.copyOf(minOverdueDays);
    }
}
