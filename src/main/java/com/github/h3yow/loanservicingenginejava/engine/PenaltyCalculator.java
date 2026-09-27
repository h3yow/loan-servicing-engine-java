package com.github.h3yow.loanservicingenginejava.engine;

import com.github.h3yow.loanservicingenginejava.domain.AllocationBucket;
import com.github.h3yow.loanservicingenginejava.domain.AllocationLine;
import com.github.h3yow.loanservicingenginejava.domain.DayCountConvention;
import com.github.h3yow.loanservicingenginejava.domain.Installment;
import com.github.h3yow.loanservicingenginejava.domain.PaymentRecord;
import com.github.h3yow.loanservicingenginejava.domain.ReversalRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 罚息累计（纯函数）。
 *
 * <p>口径：</p>
 * <ul>
 *   <li>每期罚息起息日 = 到期日 + 宽限期 + 1 天；</li>
 *   <li>计息基数 = 该期当时未还本金；按日单利：基数 × 年罚息利率 × 天数 / 年基准天数；</li>
 *   <li>本金随还款日变动：还款日当天冲抵的是截至当日（含当日）已计罚息，
 *       故当日还本不缩减当日基数，按还款日将起息区间切段逐段计息；</li>
 *   <li>同日多笔还款按序号串行：后一笔的待付罚息须扣除同日早序号还款已付罚息；</li>
 *   <li>每次都从计划与历史流水重放重算（非增量累加），补录/回溯入账后结论一致且不重复累计。</li>
 * </ul>
 */
@Component
public class PenaltyCalculator {

    private static final int MONEY_SCALE = 2;
    private static final int CALC_SCALE = 10;

