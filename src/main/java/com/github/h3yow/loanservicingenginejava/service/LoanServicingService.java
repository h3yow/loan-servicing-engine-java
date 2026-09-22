package com.github.h3yow.loanservicingenginejava.service;

import com.github.h3yow.loanservicingenginejava.config.ServicingProperties;
import com.github.h3yow.loanservicingenginejava.domain.AccountStatus;
import com.github.h3yow.loanservicingenginejava.domain.AllocationBucket;
import com.github.h3yow.loanservicingenginejava.domain.AllocationLine;
import com.github.h3yow.loanservicingenginejava.domain.IdempotencyRecord;
import com.github.h3yow.loanservicingenginejava.domain.Installment;
import com.github.h3yow.loanservicingenginejava.domain.LoanAccount;
import com.github.h3yow.loanservicingenginejava.domain.LoanTerms;
import com.github.h3yow.loanservicingenginejava.domain.PaymentCommand;
import com.github.h3yow.loanservicingenginejava.domain.PaymentOutcome;
import com.github.h3yow.loanservicingenginejava.domain.PaymentRecord;
import com.github.h3yow.loanservicingenginejava.domain.RejectReason;
import com.github.h3yow.loanservicingenginejava.domain.RiskState;
import com.github.h3yow.loanservicingenginejava.domain.RiskTransitionRecord;
import com.github.h3yow.loanservicingenginejava.engine.OverdueEvaluator;
import com.github.h3yow.loanservicingenginejava.engine.PaymentAllocator;
import com.github.h3yow.loanservicingenginejava.engine.PenaltyCalculator;
import com.github.h3yow.loanservicingenginejava.engine.RiskRuleBook;
import com.github.h3yow.loanservicingenginejava.engine.RiskRuleVersion;
import com.github.h3yow.loanservicingenginejava.engine.RiskStateMachine;
import com.github.h3yow.loanservicingenginejava.engine.ScheduleGenerator;
import com.github.h3yow.loanservicingenginejava.store.LoanAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 贷款服务门面：开户、还款入账、逾期/风险重估、风险迁移与账本查询。
 *
 * <p>账户派生状态（各期已还金额、罚息、余额）永远由"计划 + 还款流水"按生效日期
 * 重放得到（重放不改账本）；因此补录/回溯入账天然重新计算且可复现，
 * 重放相同输入得到相同结果。</p>
 *
 * <p>守恒恒等式（任意时刻）：
 * 计划总额(本金+利息+费用) + 应计罚息毛额 = 已收还款总额 + 剩余应还（计划未还 + 未付罚息）。</p>
 */
@Service
public class LoanServicingService {

    private static final Logger log = LoggerFactory.getLogger(LoanServicingService.class);

    private final LoanAccountRepository repository;
    private final ScheduleGenerator scheduleGenerator;
    private final PenaltyCalculator penaltyCalculator;
    private final OverdueEvaluator overdueEvaluator;
    private final PaymentAllocator allocator;
    private final RiskRuleBook riskRuleBook;
    private final RiskStateMachine riskStateMachine;
    private final ServicingProperties properties;

    public LoanServicingService(LoanAccountRepository repository,
                                ScheduleGenerator scheduleGenerator,
                                PenaltyCalculator penaltyCalculator,
                                OverdueEvaluator overdueEvaluator,
                                PaymentAllocator allocator,
                                RiskRuleBook riskRuleBook,
                                RiskStateMachine riskStateMachine,
                                ServicingProperties properties) {
        this.repository = repository;
        this.scheduleGenerator = scheduleGenerator;
        this.penaltyCalculator = penaltyCalculator;
        this.overdueEvaluator = overdueEvaluator;
        this.allocator = allocator;
        this.riskRuleBook = riskRuleBook;
        this.riskStateMachine = riskStateMachine;
        this.properties = properties;
    }

