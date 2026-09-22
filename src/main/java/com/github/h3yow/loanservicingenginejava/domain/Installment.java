package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 还款计划中的一期。金额字段可随分摊入账变动。
 *
 * <p>{@code outstandingPenalty} 为重放时的工作字段：由罚息计算器在每个处理日
 * 重算，表示截至该日该期应计未付罚息；不持久化为独立口径（由计划与流水决定）。</p>
 */
public final class Installment {
    public final int seq;
    public final LocalDate dueDate;
    public BigDecimal scheduledPrincipal;
    public BigDecimal scheduledInterest;
    public BigDecimal scheduledFee;
    public BigDecimal paidPrincipal;
    public BigDecimal paidInterest;
    public BigDecimal paidFee;
    public BigDecimal paidPenalty;
    public BigDecimal outstandingPenalty;

    public Installment(int seq, LocalDate dueDate, BigDecimal scheduledPrincipal,
                       BigDecimal scheduledInterest, BigDecimal scheduledFee) {
        this.seq = seq;
        this.dueDate = dueDate;
        this.scheduledPrincipal = scheduledPrincipal;
        this.scheduledInterest = scheduledInterest;
        this.scheduledFee = scheduledFee;
        this.paidPrincipal = BigDecimal.ZERO;
        this.paidInterest = BigDecimal.ZERO;
        this.paidFee = BigDecimal.ZERO;
        this.paidPenalty = BigDecimal.ZERO;
        this.outstandingPenalty = BigDecimal.ZERO;
    }

    public Installment copy() {
        Installment c = new Installment(seq, dueDate, scheduledPrincipal, scheduledInterest, scheduledFee);
        c.paidPrincipal = paidPrincipal;
        c.paidInterest = paidInterest;
        c.paidFee = paidFee;
        c.paidPenalty = paidPenalty;
        c.outstandingPenalty = outstandingPenalty;
        return c;
    }
}
