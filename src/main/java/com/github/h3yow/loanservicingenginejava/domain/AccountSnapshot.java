package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件重放后得到的账户完整状态（确定性结果）。
 *
 * @param riskState           当前生效的风险状态（最近一次显式迁移，默认 NORMAL）
 * @param suggestedRiskState  按当前 DPD 与最新规则版本计算出的系统建议分类
 * @param totalWaivedInterest 提前结清累计减免的到期日之后利息
 */
public record AccountSnapshot(String accountId,
                              Terms terms,
                              AccountStatus status,
                              RiskState riskState,
                              RiskState suggestedRiskState,
                              List<Installment> installments,
                              BalanceSnapshot balance,
                              List<PaymentReceipt> payments,
                              List<RiskAssessment> riskHistory,
                              BigDecimal totalWaivedInterest,
                              LocalDateTime asOf) {
}
