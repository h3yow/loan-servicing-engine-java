package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 账本事件：仅追加、按生效时间排序重放。
 * 回溯入账即向历史位置插入事件后全量重放，罚息不会重复累计。
 */
public sealed interface AccountEvent
        permits AccountEvent.Opened,
                AccountEvent.PaymentReceived,
                AccountEvent.FeeCharged,
                AccountEvent.RiskTransition,
                AccountEvent.WrittenOff {

    /** 事件序号（插入顺序），同一生效时间时以此稳定排序。 */
    long seq();

    /** 业务生效时间（含业务日切换算后的入账时间）。 */
    LocalDateTime effectiveAt();

    /** 账户开户。 */
    record Opened(long seq,
                  LocalDateTime effectiveAt,
                  String accountId,
                  Terms terms,
                  LocalDate firstDueDate) implements AccountEvent {
    }

    /** 还款入账（幂等键唯一）。earlySettlement 为提前结清标记，随事件持久化。 */
    record PaymentReceived(long seq,
                           LocalDateTime effectiveAt,
                           String idempotencyKey,
                           BigDecimal amount,
                           LocalDateTime submittedAt,
                           boolean earlySettlement) implements AccountEvent {
    }

    /** 费用计收（如管理费）。 */
    record FeeCharged(long seq,
                      LocalDateTime effectiveAt,
                      String feeId,
                      BigDecimal amount) implements AccountEvent {
    }

    /** 风险状态迁移（记录判定时使用的规则版本）。 */
    record RiskTransition(long seq,
                          LocalDateTime effectiveAt,
                          RiskState fromState,
                          RiskState toState,
                          String ruleVersion,
                          String reason) implements AccountEvent {
    }

    /** 核销。 */
    record WrittenOff(long seq,
                      LocalDateTime effectiveAt,
                      String reason) implements AccountEvent {
    }
}
