package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 还款入账指令。effectiveDate 为空表示按提交时间与日切规则确定生效日期。
 */
public final class PaymentCommand {
    public final String accountId;
    public final String idempotencyKey;
    public final BigDecimal amount;
    public final LocalDate effectiveDate;

    public PaymentCommand(String accountId, String idempotencyKey,
                          BigDecimal amount, LocalDate effectiveDate) {
        this.accountId = accountId;
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.effectiveDate = effectiveDate;
    }
}
