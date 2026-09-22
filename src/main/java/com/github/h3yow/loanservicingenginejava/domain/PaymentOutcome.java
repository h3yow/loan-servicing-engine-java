package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 还款入账结果。duplicate=true 表示命中幂等返回的既有结果。
 */
public final class PaymentOutcome {
    public final boolean duplicate;
    public final String idempotencyKey;
    public final List<AllocationLine> lines;
    public final BigDecimal allocated;
    public final BigDecimal remainingTotal;
    public final AccountStatus accountStatus;

    public PaymentOutcome(boolean duplicate, String idempotencyKey, List<AllocationLine> lines,
                          BigDecimal allocated, BigDecimal remainingTotal, AccountStatus accountStatus) {
        this.duplicate = duplicate;
        this.idempotencyKey = idempotencyKey;
        this.lines = lines;
        this.allocated = allocated;
        this.remainingTotal = remainingTotal;
        this.accountStatus = accountStatus;
    }
}
