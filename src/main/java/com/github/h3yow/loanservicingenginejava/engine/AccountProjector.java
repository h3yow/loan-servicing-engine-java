package com.github.h3yow.loanservicingenginejava.engine;

import com.github.h3yow.loanservicingenginejava.config.EngineProperties;
import com.github.h3yow.loanservicingenginejava.domain.AccountEvent;
import com.github.h3yow.loanservicingenginejava.domain.AccountEvent.FeeCharged;
import com.github.h3yow.loanservicingenginejava.domain.AccountEvent.Opened;
import com.github.h3yow.loanservicingenginejava.domain.AccountEvent.PaymentReceived;
import com.github.h3yow.loanservicingenginejava.domain.AccountEvent.RiskTransition;
import com.github.h3yow.loanservicingenginejava.domain.AccountEvent.WrittenOff;
import com.github.h3yow.loanservicingenginejava.domain.AccountSnapshot;
import com.github.h3yow.loanservicingenginejava.domain.AccountStatus;
import com.github.h3yow.loanservicingenginejava.domain.AllocationLine;
import com.github.h3yow.loanservicingenginejava.domain.BalanceSnapshot;
import com.github.h3yow.loanservicingenginejava.domain.ComponentType;
import com.github.h3yow.loanservicingenginejava.domain.Installment;
import com.github.h3yow.loanservicingenginejava.domain.LoanAccount;
import com.github.h3yow.loanservicingenginejava.domain.PaymentReceipt;
import com.github.h3yow.loanservicingenginejava.domain.RiskAssessment;
import com.github.h3yow.loanservicingenginejava.domain.RiskState;
import com.github.h3yow.loanservicingenginejava.domain.Terms;
import com.github.h3yow.loanservicingenginejava.error.ErrorCode;
import com.github.h3yow.loanservicingenginejava.error.LoanEngineException;
import com.github.h3yow.loanservicingenginejava.risk.RiskRuleBook;
import com.github.h3yow.loanservicingenginejava.risk.RiskRuleRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 账户状态投影器：从仅追加事件确定性重放任意时刻的账户状态。
 * <ol>
 *   <li>生成还款计划；事件按（生效时间, 序号）稳定排序；</li>
 *   <li>沿时间轴处理费用与还款；还款分摊顺序固定为
 *       FEE → PENALTY → INTEREST → PRINCIPAL（同类内按到期先后）；</li>
 *   <li>逾期基数随还款变动，罚息按段重算；计息区间为
 *       [到期日+宽限期+1, 下一时点)，入账当日不计息；</li>
 *   <li>系统建议分类由各期最大 DPD 与评估时点的规则版本计算；</li>
 *   <li>守恒校验（未核销）：剩余本金 + 剩余利息 + 未付费用 + 应计罚息
 *       = 总待还，且任一分摊额不得超过应收。</li>
 * </ol>
 * 同一事件序列重放两次结果完全一致。
 */
@Component
public class AccountProjector {

    private static final Logger log = LoggerFactory.getLogger(AccountProjector.class);
    private static final Comparator<AccountEvent> ORDER =
            Comparator.comparing(AccountEvent::effectiveAt).thenComparing(AccountEvent::seq);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final ScheduleGenerator scheduleGenerator;
    private final PenaltyCalculator penaltyCalculator;
    private final RiskRuleRegistry riskRuleRegistry;
    private final EngineProperties properties;

    public AccountProjector(ScheduleGenerator scheduleGenerator,
                            PenaltyCalculator penaltyCalculator,
                            RiskRuleRegistry riskRuleRegistry,
                            EngineProperties properties) {
        this.scheduleGenerator = scheduleGenerator;
        this.penaltyCalculator = penaltyCalculator;
        this.riskRuleRegistry = riskRuleRegistry;
        this.properties = properties;
    }

    /** 可变重放状态。 */
    private static final class Ledger {
        String accountId;
        Terms terms;
        List<Installment> installments = new ArrayList<>();
        final List<BigDecimal> fees = new ArrayList<>();
        final List<BigDecimal> feesPaid = new ArrayList<>();
        final List<PaymentReceipt> receipts = new ArrayList<>();
        final List<RiskAssessment> riskHistory = new ArrayList<>();
        RiskState riskState = RiskState.NORMAL;
        boolean writtenOff;
        BigDecimal waivedInterest = ZERO;
        BigDecimal accruedPenalty = ZERO;
        LocalDate cursor;
    }

