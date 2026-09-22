package com.github.h3yow.loanservicingenginejava.engine;

import com.github.h3yow.loanservicingenginejava.domain.AllocationBucket;
import com.github.h3yow.loanservicingenginejava.domain.AllocationLine;
import com.github.h3yow.loanservicingenginejava.domain.Installment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 还款分摊。
 *
 * <p>分摊顺序：逐期（按期号/到期日），每期内依次冲抵
 * 罚息 → 费用 → 利息 → 本金；允许部分还款；提前结清即一次性冲抵全部剩余
 * （未来期罚息恒为 0，仅冲抵其计划费用/利息/本金）。</p>
 *
 * <p>{@code allocate} 同时做两件事：在可变计划上冲抵 paid*、产出本次分摊明细。
 * 因此重放时逐笔调用即可，调用方不得再额外冲抵，否则同一笔会被冲抵两次。</p>
 */
@Component
public class PaymentAllocator {

    public static final List<AllocationBucket> DEFAULT_ORDER =
            List.of(AllocationBucket.PENALTY, AllocationBucket.FEE,
                    AllocationBucket.INTEREST, AllocationBucket.PRINCIPAL);

    /**
     * 在已刷新罚息（outstandingPenalty）的可变计划上分摊一笔还款。
     *
     * @return 本次分摊明细；返回时各期 paid* 字段相应增加。
     */
    public List<AllocationLine> allocate(List<Installment> installments, BigDecimal amount,
                                         LocalDate effectiveDate) {
        List<AllocationLine> lines = new ArrayList<>();
        BigDecimal remaining = amount;
        for (Installment installment : installments) {
            if (remaining.signum() == 0) {
                break;
            }
            for (AllocationBucket bucket : DEFAULT_ORDER) {
                if (remaining.signum() == 0) {
                    break;
                }
                BigDecimal owed = owedOf(installment, bucket);
                if (owed.signum() <= 0) {
                    continue;
                }
                BigDecimal take = remaining.min(owed);
                applyOne(installment, bucket, take);
                lines.add(new AllocationLine(installment.seq, bucket, take));
                remaining = remaining.subtract(take);
            }
        }
        return lines;
    }

    private BigDecimal owedOf(Installment i, AllocationBucket bucket) {
        return switch (bucket) {
            case PENALTY -> i.outstandingPenalty;
            case FEE -> i.scheduledFee.subtract(i.paidFee).max(BigDecimal.ZERO);
            case INTEREST -> i.scheduledInterest.subtract(i.paidInterest).max(BigDecimal.ZERO);
            case PRINCIPAL -> i.scheduledPrincipal.subtract(i.paidPrincipal).max(BigDecimal.ZERO);
        };
    }

    private void applyOne(Installment i, AllocationBucket bucket, BigDecimal amount) {
        switch (bucket) {
            case PENALTY -> {
                i.paidPenalty = i.paidPenalty.add(amount);
                i.outstandingPenalty = i.outstandingPenalty.subtract(amount).max(BigDecimal.ZERO);
            }
            case FEE -> i.paidFee = i.paidFee.add(amount);
            case INTEREST -> i.paidInterest = i.paidInterest.add(amount);
            case PRINCIPAL -> i.paidPrincipal = i.paidPrincipal.add(amount);
        }
    }
}