    /**
     * 开户并生成还款计划。
     */
    public LoanAccount openAccount(String accountId, LoanTerms terms) {
        validateTerms(accountId, terms);
        LoanAccount account = new LoanAccount(accountId, terms);
        account.installments.addAll(scheduleGenerator.generate(terms));
        account.ruleVersion = riskRuleBook.latestVersion();
        account.riskStateDate = terms.originationDate;
        repository.saveAccount(account);
        log.info("[开户] accountId={} principal={} rate={} n={} method={} firstDue={} feeRate={} 生成计划{}期",
                accountId, terms.principal, terms.annualRate, terms.installmentCount,
                terms.method, terms.firstDueDate, terms.feeRate, account.installments.size());
        return snapshotAccount(account, LocalDate.now());
    }

    /**
     * 还款入账。重复幂等键返回既有结果（duplicate=true）；键冲突、超额、不可入账均拒绝。
     */
    public PaymentOutcome postPayment(PaymentCommand command, LocalDateTime submittedAt) {
        validateCommand(command, submittedAt);
        LocalDate effectiveDate = resolveEffectiveDate(command.effectiveDate, submittedAt);
        log.info("[还款请求] accountId={} key={} amount={} effectiveDate={} submittedAt={}",
                command.accountId, command.idempotencyKey, command.amount, effectiveDate, submittedAt);

        return repository.withAccountAndKey(command.accountId, command.idempotencyKey, account -> {
            if (account.status != AccountStatus.ACTIVE) {
                throw new ServicingException(RejectReason.ACCOUNT_NOT_POSTABLE,
                        "账户当前状态不可入账: " + account.status);
            }
            IdempotencyRecord existing = repository.idempotency(command.idempotencyKey).orElse(null);
            if (existing != null) {
                IdempotencyView view = IdempotencyView.of(existing, command);
                if (!view.sameRequest()) {
                    throw new ServicingException(RejectReason.IDEMPOTENCY_KEY_CONFLICT,
                            "幂等键已被其它请求占用: " + command.idempotencyKey
                                    + "（账户/金额不一致）");
                }
                LocalDate originalDate = repository.paymentsOf(account.accountId).stream()
                        .filter(p -> p.sequence == existing.paymentSequence)
                        .map(p -> p.effectiveDate)
                        .findFirst().orElse(effectiveDate);
                ReplayState state = replay(account, originalDate);
                log.info("[还款重复] key={} 命中既有流水 seq={} 生效日={} 不重复扣款; 剩余应还={} 账户状态={}",
                        command.idempotencyKey, existing.paymentSequence, originalDate,
                        state.totalOutstanding(), account.status);
                return new PaymentOutcome(true, command.idempotencyKey,
                        recordLines(account.accountId, existing.paymentSequence),
                        command.amount, state.totalOutstanding(), account.status);
            }

            ReplayState before = replay(account, effectiveDate);
            BigDecimal owed = before.totalOutstanding();
            if (command.amount.compareTo(owed) > 0) {
                throw new ServicingException(RejectReason.OVERPAYMENT,
                        "还款金额 " + command.amount + " 超过剩余应还 " + owed);
            }

            long seq = repository.nextSequence();
            // 明细留空：保存后由重放按生效日统一重新分摊（保证补录/回溯可重算）
            PaymentRecord record = new PaymentRecord(command.idempotencyKey, command.amount,
                    effectiveDate, submittedAt.toLocalDate(), seq, new ArrayList<>());
            repository.addPayment(account.accountId, record);
            repository.putIdempotency(command.idempotencyKey, account.accountId,
                    command.amount, seq);

            // 重放：重新分摊所有流水（含本笔），并把结果回写各流水
            ReplayState after = replay(account, effectiveDate);
            List<AllocationLine> lines = record.lines.stream()
                    .map(l -> new AllocationLine(l.installmentSeq, l.bucket, l.amount))
                    .toList();
            verifyFullyAllocated(command.amount, lines);
            if (after.totalOutstanding().signum() == 0) {
                account.status = AccountStatus.SETTLED;
            }
            log.info("[还款入账] key={} seq={} 生效日={} 分摊{}条 本次分摊={} 剩余应还={} 账户状态={}",
                    command.idempotencyKey, seq, effectiveDate, lines.size(), command.amount,
                    after.totalOutstanding(), account.status);
            lines.forEach(l -> log.info("  分摊: 期号={} 桶={} 金额={}",
                    l.installmentSeq, l.bucket, l.amount));
            verifyConservation(account, after);
            return new PaymentOutcome(false, command.idempotencyKey, lines,
                    command.amount, after.totalOutstanding(), account.status);
        });
    }