    public AccountSnapshot project(LoanAccount account, LocalDateTime asOf) {
        Ledger ledger = replay(account, asOf);
        return toSnapshot(ledger, asOf);
    }

    /**
     * 校验一笔拟入账还款并返回“入账后”的回执与快照（不落事件）。
     * 服务层在账户锁内调用：校验通过后再追加事件。
     */
    public QuoteResult quotePayment(LoanAccount account, LocalDateTime asOf,
                                    PaymentReceived candidate) {
        Ledger ledger = replay(account, asOf);

        BigDecimal amount = money(candidate.amount());
        Allocation allocation = allocate(ledger, asOf.toLocalDate(),
                amount, candidate.earlySettlement());
        List<AllocationLine> lines = allocation.lines();
        BigDecimal allocated = lines.stream().map(AllocationLine::amount)
                .reduce(ZERO, BigDecimal::add);

        PaymentReceipt receipt = new PaymentReceipt(candidate.idempotencyKey(),
                ledger.accountId, amount, candidate.effectiveAt(),
                List.copyOf(lines), allocated, candidate.earlySettlement(),
                allocation.waivedThisTime());

        ledger.receipts.add(receipt);
        log.info("[quote] account={} key={} amount={} early={} allocated={} waived={} -> status",
                ledger.accountId, candidate.idempotencyKey(), amount,
                candidate.earlySettlement(), allocated, ledger.waivedInterest);

        AccountSnapshot snapshot = toSnapshot(ledger, asOf);
        return new QuoteResult(receipt, snapshot);
    }

    /** quotePayment 的返回：回执 + 入账后快照。 */
    public record QuoteResult(PaymentReceipt receipt, AccountSnapshot snapshotAfter) {
    }

    /**
     * 只读应缴报价，服务层入账前用于金额校验（不改变重放状态）。
     *
     * @param earlySettle true 时应缴 = 费用 + 罚息 + 全部剩余本金 + 已到期期利息；
     *                    false 时应缴 = 费用 + 罚息 + 到期期（到期日 &lt;= asOf）的本息
     */
    public BigDecimal requiredAmount(LoanAccount account, LocalDateTime asOf,
                                     boolean earlySettle) {
        Ledger ledger = replay(account, asOf);
        BigDecimal feeDue = ZERO;
        for (int i = 0; i < ledger.fees.size(); i++) {
            feeDue = money(feeDue.add(ledger.fees.get(i).subtract(ledger.feesPaid.get(i))));
        }
        BigDecimal due = feeDue.add(money(ledger.accruedPenalty));
        LocalDate date = asOf.toLocalDate();
        for (Installment ins : ledger.installments) {
            if (earlySettle) {
                due = due.add(ins.scheduledPrincipal().subtract(ins.paidPrincipal()));
                if (!ins.dueDate().isAfter(date)) {
                    due = due.add(ins.scheduledInterest().subtract(ins.paidInterest()));
                }
            } else if (!ins.dueDate().isAfter(date)) {
                due = due.add(ins.scheduledPrincipal().subtract(ins.paidPrincipal()))
                        .add(ins.scheduledInterest().subtract(ins.paidInterest()));
            }
        }
        return money(due);
    }

    // ------------------------------------------------------------------
    // 事件重放
    // ------------------------------------------------------------------

    private Ledger replay(LoanAccount account, LocalDateTime asOf) {
        List<AccountEvent> events = account.events().stream()
                .filter(e -> !e.effectiveAt().isAfter(asOf))
                .sorted(ORDER)
                .toList();
        Ledger ledger = new Ledger();
        ledger.accountId = account.getAccountId();
        for (AccountEvent event : events) {
            switch (event) {
                case Opened e -> applyOpened(ledger, e, asOf);
                case FeeCharged e -> applyFee(ledger, e);
                case PaymentReceived e -> applyPayment(ledger, e);
                case RiskTransition e -> applyRisk(ledger, e);
                case WrittenOff e -> applyWrittenOff(ledger, e);
            }
        }
        accruePenaltyUntil(ledger, asOf.toLocalDate());
        return ledger;
    }

