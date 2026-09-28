package com.github.h3yow.loanservicingenginejava.service;

import com.github.h3yow.loanservicingenginejava.config.ServicingProperties;
import com.github.h3yow.loanservicingenginejava.domain.AccountStatus;
import com.github.h3yow.loanservicingenginejava.domain.AccountStatusEvent;
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
import com.github.h3yow.loanservicingenginejava.domain.ReversalCommand;
import com.github.h3yow.loanservicingenginejava.domain.ReversalOutcome;
import com.github.h3yow.loanservicingenginejava.domain.ReversalRecord;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 贷款服务门面：开户、还款入账、还款冲正、逾期/风险重估、风险迁移与账本查询。
 *
 * <p>账户派生状态（各期已还金额、罚息、余额）永远由"计划 + 还款流水 − 已生效冲正"
 * 按查询时点 asOf 重放得到（时点重放不落盘，落账重放以全部已登记事实的最大生效日为门控日）；
 * 冲正只在其生效日当天（含）起生效，早于生效日的历史查询与冲正前完全一致。
 * 补录/回溯入账天然重新计算且可复现，重放相同 (事实, asOf) 得到相同结果。</p>
 *
 * <p>守恒恒等式（任意时点成立）：
 * 计划总额(本金+利息+费用) + 应计罚息毛额 = 净已收(还款−已生效冲正) + 剩余应还（计划未还 + 未付罚息）。</p>
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
            if (!isPostable(account)) {
                throw new ServicingException(RejectReason.ACCOUNT_NOT_POSTABLE,
                        "账户当前状态不可入账: status=" + account.status
                                + " riskState=" + account.riskState);
            }
            IdempotencyRecord existing = repository.idempotency(command.idempotencyKey).orElse(null);
            if (existing != null) {
                IdempotencyView view = IdempotencyView.of(existing, command);
                if (!view.sameRequest()) {
                    throw new ServicingException(RejectReason.IDEMPOTENCY_KEY_CONFLICT,
                            "幂等键已被其它请求占用: " + command.idempotencyKey
                                    + "（操作类型/账户/金额不一致）");
                }
                LocalDate originalDate = repository.paymentsOf(account.accountId).stream()
                        .filter(p -> p.sequence == existing.paymentSequence)
                        .map(p -> p.effectiveDate)
                        .findFirst().orElse(effectiveDate);
                ReplayState state = materialize(account);
                log.info("[还款重复] key={} 命中既有流水 seq={} 生效日={} 不重复扣款; 剩余应还={} 账户状态={}",
                        command.idempotencyKey, existing.paymentSequence, originalDate,
                        state.totalOutstanding(), account.status);
                return new PaymentOutcome(true, command.idempotencyKey,
                        recordLines(account.accountId, existing.paymentSequence),
                        command.amount, state.totalOutstanding(), account.status);
            }

            // 超额判定按本笔生效日时点口径（生效日更晚的冲正尚未发生，不影响当时欠款）
            ReplayState before = replayView(account, effectiveDate);
            BigDecimal owed = before.totalOutstanding();
            if (command.amount.compareTo(owed) > 0) {
                throw new ServicingException(RejectReason.OVERPAYMENT,
                        "还款金额 " + command.amount + " 超过剩余应还 " + owed);
            }

            long seq = repository.nextSequence(account.accountId);
            // 明细留空：保存后由重放按生效日统一重新分摊（保证补录/回溯可重算）
            PaymentRecord record = new PaymentRecord(command.idempotencyKey, command.amount,
                    effectiveDate, submittedAt.toLocalDate(), seq, new ArrayList<>());
            repository.addPayment(account.accountId, record);
            repository.putIdempotency(command.idempotencyKey, account.accountId,
                    command.amount, seq);

            // 落账重放：按全部已登记事实重新分摊所有流水（含本笔），并把结果回写各流水
            ReplayState after = materialize(account);
            List<AllocationLine> lines = recordLines(account.accountId, seq);
            verifyFullyAllocated(command.amount, lines);
            // 结清事实锚定本笔生效日时点（与历史视图口径一致），再按门控日重建当前状态
            if (replayView(account, effectiveDate).totalOutstanding().signum() == 0) {
                changeFundStatus(account, AccountStatus.SETTLED, effectiveDate,
                        "PAYMENT_SETTLED(seq=" + seq + ")");
            }
            reconcileCurrentFundStatus(account, after.gateDate);
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
     * 对已入账还款发起冲正（整笔或部分）。冲正只在其生效日当天（含）起生效：
     * 早于生效日的查询仍是冲正前口径；生效日及之后，被冲金额不再算作已收，
     * 系统按剩余有效流水重放，重新得出分摊、剩余应还、罚息、逾期与风险状态。
     *
     * <p>重复幂等键返回既有结果（duplicate=true）；同键内容冲突、目标还款不存在、
     * 累计冲正超额、金额非法、账户处于不可入账终态均明确拒绝。</p>
     */
    public ReversalOutcome postReversal(ReversalCommand command, LocalDateTime submittedAt) {
        validateReversalCommand(command, submittedAt);
        LocalDate effectiveDate = resolveEffectiveDate(command.effectiveDate, submittedAt);
        log.info("[冲正请求] accountId={} key={} targetSeq={} amount={} effectiveDate={} reason={} submittedAt={}",
                command.accountId, command.idempotencyKey, command.paymentSequence,
                command.amount, effectiveDate, command.reason, submittedAt);

        return repository.withAccountAndKey(command.accountId, command.idempotencyKey, account -> {
            // 冲正属于资金类操作：已关账/已核销等终态一律拒绝；已结清账户允许冲正（冲后可退回未结清）
            if (account.status == AccountStatus.CLOSED
                    || account.riskState == RiskState.WRITTEN_OFF) {
                throw new ServicingException(RejectReason.ACCOUNT_NOT_POSTABLE,
                        "账户当前状态不可进行资金类操作: status=" + account.status
                                + " riskState=" + account.riskState);
            }
            IdempotencyRecord existing = repository.idempotency(command.idempotencyKey).orElse(null);
            if (existing != null) {
                ReversalIdempotencyView view = ReversalIdempotencyView.of(existing, command);
                if (!view.sameRequest()) {
                    throw new ServicingException(RejectReason.IDEMPOTENCY_KEY_CONFLICT,
                            "冲正幂等键已被其它请求占用: " + command.idempotencyKey
                                    + "（操作类型/账户/目标还款/金额不一致）");
                }
                ReversalRecord first = repository.reversalsOf(account.accountId).stream()
                        .filter(r -> r.sequence == existing.paymentSequence)
                        .findFirst().orElseThrow(() -> new IllegalStateException(
                                "幂等登记存在但冲正流水缺失: " + command.idempotencyKey));
                ReplayState state = materialize(account);
                BigDecimal reversible = reversibleAmount(account, first.paymentSequence);
                log.info("[冲正重复] key={} 命中既有冲正 seq={} 被冲还款seq={} 金额={} 不重复冲账; 剩余应还={} 状态={}/{}",
                        command.idempotencyKey, first.sequence, first.paymentSequence,
                        first.amount, state.totalOutstanding(), account.status, account.riskState);
                return new ReversalOutcome(true, command.idempotencyKey, first.paymentSequence,
                        first.amount, reversible, state.totalOutstanding(),
                        account.status, account.riskState);
            }

            PaymentRecord target = repository.payment(account.accountId, command.paymentSequence)
                    .orElseThrow(() -> new ServicingException(RejectReason.REVERSAL_TARGET_NOT_FOUND,
                            "冲正目标还款不存在: accountId=" + command.accountId
                                    + " paymentSequence=" + command.paymentSequence));
            BigDecimal alreadyReversed = repository.reversalsOf(account.accountId).stream()
                    .filter(r -> r.paymentSequence == target.sequence)
                    .map(r -> r.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal cumulative = alreadyReversed.add(command.amount);
            if (cumulative.compareTo(target.amount) > 0) {
                throw new ServicingException(RejectReason.REVERSAL_EXCEEDS_PAYMENT,
                        "累计冲正金额 " + cumulative + " 超过原还款金额 " + target.amount
                                + "（已冲 " + alreadyReversed + "，本次申请 " + command.amount + "）");
            }
            if (effectiveDate.isBefore(target.effectiveDate)) {
                throw new ServicingException(RejectReason.VALIDATION_ERROR,
                        "冲正生效日 " + effectiveDate + " 不能早于原还款生效日 "
                                + target.effectiveDate);
            }

            long seq = repository.nextSequence(account.accountId);
            ReversalRecord record = new ReversalRecord(command.idempotencyKey, target.sequence,
                    command.amount, effectiveDate, submittedAt.toLocalDate(), seq,
                    command.reason != null ? command.reason : "UNSPECIFIED");
            repository.addReversal(account.accountId, record);
            repository.putReversalIdempotency(command.idempotencyKey, account.accountId,
                    command.amount, seq, target.sequence);

            // 落账重放：全部已登记事实（含本冲正）参与，分摊/罚息/余额全部重算
            ReplayState after = materialize(account);
            // 冲正生效日当天的时点口径：用于状态与风险重估（与"自生效日起重述"一致）
            ReplayState asOfState = replayView(account, effectiveDate);

            // 账户状态：冲正暴露欠款则由已结清退回未结清（ACTIVE），仍无欠款则保持 SETTLED
            if (asOfState.totalOutstanding().signum() == 0) {
                changeFundStatus(account, AccountStatus.SETTLED, effectiveDate,
                        "REVERSAL_STILL_SETTLED(seq=" + seq + ")");
            } else {
                changeFundStatus(account, AccountStatus.ACTIVE, effectiveDate,
                        "REVERSAL_REOPEN(seq=" + seq + ")");
            }
            // 风险状态随冲正暴露的逾期自动重估（白名单外迁移不强行落账，留待人工/显式重估）
            autoReclassifyAfterReversal(account, effectiveDate, asOfState);
            reconcileCurrentFundStatus(account, after.gateDate);
            verifyConservation(account, after);

            BigDecimal reversible = target.amount.subtract(cumulative);
            log.info("[冲正入账] key={} seq={} 被冲还款seq={} 原额={} 本次冲={} 累计冲={} 剩余可冲={} 剩余应还={} 状态={}/{}",
                    command.idempotencyKey, seq, target.sequence, target.amount,
                    command.amount, cumulative, reversible, after.totalOutstanding(),
                    account.status, account.riskState);
            return new ReversalOutcome(false, command.idempotencyKey, target.sequence,
                    command.amount, reversible, after.totalOutstanding(),
                    account.status, account.riskState);
        });
    }

    /**
     * 关账：账户进入终态 CLOSED，此后任何资金类操作（还款/冲正）一律拒绝。
     */
    public void closeAccount(String accountId, String reason) {
        repository.withAccount(accountId, account -> {
            if (account.status == AccountStatus.CLOSED) {
                log.info("[关账] accountId={} 已是 CLOSED，幂等返回", accountId);
                return null;
            }
            account.status = AccountStatus.CLOSED;
            log.info("[关账] accountId={} reason={} 此后资金类操作一律拒绝", accountId, reason);
            return null;
        });
    }

    public List<ReversalRecord> getReversals(String accountId) {
        repository.requireAccount(accountId);
        return repository.withAccount(accountId, a ->
                repository.reversalsOf(accountId).stream().map(ReversalRecord::copy).toList());
    }

    /**
     * 资金类操作是否允许入账：仅 ACTIVE 账户且未核销可还款；
     * SETTLED 账户仅允许冲正（在冲正入口单独判断）。
     */
    private boolean isPostable(LoanAccount account) {
        return account.status == AccountStatus.ACTIVE
                && account.riskState != RiskState.WRITTEN_OFF;
    }

    /**
     * 目标还款剩余可冲金额 = 原额 − 累计已冲。
     */
    private BigDecimal reversibleAmount(LoanAccount account, long paymentSeq) {
        PaymentRecord target = repository.payment(account.accountId, paymentSeq)
                .orElseThrow(() -> new ServicingException(RejectReason.REVERSAL_TARGET_NOT_FOUND,
                        "冲正目标还款不存在: " + paymentSeq));
        BigDecimal already = repository.reversalsOf(account.accountId).stream()
                .filter(r -> r.paymentSequence == paymentSeq)
                .map(r -> r.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return target.amount.subtract(already);
    }

    /**
     * 冲正后按生效日自动重估风险分类：选取当日规则版本推导分类，
     * 仅当状态机允许迁移时落账（例如 NPL 不可自动恢复），并记录风险历史。
     */
    private void autoReclassifyAfterReversal(LoanAccount account, LocalDate asOf, ReplayState state) {
        RiskRuleVersion rule = riskRuleBook.versionAt(asOf).orElse(null);
        if (rule == null) {
            log.info("[冲正风险] accountId={} 生效日 {} 无对应规则版本，跳过自动重估",
                    account.accountId, asOf);
            return;
        }
        int overdueDays = overdueEvaluator.maxOverdueDays(state.schedule, asOf,
                properties.getGraceDays(), properties.isExtendOverdueToBusinessDay());
        RiskState target = riskRuleBook.classify(rule, overdueDays, account);
        // from 取生效日时点的风险状态（晚于 asOf 的迁移当时尚未发生）；无历史则为开户初始 NORMAL
        RiskState from = RiskState.NORMAL;
        for (RiskTransitionRecord h : repository.riskHistoryOf(account.accountId)) {
            if (!h.effectiveDate.isAfter(asOf)) {
                from = h.to;
            }
        }
        if (from == target) {
            log.info("[冲正风险] accountId={} 逾期天数={} 规则版本={} => 分类保持{}",
                    account.accountId, overdueDays, rule.version, target);
            return;
        }
        if (!riskStateMachine.canTransition(from, target)) {
            log.warn("[冲正风险] accountId={} 逾期天数={} 推导 {} -> {} 不在迁移白名单，保留{}待人工处理",
                    account.accountId, overdueDays, from, target, from);
            return;
        }
        RiskTransitionRecord r = new RiskTransitionRecord(asOf, from, target, rule.version,
                "AUTO_AFTER_REVERSAL(" + overdueDays + "d)");
        applyRiskTransition(account, r);
        log.info("[冲正风险] accountId={} 关键依据: 逾期天数={} 宽限期={} 规则版本={} => {} -> {}",
                account.accountId, overdueDays, properties.getGraceDays(), rule.version,
                from, target);
    }


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
            ReplayState state = replayView(account, date);
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
     * 查询账户完整视图：按 asOf 时点重放（生效日晚于 asOf 的冲正不参与），
     * 返回剩余本金/利息/费用/罚息与流水、风险历史。视图中的风险状态、账户状态
     * 同样按 asOf 时点重建：晚于 asOf 的风险迁移与结清事实在该时点尚未发生。
     */
    public com.github.h3yow.loanservicingenginejava.domain.AccountView getAccountView(
            String accountId, LocalDate asOf) {
        repository.requireAccount(accountId);
        LocalDate date = asOf != null ? asOf : LocalDate.now();
        return repository.withAccount(accountId, a -> {
            ReplayState state = replayView(a, date);
            List<Installment> copies = state.schedule.stream().map(Installment::copy).toList();
            BigDecimal rp = BigDecimal.ZERO;
            BigDecimal ri = BigDecimal.ZERO;
            BigDecimal rf = BigDecimal.ZERO;
            for (Installment i : state.schedule) {
                rp = rp.add(i.scheduledPrincipal.subtract(i.paidPrincipal).max(BigDecimal.ZERO));
                ri = ri.add(i.scheduledInterest.subtract(i.paidInterest).max(BigDecimal.ZERO));
                rf = rf.add(i.scheduledFee.subtract(i.paidFee).max(BigDecimal.ZERO));
            }
            // 时点风险状态：取不晚于 asOf 的最后一条风险迁移；此前为开户初始 NORMAL/V 初始版本
            RiskState riskAt = RiskState.NORMAL;
            String ruleAt = a.ruleVersion;
            LocalDate riskDateAt = a.terms.originationDate;
            for (RiskTransitionRecord h : repository.riskHistoryOf(a.accountId)) {
                if (!h.effectiveDate.isAfter(date)) {
                    riskAt = h.to;
                    ruleAt = h.ruleVersion;
                    riskDateAt = h.effectiveDate;
                }
            }
            // 时点账户状态：CLOSED 是无日期的关账终态标记，不参与历史重建；
            // ACTIVE/SETTLED 沿资金状态事件时间线重放到 asOf（晚于 asOf 的事件尚未发生）
            AccountStatus statusAt = AccountStatus.ACTIVE;
            if (a.status != AccountStatus.CLOSED) {
                for (AccountStatusEvent e : repository.statusHistoryOf(a.accountId)) {
                    if (!e.effectiveDate.isAfter(date)) {
                        statusAt = e.to;
                    }
                }
            } else {
                statusAt = AccountStatus.CLOSED;
            }
            return new com.github.h3yow.loanservicingenginejava.domain.AccountView(
                    a.accountId, statusAt, riskAt, ruleAt, riskDateAt, date,
                    copies, rp, ri, rf, state.penaltyOutstanding(), state.totalOutstanding(),
                    temporalPaymentCopies(a.accountId, state),
                    repository.reversalsOf(a.accountId).stream().map(ReversalRecord::copy).toList(),
                    List.copyOf(repository.riskHistoryOf(a.accountId)));
        });
    }

    /**
     * 时点流水副本：金额仍为原始事实金额，但分摊明细取 asOf 时点净额视图的分摊
     * （生效日晚于 asOf 的冲正尚未发生，故历史查询看到的是冲正前的原始分摊）。
     */
    private List<PaymentRecord> temporalPaymentCopies(String accountId, ReplayState state) {
        List<PaymentRecord> copies = new ArrayList<>();
        for (PaymentRecord p : repository.paymentsOf(accountId)) {
            PaymentRecord view = state.views.get(p.sequence);
            List<AllocationLine> lines = view != null
                    ? view.lines.stream()
                            .map(l -> new AllocationLine(l.installmentSeq, l.bucket, l.amount))
                            .toList()
                    : List.of();
            copies.add(new PaymentRecord(p.idempotencyKey, p.amount, p.effectiveDate,
                    p.submittedAt, p.sequence, lines));
        }
        return copies;
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
     * 时点视图重放（不落盘）：复制计划与流水，仅纳入<b>生效日不晚于 asOf</b> 的冲正，
     * 任何已登记但生效日更晚的冲正在该时点都"尚未发生"。
     *
     * <p>时间口径：</p>
     * <ul>
     *   <li>{@code asOf < 冲正生效日}：该冲正完全不参与，视图与冲正前逐分逐厘一致；</li>
     *   <li>{@code asOf >= 冲正生效日}（含当天）：被冲金额不再算作已收，按剩余有效流水推演。</li>
     * </ul>
     *
     * <p>本方法不修改账户真实计划与真实流水，可在锁内/查询路径安全重复调用，
     * 重放相同 (事实, asOf) 必然得到相同结果。</p>
     */
    private ReplayState replayView(LoanAccount account, LocalDate asOf) {
        List<Installment> schedule = account.installments.stream()
                .map(Installment::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return compute(account, schedule, asOf, false);
    }

    /**
     * 落账重放：在账户真实计划上按当前全部已登记事实重放，并把分摊明细回写真实流水。
     * 调用方必须持有账户锁。门控日取全部事实（还款/冲正）的最大生效日，
     * 保证任何已登记事实都已生效；此后任意时点查询只会得到相同或计息更多的口径。
     */
    private ReplayState materialize(LoanAccount account) {
        LocalDate gate = LocalDate.MIN;
        for (PaymentRecord p : repository.paymentsOf(account.accountId)) {
            if (p.effectiveDate.isAfter(gate)) {
                gate = p.effectiveDate;
            }
        }
        for (ReversalRecord r : repository.reversalsOf(account.accountId)) {
            if (r.effectiveDate.isAfter(gate)) {
                gate = r.effectiveDate;
            }
        }
        return compute(account, account.installments, gate, true);
    }

    /**
     * 重放核心。
     *
     * <p>有效还款金额 = 原还款金额 − 该笔在 asOf 时点（含）已生效的累计冲正金额，
     * 生效日仍为原还款生效日：冲正是对入账事实在其生效日之后的更正，而非新增反向资金流水。
     * 还款本身不做时间门控（补录还款按既有回溯口径重算历史分摊）。</p>
     *
     * @param persist true 时把重放产出的分摊回写真实流水（落账路径）；
     *                false 时只写本次构造的视图副本（查询路径）。
     */
    private ReplayState compute(LoanAccount account, List<Installment> schedule,
                                LocalDate asOf, boolean persist) {
        List<PaymentRecord> originals = repository.paymentsOf(account.accountId);

        // 仅累计生效日 <= asOf 的冲正；更晚生效的冲正在该时点尚未发生
        Map<Long, BigDecimal> reversedByPayment = new HashMap<>();
        for (ReversalRecord r : repository.reversalsOf(account.accountId)) {
            if (!r.effectiveDate.isAfter(asOf)) {
                reversedByPayment.merge(r.paymentSequence, r.amount, BigDecimal::add);
            }
        }
        // 构造有效还款视图（净额），整笔冲净的还款保留空分摊、不参与资金冲抵
        Map<Long, PaymentRecord> viewBySeq = new HashMap<>();
        List<PaymentRecord> effective = new ArrayList<>();
        for (PaymentRecord p : originals) {
            BigDecimal reversed = reversedByPayment.getOrDefault(p.sequence, BigDecimal.ZERO);
            BigDecimal net = p.amount.subtract(reversed);
            if (net.signum() < 0) {
                throw new IllegalStateException("冲正金额超过原还款金额（重放期发现）: seq="
                        + p.sequence + " 原额=" + p.amount + " 时点累计冲正=" + reversed);
            }
            if (net.signum() == 0) {
                if (persist) {
                    p.lines = new ArrayList<>();
                }
                continue;
            }
            PaymentRecord view = new PaymentRecord(p.idempotencyKey, net, p.effectiveDate,
                    p.submittedAt, p.sequence, new ArrayList<>());
            viewBySeq.put(p.sequence, view);
            effective.add(view);
        }

        List<BigDecimal> outstandingPenalty = penaltyCalculator.replay(schedule, effective, asOf,
                properties.getAnnualPenaltyRate(), properties.getDayCount(),
                properties.getGraceDays(), allocator);

        // 落账路径：把重放产出的分摊明细回写真实流水（冲正后历史分摊随之重算）
        if (persist) {
            for (PaymentRecord p : originals) {
                PaymentRecord view = viewBySeq.get(p.sequence);
                if (view != null) {
                    p.lines = view.lines.stream()
                            .map(l -> new AllocationLine(l.installmentSeq, l.bucket, l.amount))
                            .toList();
                }
            }
        }

        BigDecimal remainingScheduled = BigDecimal.ZERO;
        BigDecimal grossPenalty = BigDecimal.ZERO;
        for (int i = 0; i < schedule.size(); i++) {
            Installment inst = schedule.get(i);
            remainingScheduled = remainingScheduled
                    .add(inst.scheduledPrincipal.subtract(inst.paidPrincipal).max(BigDecimal.ZERO))
                    .add(inst.scheduledInterest.subtract(inst.paidInterest).max(BigDecimal.ZERO))
                    .add(inst.scheduledFee.subtract(inst.paidFee).max(BigDecimal.ZERO));
            grossPenalty = grossPenalty.add(outstandingPenalty.get(i).add(inst.paidPenalty));
        }
        BigDecimal totalPenaltyOutstanding = outstandingPenalty.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ReplayState(schedule, remainingScheduled, totalPenaltyOutstanding,
                grossPenalty, asOf, viewBySeq);
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

    /**
     * 资金事实驱动的 ACTIVE/SETTLED 状态迁移：仅当状态确实变化时更新当前状态
     * 并按生效日落一条状态事件，供历史时点视图重建。调用方持有账户锁。
     */
    private void changeFundStatus(LoanAccount account, AccountStatus target, LocalDate effectiveDate,
                                  String reason) {
        if (account.status == target) {
            return;
        }
        AccountStatus from = account.status;
        account.status = target;
        repository.addStatusEvent(account.accountId,
                new AccountStatusEvent(effectiveDate, from, target, reason));
        log.info("[账户状态] accountId={} {} -> {} 生效日={} 原因={}",
                account.accountId, from, target, effectiveDate, reason);
    }

    /**
     * 按门控日从状态事件时间线重建账户的当前 ACTIVE/SETTLED（CLOSED 不参与）。
     * 补录/回溯操作可能让新事件的生效日早于既有事件：当前状态必须与
     * "门控日（全部已登记事实最大生效日）时点"一致，而非简单取最后写入的目标状态。
     */
    private void reconcileCurrentFundStatus(LoanAccount account, LocalDate gateDate) {
        if (account.status == AccountStatus.CLOSED) {
            return;
        }
        AccountStatus current = AccountStatus.ACTIVE;
        for (AccountStatusEvent e : repository.statusHistoryOf(account.accountId)) {
            if (!e.effectiveDate.isAfter(gateDate)) {
                current = e.to;
            }
        }
        account.status = current;
    }

    private LoanAccount snapshotAccount(LoanAccount account, LocalDate asOf) {
        ReplayState state = replayView(account, asOf);
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
                .map(p -> p.amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .subtract(repository.reversalsOf(account.accountId).stream()
                        .map(r -> r.amount).reduce(BigDecimal.ZERO, BigDecimal::add));

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

    private void validateReversalCommand(ReversalCommand command, LocalDateTime submittedAt) {
        if (command == null || command.accountId == null || command.accountId.isBlank()) {
            throw new ServicingException(RejectReason.VALIDATION_ERROR, "accountId 不能为空");
        }
        if (command.idempotencyKey == null || command.idempotencyKey.isBlank()) {
            throw new ServicingException(RejectReason.VALIDATION_ERROR, "idempotencyKey 不能为空");
        }
        if (command.paymentSequence <= 0) {
            throw new ServicingException(RejectReason.VALIDATION_ERROR,
                    "被冲还款序号必须大于 0: " + command.paymentSequence);
        }
        if (command.amount == null || command.amount.signum() <= 0) {
            throw new ServicingException(RejectReason.REVERSAL_AMOUNT_INVALID,
                    "冲正金额必须大于 0: " + command.amount);
        }
        if (command.reason == null || command.reason.isBlank()) {
            throw new ServicingException(RejectReason.VALIDATION_ERROR, "冲正原因不能为空");
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
     * 重放后的派生状态视图。
     */
    private static final class ReplayState {
        final List<Installment> schedule;
        private final BigDecimal remainingScheduled;
        private final BigDecimal penaltyOutstanding;
        private final BigDecimal grossPenalty;
        /** 本次重放的门控日（时点视图=asOf；落账=全部事实的最大生效日）。 */
        final LocalDate gateDate;
        /** 各还款序号 -> 本次重放使用的净额视图（含分摊明细）。 */
        final Map<Long, PaymentRecord> views;

        ReplayState(List<Installment> schedule, BigDecimal remainingScheduled,
                    BigDecimal penaltyOutstanding, BigDecimal grossPenalty,
                    LocalDate gateDate, Map<Long, PaymentRecord> views) {
            this.schedule = schedule;
            this.remainingScheduled = remainingScheduled;
            this.penaltyOutstanding = penaltyOutstanding;
            this.grossPenalty = grossPenalty;
            this.gateDate = gateDate;
            this.views = views;
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
            boolean same = "PAYMENT".equals(record.kind)
                    && record.accountId.equals(command.accountId)
                    && record.amount.compareTo(command.amount) == 0;
            return new IdempotencyView(record, same);
        }
    }

    /**
     * 冲正幂等视图：同键必须同操作类型、同账户、同目标还款、同金额，
     * 否则即为标识冲突（键串户/串还款/串金额/串到还款操作）。
     */
    private record ReversalIdempotencyView(IdempotencyRecord record, boolean sameRequest) {
        static ReversalIdempotencyView of(IdempotencyRecord record, ReversalCommand command) {
            boolean same = "REVERSAL".equals(record.kind)
                    && record.accountId.equals(command.accountId)
                    && record.targetSequence == command.paymentSequence
                    && record.amount.compareTo(command.amount) == 0;
            return new ReversalIdempotencyView(record, same);
        }
    }
}