    /**
     * 手动风险状态迁移：经状态机白名单校验，保留判定时规则版本；非法迁移明确拒绝。
     */
    public RiskTransitionRecord transitionRisk(String accountId, RiskState target,
                                               LocalDate effectiveDate, String reason) {
        if (target == null) {
            throw new ServicingException(RejectReason.VALIDATION_ERROR, "目标风险状态不能为空");
        }
        LocalDate asOf = effectiveDate != null ? effectiveDate : LocalDate.now();
        return repository.withAccount(accountId, account -> {
            if (account.status == AccountStatus.CLOSED) {
                throw new ServicingException(RejectReason.ACCOUNT_NOT_POSTABLE,
                        "已关账账户不允许风险迁移");
            }
            RiskState from = account.riskState;
            if (!riskStateMachine.canTransition(from, target)) {
                throw new ServicingException(RejectReason.ILLEGAL_RISK_TRANSITION,
                        "非法风险迁移: " + from + " -> " + target);
            }
            RiskRuleVersion rule = riskRuleBook.versionAt(asOf)
                    .or(() -> riskRuleBook.byVersion(account.ruleVersion))
                    .orElseThrow(() -> new ServicingException(RejectReason.RULE_VERSION_UNKNOWN,
                            "无法确定生效规则版本: " + asOf));
            RiskTransitionRecord r = new RiskTransitionRecord(asOf, from, target,
                    rule.version, reason != null ? reason : "MANUAL");
            applyRiskTransition(account, r);
            log.info("[风险迁移] accountId={} {} -> {} 规则版本={} 生效日={} 原因={}",
                    accountId, from, target, rule.version, asOf, r.reason);
            return r;
        });
    }

    /**
     * 按生效日期与当时规则版本自动重估风险分类。规则升级不改写历史判定。
     */
    public RiskTransitionRecord reevaluateRisk(String accountId, LocalDate asOf) {
        LocalDate date = asOf != null ? asOf : LocalDate.now();
        return repository.withAccount(accountId, account -> {
            if (account.status == AccountStatus.CLOSED) {
                throw new ServicingException(RejectReason.ACCOUNT_NOT_POSTABLE,
                        "已关账账户不允许风险重估");
            }
            RiskRuleVersion rule = riskRuleBook.versionAt(date)
                    .orElseThrow(() -> new ServicingException(RejectReason.RULE_VERSION_UNKNOWN,
                            "该日期无生效规则版本: " + date));
            ReplayState state = replay(account, date);
            int overdueDays = overdueEvaluator.maxOverdueDays(state.schedule, date,
                    properties.getGraceDays(), properties.isExtendOverdueToBusinessDay());
            RiskState target = riskRuleBook.classify(rule, overdueDays, account);
            RiskState from = account.riskState;
            RiskTransitionRecord r;
            if (from == target) {
                r = new RiskTransitionRecord(date, from, target, rule.version,
                        "UNCHANGED(" + overdueDays + "d)");
                log.info("[风险重估] accountId={} 关键依据: 逾期天数={} 宽限期={} 规则版本={} => 分类保持{}",
                        accountId, overdueDays, properties.getGraceDays(), rule.version, target);
            } else {
                if (!riskStateMachine.canTransition(from, target)) {
                    throw new ServicingException(RejectReason.ILLEGAL_RISK_TRANSITION,
                            "自动分类产生非法迁移: " + from + " -> " + target
                                    + "（逾期天数=" + overdueDays + "，需人工处理）");
                }
                r = new RiskTransitionRecord(date, from, target, rule.version,
                        "AUTO(" + overdueDays + "d)");
                applyRiskTransition(account, r);
                log.info("[风险重估] accountId={} 关键依据: 逾期天数={} 宽限期={} 规则版本={} => {} -> {}",
                        accountId, overdueDays, properties.getGraceDays(), rule.version, from, target);
            }
            return r;
        });
    }

