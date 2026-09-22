package com.github.h3yow.loanservicingenginejava.domain;

/** 风险分类状态（带版本的规则进行分类与迁移）。 */
public enum RiskState {
    NORMAL,
    WATCH,
    SUBSTANDARD,
    DOUBTFUL,
    NPL,
    WRITTEN_OFF
}