    private void applyOpened(Ledger ledger, Opened e, LocalDateTime asOf) {
        ledger.terms = e.terms();
        ledger.installments = new ArrayList<>(
                scheduleGenerator.generate(e.terms(), e.firstDueDate()));
        ledger.cursor = e.effectiveAt().toLocalDate();
    }

    private void applyFee(Ledger ledger, FeeCharged e) {
        accruePenaltyUntil(ledger, e.effectiveAt().toLocalDate());
        ledger.fees.add(money(e.amount()));
        ledger.feesPaid.add(ZERO);
    }

    private void applyPayment(Ledger ledger, PaymentReceived e) {
        accruePenaltyUntil(ledger, e.effectiveAt().toLocalDate());
        BigDecimal amount = money(e.amount());
        Allocation allocation = allocate(ledger, e.effectiveAt().toLocalDate(),
                amount, e.earlySettlement());
        List<AllocationLine> lines = allocation.lines();
        BigDecimal allocated = lines.stream().map(AllocationLine::amount)
                .reduce(ZERO, BigDecimal::add);
        ledger.receipts.add(new PaymentReceipt(e.idempotencyKey(), ledger.accountId,
                amount, e.effectiveAt(), List.copyOf(lines), allocated,
                e.earlySettlement(), allocation.waivedThisTime()));
    }

    private void applyRisk(Ledger ledger, RiskTransition e) {
        accruePenaltyUntil(ledger, e.effectiveAt().toLocalDate());
        ledger.riskState = e.toState();
        ledger.riskHistory.add(new RiskAssessment(e.effectiveAt(),
                e.fromState(), e.toState(), e.ruleVersion(), e.reason()));
    }

    private void applyWrittenOff(Ledger ledger, WrittenOff e) {
        accruePenaltyUntil(ledger, e.effectiveAt().toLocalDate());
        RiskState from = ledger.riskState;
        ledger.writtenOff = true;
        ledger.riskState = RiskState.WRITTEN_OFF;
        ledger.riskHistory.add(new RiskAssessment(e.effectiveAt(),
                from, RiskState.WRITTEN_OFF, "write-off", e.reason()));
    }

    // ------------------------------------------------------------------
    // 罚息：沿时间轴走到 target（不含 target 当天）
    // ------------------------------------------------------------------

    private void accruePenaltyUntil(Ledger ledger, LocalDate target) {
        if (ledger.terms == null || ledger.writtenOff) {
            ledger.cursor = target;
            return;
        }
        LocalDate from = ledger.cursor == null ? target : ledger.cursor;
        if (!target.isAfter(from)) {
            return;
        }
        if ("OUTSTANDING".equalsIgnoreCase(properties.getPenaltyBasis())) {
            accrueOutstandingPenalty(ledger, from, target);
        } else {
            accrueDuePenalty(ledger, from, target);
        }
        ledger.cursor = target;
    }

    /** DUE 口径：逐期以“未付应还本金 + 未付应还利息”为基数。 */
    private void accrueDuePenalty(Ledger ledger, LocalDate from, LocalDate target) {
        for (Installment ins : ledger.installments) {
            if (ins.settled()) {
                continue;
            }
            LocalDate start = penaltyCalculator.penaltyStartDate(ins.dueDate());
            LocalDate segFrom = from.isAfter(start) ? from : start;
            if (!target.isAfter(segFrom)) {
                continue;
            }
            BigDecimal base = ins.scheduledPrincipal().subtract(ins.paidPrincipal())
                    .add(ins.scheduledInterest().subtract(ins.paidInterest()));
            int days = penaltyCalculator.accrualDays(segFrom, target);
            ledger.accruedPenalty = money(ledger.accruedPenalty
                    .add(penaltyCalculator.segmentPenalty(base, days)));
        }
    }