    /**
     * 计算某期截至 asOf（含当日）的累计应计罚息毛额（未扣除已付罚息）。
     *
     * <p>模型：从起息日起的每个日期点 d 都有一个计息基数 base(d)，
     * 应计 = Σ base(d)·日利率。还款日 D 还本后，D 当天仍按还本前基数计息，
     * D+1 起切换为还本后基数；起息日之前（如宽限期内）的还本直接调低初始基数。</p>
     *
     * @param principalEvents 该期在此前各生效日上被冲抵本金的事件（按日期非降序）。
     */
    public BigDecimal accruedAsOf(Installment installment, LocalDate asOf, int graceDays,
                                  BigDecimal annualPenaltyRate, DayCountConvention convention,
                                  List<PrincipalEvent> principalEvents) {
        LocalDate start = installment.dueDate.plusDays(graceDays + 1L);
        if (asOf.isBefore(start) || installment.scheduledPrincipal.signum() == 0) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal base = installment.scheduledPrincipal;
        LocalDate segStart = start;
        for (PrincipalEvent event : principalEvents) {
            LocalDate nextBaseFrom = event.date().plusDays(1);
            if (!nextBaseFrom.isAfter(start)) {
                // 起息前（含起息前一天）还本：直接调低初始基数
                base = base.subtract(event.amount()).max(BigDecimal.ZERO);
                continue;
            }
            if (nextBaseFrom.isAfter(asOf)) {
                // 新基数在 asOf 之后才生效：当前基数一直计到 asOf
                break;
            }
            // 当前基数覆盖日期点 [segStart, event.date]，天数 = event.date - segStart + 1
            total = total.add(accrue(base, annualPenaltyRate, convention,
                    ChronoUnit.DAYS.between(segStart, event.date()) + 1));
            base = base.subtract(event.amount()).max(BigDecimal.ZERO);
            segStart = nextBaseFrom;
        }
        // 最后一段覆盖日期点 [segStart, asOf]，天数 = asOf - segStart + 1
        total = total.add(accrue(base, annualPenaltyRate, convention,
                ChronoUnit.DAYS.between(segStart, asOf) + 1));
        return total.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 重放全部还款：按生效日（同日按序号）逐笔刷新罚息并重新分摊，
     * 重新分摊得到的明细回写到各流水（补录/回溯后历史分摊随之重算）。
     * 最终把截至 asOf 的应计未付罚息写入 outstandingPenalty，
     * 已付罚息/本金/利息/费用写入对应 paid* 字段，并返回截至 asOf 各期应计未付罚息。
     */
    public List<BigDecimal> replay(List<Installment> schedule,
                                   List<PaymentRecord> payments,
                                   LocalDate asOf,
                                   BigDecimal annualPenaltyRate,
                                   DayCountConvention convention,
                                   int graceDays,
                                   PaymentAllocator allocator) {
        return replay(schedule, payments, java.util.List.of(), asOf,
                annualPenaltyRate, convention, graceDays, allocator);
    }

    /**
     * 重放全部还款（含冲正）：截至 asOf（含当日）已生效的冲正从其目标还款的
     * 有效金额中扣减，随后按生效日（同日按序号）逐笔刷新罚息并重新分摊。
     *
     * <p>冲正口径：冲正不删除原还款事实，而是把被冲金额视作"从未收到"——
     * 目标还款按扣减后的有效金额参与重放（整笔冲正则跳过）。
     * 故冲正生效日之前的历史重放（asOf 更早）不受影响；
     * 冲正生效日及之后的分摊、罚息、逾期按剩余有效流水整体重述重算。</p>
     */
    public List<BigDecimal> replay(List<Installment> schedule,
                                   List<PaymentRecord> payments,
                                   List<ReversalRecord> reversals,
                                   LocalDate asOf,
                                   BigDecimal annualPenaltyRate,
                                   DayCountConvention convention,
                                   int graceDays,
                                   PaymentAllocator allocator) {
        resetPaid(schedule);
        List<PaymentRecord> ordered = payments.stream()
                .sorted(Comparator.comparing((PaymentRecord p) -> p.effectiveDate)
                        .thenComparingLong(p -> p.sequence))
                .toList();
        java.util.Map<Long, BigDecimal> reversedByPayment = reversedByPaymentAsOf(reversals, asOf);
        for (PaymentRecord payment : ordered) {
            BigDecimal effectiveAmount = payment.amount
                    .subtract(reversedByPayment.getOrDefault(payment.sequence, BigDecimal.ZERO));
            // 整笔冲正：该笔还款视作从未存在，不分摊、不产生本金减基数事件
            payment.lines = java.util.List.of();
            if (effectiveAmount.signum() <= 0) {
                continue;
            }
            // 刷新截至本笔生效日（含当日，且不包含本笔自身）的罚息
            refreshPenalty(schedule, ordered, payment.effectiveDate, payment.sequence,
                    annualPenaltyRate, convention, graceDays, true);
            // allocate 既冲抵 paid* 又产出明细；回写流水（补录/回溯/冲正后历史分摊随之重算）。
            payment.lines = allocator.allocate(schedule, effectiveAmount, payment.effectiveDate);
        }
        return refreshPenalty(schedule, ordered, asOf, Long.MAX_VALUE,
                annualPenaltyRate, convention, graceDays, false);
    }

    /**
     * 汇总截至 asOf（含当日）每笔目标还款被冲正的金额合计。
     */
    private java.util.Map<Long, BigDecimal> reversedByPaymentAsOf(List<ReversalRecord> reversals,
                                                                  LocalDate asOf) {
        java.util.Map<Long, BigDecimal> result = new java.util.HashMap<>();
        for (ReversalRecord reversal : reversals) {
            if (reversal.effectiveDate.isAfter(asOf)) {
                continue;
            }
            result.merge(reversal.targetPaymentSequence, reversal.amount, BigDecimal::add);
        }
        return result;
    }

    /**
     * 依据已排序流水刷新各期罚息。
     *
     * <p>本金减基数时间线由"生效日严格早于 asOf 的还本明细"还原；
     * 已付罚息直接取重放状态 {@code paidPenalty}（由 {@code allocate} 按序维护，
     * 唯一事实来源，避免同一笔罚息被双重计数）。</p>
     *
     * @param currentSeq 当前待处理还款序号。在"逐笔刷新"模式（inFlight=true）下，
     *                   同日序号更小的还款已发生，其罚息冲抵应反映到 paidPenalty；
     *                   最终刷新（inFlight=false）包含所有还款。
     */
    private List<BigDecimal> refreshPenalty(List<Installment> schedule,
                                            List<PaymentRecord> ordered,
                                            LocalDate asOf, long currentSeq,
                                            BigDecimal annualPenaltyRate,
                                            DayCountConvention convention,
                                            int graceDays,
                                            boolean inFlight) {
        List<BigDecimal> result = new ArrayList<>();
        for (Installment installment : schedule) {
            List<PrincipalEvent> principalEvents = new ArrayList<>();
            for (PaymentRecord payment : ordered) {
                boolean earlierDate = payment.effectiveDate.isBefore(asOf);
                boolean sameDateEarlierSeq = inFlight
                        && payment.effectiveDate.isEqual(asOf)
                        && payment.sequence < currentSeq;
                if (!earlierDate && !sameDateEarlierSeq) {
                    continue;
                }
                BigDecimal principal = BigDecimal.ZERO;
                for (AllocationLine line : payment.lines) {
                    if (line.installmentSeq == installment.seq
                            && line.bucket == AllocationBucket.PRINCIPAL
                            && earlierDate) {
                        principal = principal.add(line.amount);
                    }
                }
                if (principal.signum() > 0) {
                    principalEvents.add(new PrincipalEvent(payment.effectiveDate, principal));
                }
            }
            BigDecimal accrued = accruedAsOf(installment, asOf, graceDays,
                    annualPenaltyRate, convention, principalEvents);
            // paidPenalty 不在此重算：由 allocate 按重放顺序维护
            installment.outstandingPenalty = accrued.subtract(installment.paidPenalty)
                    .max(BigDecimal.ZERO);
            result.add(installment.outstandingPenalty);
        }
        return result;
    }

    private void resetPaid(List<Installment> schedule) {
        for (Installment i : schedule) {
            i.paidPrincipal = BigDecimal.ZERO;
            i.paidInterest = BigDecimal.ZERO;
            i.paidFee = BigDecimal.ZERO;
            i.paidPenalty = BigDecimal.ZERO;
            i.outstandingPenalty = BigDecimal.ZERO;
        }
    }

    private BigDecimal accrue(BigDecimal base, BigDecimal annualRate,
                              DayCountConvention convention, long days) {
        if (days <= 0 || base.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return base.multiply(annualRate)
                .multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(convention.dayBase()), CALC_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 某期本金被冲抵事件。
     */
    public record PrincipalEvent(LocalDate date, BigDecimal amount) {
    }
}