    /**
     * 查询账户：返回基于当前日期重放的快照（含各期已还与罚息工作口径）。
     */
    public LoanAccount getAccount(String accountId) {
        repository.requireAccount(accountId);
        return repository.withAccount(accountId, a -> snapshotAccount(a, LocalDate.now()));
    }

    /**
     * 查询账户完整视图：按 asOf 重放，返回剩余本金/利息/费用/罚息与流水、风险历史。
     */
    public com.github.h3yow.loanservicingenginejava.domain.AccountView getAccountView(
            String accountId, LocalDate asOf) {
        repository.requireAccount(accountId);
        LocalDate date = asOf != null ? asOf : LocalDate.now();
        return repository.withAccount(accountId, a -> {
            ReplayState state = replay(a, date);
            List<Installment> copies = state.schedule.stream().map(Installment::copy).toList();
            BigDecimal rp = BigDecimal.ZERO;
            BigDecimal ri = BigDecimal.ZERO;
            BigDecimal rf = BigDecimal.ZERO;
            for (Installment i : state.schedule) {
                rp = rp.add(i.scheduledPrincipal.subtract(i.paidPrincipal).max(BigDecimal.ZERO));
                ri = ri.add(i.scheduledInterest.subtract(i.paidInterest).max(BigDecimal.ZERO));
                rf = rf.add(i.scheduledFee.subtract(i.paidFee).max(BigDecimal.ZERO));
            }
            return new com.github.h3yow.loanservicingenginejava.domain.AccountView(
                    a.accountId, a.status, a.riskState, a.ruleVersion, a.riskStateDate, date,
                    copies, rp, ri, rf, state.penaltyOutstanding(), state.totalOutstanding(),
                    repository.paymentsOf(a.accountId).stream().map(this::copyRecord).toList(),
                    List.copyOf(repository.riskHistoryOf(a.accountId)));
        });
    }

    public List<PaymentRecord> getPayments(String accountId) {
        repository.requireAccount(accountId);
        return repository.withAccount(accountId, a ->
                repository.paymentsOf(accountId).stream().map(this::copyRecord).toList());
    }

    public List<RiskTransitionRecord> getRiskHistory(String accountId) {
        repository.requireAccount(accountId);
        return repository.withAccount(accountId, a ->
                List.copyOf(repository.riskHistoryOf(accountId)));
    }

    // ----------------- 内部：重放 / 派生状态 -----------------

    /**
     * 在账户真实计划与真实流水上原地重放（调用方持有账户锁，故线程安全）。
     *
     * <p>重放会重新计算每笔流水的分摊明细并回写，因此：</p>
     * <ul>
     *   <li>补录/回溯入账后，历史还款分摊按生效日自动重算，结果只取决于流水事实；</li>
     *   <li>各期 paid* 与 outstandingPenalty 被刷新为重放结果；</li>
     *   <li>重放可重复调用且幂等。</li>
     * </ul>
     */
    private ReplayState replay(LoanAccount account, LocalDate asOf) {
        List<Installment> schedule = account.installments;
        List<PaymentRecord> payments = repository.paymentsOf(account.accountId);
        List<BigDecimal> outstandingPenalty = penaltyCalculator.replay(schedule, payments, asOf,
                properties.getAnnualPenaltyRate(), properties.getDayCount(),
                properties.getGraceDays(), allocator);

        BigDecimal remainingScheduled = BigDecimal.ZERO;
        BigDecimal grossPenalty = BigDecimal.ZERO;
        BigDecimal paidPenalty = BigDecimal.ZERO;
        for (int i = 0; i < schedule.size(); i++) {
            Installment inst = schedule.get(i);
            remainingScheduled = remainingScheduled
                    .add(inst.scheduledPrincipal.subtract(inst.paidPrincipal).max(BigDecimal.ZERO))
                    .add(inst.scheduledInterest.subtract(inst.paidInterest).max(BigDecimal.ZERO))
                    .add(inst.scheduledFee.subtract(inst.paidFee).max(BigDecimal.ZERO));
            grossPenalty = grossPenalty.add(outstandingPenalty.get(i).add(inst.paidPenalty));
            paidPenalty = paidPenalty.add(inst.paidPenalty);
        }
        BigDecimal totalPenaltyOutstanding = outstandingPenalty.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ReplayState(schedule, remainingScheduled, totalPenaltyOutstanding,
                grossPenalty, paidPenalty);
    }

