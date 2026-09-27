package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 还款冲正指令：对目标还款（paymentSequence）冲销 amount 金额。
 *
 * <p>effectiveDate 为冲正生效（登记）日；为空表示按提交时间与日切规则确定。
 * 冲正语义为对原入账事实的更正：被冲金额自原还款生效日起不再算作已收，
 * 账本按"原还款生效日 + 冲正后剩余有效金额"整体重放，而非新增一笔反向资金流水。</p>
 */
public final class ReversalCommand {
    public final String accountId;
    public final String idempotencyKey;
    public final long paymentSequence;
    public final BigDecimal amount;
    public final LocalDate effectiveDate;
    public final String reason;

    public ReversalCommand(String accountId, String idempotencyKey, long paymentSequence,
                           BigDecimal amount, LocalDate effectiveDate, String reason) {
        this.accountId = accountId;
        this.idempotencyKey = idempotencyKey;
        this.paymentSequence = paymentSequence;
        this.amount = amount;
        this.effectiveDate = effectiveDate;
        this.reason = reason;
    }
}
