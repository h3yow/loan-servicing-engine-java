package com.github.h3yow.loanservicingenginejava.engine;

import com.github.h3yow.loanservicingenginejava.domain.AllocationBucket;
import com.github.h3yow.loanservicingenginejava.domain.AllocationLine;
import com.github.h3yow.loanservicingenginejava.domain.DayCountConvention;
import com.github.h3yow.loanservicingenginejava.domain.Installment;
import com.github.h3yow.loanservicingenginejava.domain.PaymentRecord;
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
 *   <li>每次都在计划副本上从计划与"截至 asOf 已生效的净流水"重放重算（纯函数、非增量累加），
 *       补录/回溯、冲正时间点重述后结论一致且不重复累计，真实账本与流水不被修改。</li>
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
     * 时间点重放（纯函数）：只纳入生效日 ≤ asOf 的有效还款，在计划副本上按生效日
     * （同日按序号）逐笔刷新罚息并重新分摊。不修改任何真实流水，也不修改入参计划。
     *
     * <p>注意：调用方必须先按"查询时点"过滤冲正（生效日晚于 asOf 的冲正不生效），
     * 再把净有效还款视图传入；{@code payments} 本身的生效日同样按 asOf 过滤，
     * 因此"按历史日期查询"得到的就是当时真实口径。</p>
     *
     * @return 重放结果（各期副本，含 paid* 与截至 asOf 的 outstandingPenalty；
     *         以及每笔有效还款在该时点重算得到的分摊明细，按流水序号索引）。
     */
    public ReplayResult replayAt(List<Installment> schedule,
                                 List<PaymentRecord> payments,
                                 LocalDate asOf,
                                 BigDecimal annualPenaltyRate,
                                 DayCountConvention convention,
                                 int graceDays,
                                 PaymentAllocator allocator) {
        List<Installment> work = schedule.stream().map(Installment::copy).toList();
        resetPaid(work);
        List<PaymentRecord> ordered = payments.stream()
                .filter(p -> !p.effectiveDate.isAfter(asOf))
                .sorted(Comparator.comparing((PaymentRecord p) -> p.effectiveDate)
                        .thenComparingLong(p -> p.sequence))
                .toList();
        java.util.Map<Long, List<AllocationLine>> linesBySeq = new java.util.HashMap<>();
        for (PaymentRecord payment : ordered) {
            // 刷新截至本笔生效日（含当日，且不包含本笔自身）的罚息
            refreshPenalty(work, ordered, payment.effectiveDate, payment.sequence,
                    annualPenaltyRate, convention, graceDays, true);
            // allocate 既冲抵 paid* 又产出明细（明细随结果返回，不回写真实流水）
            List<AllocationLine> lines = allocator.allocate(work, payment.amount, payment.effectiveDate);
            linesBySeq.put(payment.sequence, lines);
        }
        List<BigDecimal> outstanding = refreshPenalty(work, ordered, asOf, Long.MAX_VALUE,
                annualPenaltyRate, convention, graceDays, false);
        return new ReplayResult(work, outstanding, linesBySeq);
    }

    /**
     * 时间点重放结果：各期派生口径副本 + 每笔有效还款的重算分摊（按流水序号索引）。
     */
    public static final class ReplayResult {
        public final List<Installment> installments;
        public final List<BigDecimal> outstandingPenalty;
        public final java.util.Map<Long, List<AllocationLine>> linesBySequence;

        public ReplayResult(List<Installment> installments, List<BigDecimal> outstandingPenalty,
                            java.util.Map<Long, List<AllocationLine>> linesBySequence) {
            this.installments = installments;
            this.outstandingPenalty = outstandingPenalty;
            this.linesBySequence = linesBySequence;
        }
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