    private List<AllocationLine> recordLines(String accountId, long seq) {
        return repository.paymentsOf(accountId).stream()
                .filter(p -> p.sequence == seq)
                .findFirst()
                .map(p -> p.lines.stream()
                        .map(l -> new AllocationLine(l.installmentSeq, l.bucket, l.amount))
                        .toList())
                .orElseGet(List::of);
    }

    private void applyRiskTransition(LoanAccount account, RiskTransitionRecord record) {
        account.riskState = record.to;
        account.ruleVersion = record.ruleVersion;
        account.riskStateDate = record.effectiveDate;
        repository.addRiskTransition(account.accountId, record);
    }

    private LoanAccount snapshotAccount(LoanAccount account, LocalDate asOf) {
        ReplayState state = replay(account, asOf);
        LoanAccount copy = new LoanAccount(account.accountId, account.terms);
        copy.installments.clear();
        state.schedule.forEach(i -> copy.installments.add(i.copy()));
        copy.status = account.status;
        copy.riskState = account.riskState;
        copy.ruleVersion = account.ruleVersion;
        copy.riskStateDate = account.riskStateDate;
        return copy;
    }

    private PaymentRecord copyRecord(PaymentRecord r) {
        return new PaymentRecord(r.idempotencyKey, r.amount, r.effectiveDate, r.submittedAt,
                r.sequence, r.lines.stream()
                        .map(l -> new AllocationLine(l.installmentSeq, l.bucket, l.amount))
                        .toList());
    }

    private LocalDate resolveEffectiveDate(LocalDate explicit, LocalDateTime submittedAt) {
        if (explicit != null) {
            if (explicit.isAfter(submittedAt.toLocalDate())) {
                throw new ServicingException(RejectReason.VALIDATION_ERROR,
                        "生效日期不能晚于提交日期: " + explicit);
            }
            return explicit;
        }
        // 业务日切：提交时刻的小时 ≥ cutoffHour 归属当日，否则归属前一业务日
        return submittedAt.getHour() >= properties.getCutoffHour()
                ? submittedAt.toLocalDate()
                : submittedAt.toLocalDate().minusDays(1);
    }

    private void verifyFullyAllocated(BigDecimal amount, List<AllocationLine> lines) {
        BigDecimal sum = lines.stream().map(l -> l.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(amount) != 0) {
            throw new ServicingException(RejectReason.VALIDATION_ERROR,
                    "还款未被完整分摊: 金额=" + amount + " 分摊合计=" + sum);
        }
    }