    /** OUTSTANDING 口径：账户一旦存在逾期期，以全部剩余本金为基数。 */
    private void accrueOutstandingPenalty(Ledger ledger, LocalDate from, LocalDate target) {
        LocalDate earliestStart = null;
        for (Installment ins : ledger.installments) {
            if (ins.settled()) {
                continue;
            }
            LocalDate start = penaltyCalculator.penaltyStartDate(ins.dueDate());
            if (!target.isAfter(start)) {
                continue;
            }
            if (earliestStart == null || start.isBefore(earliestStart)) {
                earliestStart = start;
            }
        }
        if (earliestStart == null) {
            return;
        }
        LocalDate segFrom = from.isAfter(earliestStart) ? from : earliestStart;
        if (target.isAfter(segFrom)) {
            BigDecimal base = ledger.installments.stream()
                    .map(i -> i.scheduledPrincipal().subtract(i.paidPrincipal()))
                    .reduce(ZERO, BigDecimal::add);
            int days = penaltyCalculator.accrualDays(segFrom, target);
            ledger.accruedPenalty = money(ledger.accruedPenalty
                    .add(penaltyCalculator.segmentPenalty(base, days)));
        }
    }

    // ------------------------------------------------------------------
    // 还款分摊：FEE → PENALTY → INTEREST → PRINCIPAL（一次性完成全部变更）
    // ------------------------------------------------------------------

    /** 分摊结果：明细 + 本次提前结清减免的利息。 */
    private record Allocation(List<AllocationLine> lines, BigDecimal waivedThisTime) {
    }

    private Allocation allocate(Ledger ledger, LocalDate payDate,
                                BigDecimal amount, boolean earlySettle) {
        List<AllocationLine> lines = new ArrayList<>();
        BigDecimal remaining = amount;

        // 1. 费用（按计收先后）
        for (int i = 0; i < ledger.fees.size() && remaining.signum() > 0; i++) {
            BigDecimal unpaid = ledger.fees.get(i).subtract(ledger.feesPaid.get(i));
            if (unpaid.signum() <= 0) {
                continue;
            }
            BigDecimal take = min(remaining, unpaid);
            ledger.feesPaid.set(i, money(ledger.feesPaid.get(i).add(take)));
            lines.add(new AllocationLine(ComponentType.FEE, 0, take));
            remaining = money(remaining.subtract(take));
        }

        // 2. 已计提罚息（资金池）
        if (remaining.signum() > 0 && ledger.accruedPenalty.signum() > 0) {
            BigDecimal take = min(remaining, ledger.accruedPenalty);
            ledger.accruedPenalty = money(ledger.accruedPenalty.subtract(take));
            lines.add(new AllocationLine(ComponentType.PENALTY, 0, take));
            remaining = money(remaining.subtract(take));
        }

        BigDecimal waivedThisTime = ZERO;
        if (earlySettle) {
            BigDecimal[] result = allocateEarlySettlement(ledger, payDate, lines, remaining);
            remaining = result[0];
            waivedThisTime = result[1];
            if (remaining.signum() != 0) {
                throw new IllegalStateException(
                        "early settlement quote mismatch, leftover=" + remaining);
            }
        } else {
            remaining = allocateCurrentDue(ledger, payDate, lines, remaining);
            if (remaining.signum() > 0) {
                throw new IllegalStateException(
                        "payment exceeds current due, leftover=" + remaining);
            }
        }
        return new Allocation(lines, waivedThisTime);
    }

    /** 普通还款：仅可偿还到期日 <= 还款日的期；期内先利息后本金。 */
    private BigDecimal allocateCurrentDue(Ledger ledger, LocalDate payDate,
                                          List<AllocationLine> lines, BigDecimal remaining) {
        for (int i = 0; i < ledger.installments.size() && remaining.signum() > 0; i++) {
            Installment ins = ledger.installments.get(i);
            if (ins.dueDate().isAfter(payDate)) {
                break;
            }
            BigDecimal unpaidInterest = ins.scheduledInterest().subtract(ins.paidInterest());
            if (unpaidInterest.signum() > 0) {
                BigDecimal take = min(remaining, unpaidInterest);
                ledger.installments.set(i, addPaid(ins, ZERO, take, ZERO));
                lines.add(new AllocationLine(ComponentType.INTEREST, ins.periodNo(), take));
                remaining = money(remaining.subtract(take));
            }
            ins = ledger.installments.get(i);
            BigDecimal unpaidPrincipal = ins.scheduledPrincipal().subtract(ins.paidPrincipal());
            if (remaining.signum() > 0 && unpaidPrincipal.signum() > 0) {
                BigDecimal take = min(remaining, unpaidPrincipal);
                ledger.installments.set(i, addPaid(ins, take, ZERO, ZERO));
                lines.add(new AllocationLine(ComponentType.PRINCIPAL, ins.periodNo(), take));
                remaining = money(remaining.subtract(take));
            }
        }
        return remaining;
    }

