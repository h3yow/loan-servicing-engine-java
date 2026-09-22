package com.github.h3yow.loanservicingenginejava.domain;

/**
 * 账户风险分类状态，流转规则见 RiskStateMachine。
 */
public enum RiskState {
    NORMAL,
    WATCH,
    OVERDUE,
    NPL,
    WRITTEN_OFF
}
