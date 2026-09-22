package com.github.h3yow.loanservicingenginejava.domain;

import java.time.LocalDateTime;

/** 一条风险判定/迁移历史。规则升级不改写历史行，只追加新行。 */
public record RiskAssessment(LocalDateTime at,
                             RiskState fromState,
                             RiskState toState,
                             String ruleVersion,
                             String reason) {
}