    /**
     * 提前结清：偿还全部剩余本金与“已到期期”的应还利息；
     * 未到期期的计划利息被减免（累计进 waivedInterest）。
     * 服务层保证还款额恰好等于结清报价。
     *
     * @return [剩余金额, 本次减免利息]
     */
    private BigDecimal[] allocateEarlySettlement(Ledger ledger, LocalDate payDate,
                                                 List<AllocationLine> lines,
                                                 BigDecimal remaining) {
        BigDecimal waived = ZERO;
        for (int i = 0; i < ledger.installments.size(); i++) {
            Installment ins = ledger.installments.get(i);
            boolean due = !ins.dueDate().isAfter(payDate);
            BigDecimal unpaidInterest = ins.scheduledInterest().subtract(ins.paidInterest());
            BigDecimal unpaidPrincipal = ins.scheduledPrincipal().subtract(ins.paidPrincipal());

            if (due && unpaidInterest.signum() > 0) {
                BigDecimal take = min(remaining, unpaidInterest);
                ledger.installments.set(i, addPaid(ins, ZERO, take, ZERO));
                lines.add(new AllocationLine(ComponentType.INTEREST, ins.periodNo(), take));
                remaining = money(remaining.subtract(take));
                ins = ledger.installments.get(i);
            }
            if (unpaidPrincipal.signum() > 0) {
                BigDecimal take = min(remaining, unpaidPrincipal);
                ledger.installments.set(i, addPaid(ins, take, ZERO, ZERO));
                lines.add(new AllocationLine(ComponentType.PRINCIPAL, ins.periodNo(), take));
                remaining = money(remaining.subtract(take));
                ins = ledger.installments.get(i);
            }
            // 未到期期：本金已清，剩余计划利息予以减免（不记为已付），期标记结清
            BigDecimal stillInterest = ins.scheduledInterest().subtract(ins.paidInterest());
            BigDecimal stillPrincipal = ins.scheduledPrincipal().subtract(ins.paidPrincipal());
            if (!due && stillPrincipal.signum() == 0 && stillInterest.signum() > 0) {
                waived = money(waived.add(stillInterest));
                ledger.installments.set(i, new Installment(ins.periodNo(), ins.dueDate(),
                        ins.scheduledPrincipal(), ins.scheduledInterest(),
                        ins.paidPrincipal(), ins.paidInterest(),
                        ins.paidFee(), ins.paidPenalty(), true));
            }
        }
        ledger.waivedInterest = money(ledger.waivedInterest.add(waived));
        if (waived.signum() > 0) {
            log.info("[early-settle] account={} waivedInterest={}", ledger.accountId, waived);
        }
        return new BigDecimal[]{remaining, waived};
    }

    private Installment addPaid(Installment ins, BigDecimal dPrincipal,
                                BigDecimal dInterest, BigDecimal dPenalty) {
        BigDecimal pp = money(ins.paidPrincipal().add(dPrincipal));
        BigDecimal pi = money(ins.paidInterest().add(dInterest));
        BigDecimal ppe = money(ins.paidPenalty().add(dPenalty));
        boolean settled = pp.compareTo(ins.scheduledPrincipal()) == 0
                && pi.compareTo(ins.scheduledInterest()) == 0;
        return new Installment(ins.periodNo(), ins.dueDate(),
                ins.scheduledPrincipal(), ins.scheduledInterest(), pp, pi,
                ins.paidFee(), ppe, settled);
    }

    // ------------------------------------------------------------------
    // 快照组装 + 守恒校验
    // ------------------------------------------------------------------