    /**
     * 守恒校验：计划总额 + 应计罚息毛额 = 已收还款总额 + 剩余应还（计划未还 + 未付罚息）。
     */
    private void verifyConservation(LoanAccount account, ReplayState state) {
        BigDecimal scheduledTotal = BigDecimal.ZERO;
        for (Installment i : state.schedule) {
            scheduledTotal = scheduledTotal.add(i.scheduledPrincipal)
                    .add(i.scheduledInterest).add(i.scheduledFee);
        }
        BigDecimal paidTotal = repository.paymentsOf(account.accountId).stream()
                .map(p -> p.amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal lhs = scheduledTotal.add(state.grossPenalty);
        BigDecimal rhs = paidTotal.add(state.remainingScheduled).add(state.penaltyOutstanding);
        if (lhs.compareTo(rhs) != 0) {
            throw new IllegalStateException("守恒校验失败: 计划总额+应计罚息=" + lhs
                    + " 已收+剩余=" + rhs);
        }
        for (Installment i : state.schedule) {
            if (i.paidPrincipal.compareTo(i.scheduledPrincipal) > 0
                    || i.paidInterest.compareTo(i.scheduledInterest) > 0
                    || i.paidFee.compareTo(i.scheduledFee) > 0
                    || i.paidPenalty.add(i.outstandingPenalty).signum() < 0) {
                throw new IllegalStateException("守恒校验失败: 期号 " + i.seq + " 出现超额冲抵");
            }
        }
    }

    private void validateCommand(PaymentCommand command, LocalDateTime submittedAt) {
        if (command == null || command.accountId == null || command.accountId.isBlank()) {
            throw new ServicingException(RejectReason.VALIDATION_ERROR, "accountId 不能为空");
        }
        if (command.idempotencyKey == null || command.idempotencyKey.isBlank()) {
            throw new ServicingException(RejectReason.VALIDATION_ERROR, "idempotencyKey 不能为空");
        }
        if (command.amount == null || command.amount.signum() <= 0) {
            throw new ServicingException(RejectReason.PAYMENT_AMOUNT_INVALID,
                    "还款金额必须大于 0: " + command.amount);
        }
        if (submittedAt == null) {
            throw new ServicingException(RejectReason.VALIDATION_ERROR, "提交时间不能为空");
        }
    }

    private void validateTerms(String accountId, LoanTerms terms) {
        if (accountId == null || accountId.isBlank() || terms == null) {
            throw new ServicingException(RejectReason.VALIDATION_ERROR, "accountId/合同要素不能为空");
        }
        if (terms.principal == null || terms.principal.signum() <= 0) {
            throw new ServicingException(RejectReason.VALIDATION_ERROR, "本金必须大于 0");
        }
        if (terms.annualRate == null || terms.annualRate.signum() < 0) {
            throw new ServicingException(RejectReason.VALIDATION_ERROR, "年利率非法");
        }
        if (terms.installmentCount <= 0) {
            throw new ServicingException(RejectReason.VALIDATION_ERROR, "期数必须大于 0");
        }
        if (terms.firstDueDate == null || terms.method == null || terms.feeRate == null) {
            throw new ServicingException(RejectReason.VALIDATION_ERROR, "合同要素不完整");
        }
        if (repository.findAccount(accountId).isPresent()) {
            throw new ServicingException(RejectReason.VALIDATION_ERROR, "账户已存在: " + accountId);
        }
    }

    /**
     * 重放后的派生状态视图（不可变）。
     */
    private static final class ReplayState {
        final List<Installment> schedule;
        private final BigDecimal remainingScheduled;
        private final BigDecimal penaltyOutstanding;
        private final BigDecimal grossPenalty;

        ReplayState(List<Installment> schedule, BigDecimal remainingScheduled,
                    BigDecimal penaltyOutstanding, BigDecimal grossPenalty,
                    BigDecimal paidPenaltyIgnored) {
            this.schedule = schedule;
            this.remainingScheduled = remainingScheduled;
            this.penaltyOutstanding = penaltyOutstanding;
            this.grossPenalty = grossPenalty;
        }

        BigDecimal totalOutstanding() {
            return remainingScheduled.add(penaltyOutstanding);
        }

        BigDecimal penaltyOutstanding() {
            return penaltyOutstanding;
        }
    }

    private record IdempotencyView(IdempotencyRecord record, boolean sameRequest) {
        static IdempotencyView of(IdempotencyRecord record, PaymentCommand command) {
            boolean same = record.accountId.equals(command.accountId)
                    && record.amount.compareTo(command.amount) == 0;
            return new IdempotencyView(record, same);
        }
    }
}
