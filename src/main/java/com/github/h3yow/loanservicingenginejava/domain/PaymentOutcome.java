package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 还款入账结果。duplicate=true 表示命中幂等返回的既有结果。
 */
public final class PaymentOutcome {
    public final boolean duplicate;
    public final String idempotencyKey;
    /** 本笔还款在账本中的全局流水序号（冲正接口按此定位被冲还款）。 */
    public final long paymentSequence;
    public final List<AllocationLine> lines;
    public final BigDecimal allocated;
    public final BigDecimal remainingTotal;
    public final AccountStatus accountStatus;

    public PaymentOutcome(boolean duplicate, String idempotencyKey, List<AllocationLine> lines,
                          BigDecimal allocated, BigDecimal remainingTotal, AccountStatus accountStatus) {
        this(duplicate, idempotencyKey, 0L, lines, allocated, remainingTotal, accountStatus);
    }

    public PaymentOutcome(boolean duplicate, String idempotencyKey, long paymentSequence,
                          List<AllocationLine> lines,
                          BigDecimal allocated, BigDecimal remainingTotal, AccountStatus accountStatus) {
        this.duplicate = duplicate;
        this.idempotencyKey = idempotencyKey;
        this.paymentSequence = paymentSequence;
        this.lines = lines;
        this.allocated = allocated;
        this.remainingTotal = remainingTotal;
        this.accountStatus = accountStatus;
    }
}