    private AccountSnapshot toSnapshot(Ledger ledger, LocalDateTime asOf) {
        BigDecimal outstandingPrincipal = ZERO;
        BigDecimal outstandingInterest = ZERO;
        for (Installment ins : ledger.installments) {
            if (ins.settled()) {
                // 已结清期（含提前结清时利息被减免的期）不再产生应收
                continue;
            }
            outstandingPrincipal = outstandingPrincipal
                    .add(ins.scheduledPrincipal().subtract(ins.paidPrincipal()));
            outstandingInterest = outstandingInterest
                    .add(ins.scheduledInterest().subtract(ins.paidInterest()));
        }
        BigDecimal outstandingFee = ZERO;
        for (int i = 0; i < ledger.fees.size(); i++) {
            outstandingFee = outstandingFee
                    .add(ledger.fees.get(i).subtract(ledger.feesPaid.get(i)));
        }
        outstandingPrincipal = money(outstandingPrincipal);
        outstandingInterest = money(outstandingInterest);
        outstandingFee = money(outstandingFee);
        BigDecimal penalty = money(ledger.accruedPenalty);
        BigDecimal totalDue = money(outstandingPrincipal.add(outstandingInterest)
                .add(outstandingFee).add(penalty));

        AccountStatus status;
        if (ledger.writtenOff) {
            status = AccountStatus.WRITTEN_OFF;
        } else if (totalDue.signum() == 0 && allInstallmentsSettled(ledger)) {
            status = AccountStatus.SETTLED;
        } else {
            status = AccountStatus.ACTIVE;
        }

        int dpd = maxDpd(ledger, asOf.toLocalDate());
        RiskRuleBook currentBook = riskRuleRegistry.effectiveAt(asOf);
        RiskState suggested = currentBook.classifyByDpd(dpd);
        if (status == AccountStatus.SETTLED) {
            suggested = RiskState.NORMAL;
        }
        if (ledger.writtenOff) {
            suggested = RiskState.WRITTEN_OFF;
        }

        verifyConservation(ledger, outstandingPrincipal, outstandingInterest,
                outstandingFee, penalty, totalDue);

        return new AccountSnapshot(ledger.accountId, ledger.terms,
                status, ledger.riskState, suggested,
                List.copyOf(ledger.installments),
                new BalanceSnapshot(outstandingPrincipal, outstandingInterest,
                        outstandingFee, penalty, totalDue),
                List.copyOf(ledger.receipts), List.copyOf(ledger.riskHistory),
                money(ledger.waivedInterest), asOf);
    }

    private boolean allInstallmentsSettled(Ledger ledger) {
        return !ledger.installments.isEmpty()
                && ledger.installments.stream().allMatch(Installment::settled);
    }

    /** 最大逾期天数：未结清期 max(0, 评估日 - 到期日 - 宽限期)。 */
    private int maxDpd(Ledger ledger, LocalDate asOfDate) {
        int max = 0;
        int grace = properties.getGraceDays();
        for (Installment ins : ledger.installments) {
            if (ins.settled()) {
                continue;
            }
            long overdueDays = ChronoUnit.DAYS.between(ins.dueDate(), asOfDate) - grace;
            if (overdueDays > max) {
                max = (int) overdueDays;
            }
        }
        return max;
    }

    private void verifyConservation(Ledger ledger,
                                    BigDecimal outstandingPrincipal,
                                    BigDecimal outstandingInterest,
                                    BigDecimal outstandingFee,
                                    BigDecimal penalty,
                                    BigDecimal totalDue) {
        if (ledger.writtenOff || ledger.terms == null) {
            return;
        }
        BigDecimal sum = money(outstandingPrincipal.add(outstandingInterest)
                .add(outstandingFee).add(penalty));
        if (sum.compareTo(totalDue) != 0) {
            throw new IllegalStateException("conservation violated for " + ledger.accountId
                    + ": components=" + sum + " totalDue=" + totalDue);
        }
        BigDecimal paidPrincipal = ledger.installments.stream()
                .map(Installment::paidPrincipal).reduce(ZERO, BigDecimal::add);
        if (paidPrincipal.compareTo(ledger.terms.principal()) > 0) {
            throw new IllegalStateException(
                    "over-allocated principal for " + ledger.accountId);
        }
        for (Installment ins : ledger.installments) {
            if (ins.paidInterest().compareTo(ins.scheduledInterest()) > 0
                    || ins.paidPrincipal().compareTo(ins.scheduledPrincipal()) > 0) {
                throw new IllegalStateException("over-allocated installment "
                        + ins.periodNo() + " for " + ledger.accountId);
            }
        }
        if (ledger.accruedPenalty.signum() < 0) {
            throw new IllegalStateException(
                    "negative penalty pool for " + ledger.accountId);
        }
    }

    private static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
