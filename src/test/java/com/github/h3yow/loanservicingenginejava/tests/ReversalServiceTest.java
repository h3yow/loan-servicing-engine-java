package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.domain.AccountStatus;
import com.github.h3yow.loanservicingenginejava.domain.AccountView;
import com.github.h3yow.loanservicingenginejava.domain.LoanAccount;
import com.github.h3yow.loanservicingenginejava.domain.LoanTerms;
import com.github.h3yow.loanservicingenginejava.domain.PaymentCommand;
import com.github.h3yow.loanservicingenginejava.domain.PaymentOutcome;
import com.github.h3yow.loanservicingenginejava.domain.PaymentRecord;
import com.github.h3yow.loanservicingenginejava.domain.RejectReason;
import com.github.h3yow.loanservicingenginejava.domain.RepaymentMethod;
import com.github.h3yow.loanservicingenginejava.domain.ReversalCommand;
import com.github.h3yow.loanservicingenginejava.domain.ReversalOutcome;
import com.github.h3yow.loanservicingenginejava.domain.ReversalRecord;
import com.github.h3yow.loanservicingenginejava.domain.RiskState;
import com.github.h3yow.loanservicingenginejava.domain.RiskTransitionRecord;
import com.github.h3yow.loanservicingenginejava.service.LoanServicingService;
import com.github.h3yow.loanservicingenginejava.service.ServicingException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 还款冲正：整笔/部分冲正、幂等与标识冲突、重复/超额冲正拒绝、
 * 冲正后按生效日回溯重算（分摊/罚息/余额）、结清退回与继续还款、
 * 逾期与风险状态随冲正更新、终态账户资金操作拒绝、冲正可追溯。
 */
@SpringBootTest
class ReversalServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ReversalServiceTest.class);

    @Autowired
    LoanServicingService service;

    private void open(String id, int n, LocalDate firstDue) {
        service.openAccount(id, new LoanTerms(new BigDecimal("12000"), new BigDecimal("0.12"), n,
                firstDue.minusMonths(1), firstDue, RepaymentMethod.EQUAL_INSTALLMENT,
                BigDecimal.ZERO));
    }

    private PaymentOutcome pay(String id, String key, String amount, LocalDate date) {
        return service.postPayment(new PaymentCommand(id, id + "-" + key, new BigDecimal(amount), date),
                LocalDateTime.of(date, LocalDateTime.of(2000, 1, 1, 10, 0).toLocalTime()));
    }

    private ReversalOutcome reverse(String id, String key, long seq, String amount,
                                    LocalDate date, String reason) {
        return service.postReversal(
                new ReversalCommand(id, id + "-" + key, seq, new BigDecimal(amount), date, reason),
                LocalDateTime.of(date, LocalDateTime.of(2000, 1, 1, 12, 0).toLocalTime()));
    }

    private BigDecimal scheduledTotal(LoanAccount a) {
        return a.installments.stream()
                .map(i -> i.scheduledPrincipal.add(i.scheduledInterest).add(i.scheduledFee))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal outstandingPenalty(LoanAccount a) {
        return a.installments.stream()
                .map(i -> i.outstandingPenalty).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void fullReversalRestoresAccountAsIfPaymentNeverPosted() {
        String id = "rev-full";
        open(id, 12, LocalDate.of(2025, 2, 1));
        LoanAccount before = service.getAccount(id);
        BigDecimal total = scheduledTotal(before);

        PaymentOutcome p = pay(id, "p1", "500", LocalDate.of(2025, 2, 1));
        long seq = service.getPayments(id).get(0).sequence;
        log.info("[输入] 入账500 seq={} 冲正前剩余={}", seq, p.remainingTotal);

        ReversalOutcome r = reverse(id, "r1", seq, "500", LocalDate.of(2025, 2, 2), "银行退回");
        LoanAccount after = service.getAccount(id);
        log.info("[判定] 整笔冲正: duplicate={} 剩余可冲={} 冲正后剩余={} 计划总额={}",
                r.duplicate, r.remainingReversible, r.remainingTotal, total);
        assertFalse(r.duplicate);
        assertEquals(0, r.remainingReversible.signum(), "整笔冲正后无可冲余额");
        assertEquals(0, r.remainingTotal.compareTo(total), "整笔冲正后剩余应还恢复为计划总额");
        assertEquals(AccountStatus.ACTIVE, r.accountStatus, "未结清应保持 ACTIVE");
        // 各期 paid* 全部归零，如同从未入账
        after.installments.forEach(i -> {
            assertEquals(0, i.paidPrincipal.signum());
            assertEquals(0, i.paidInterest.signum());
            assertEquals(0, i.paidFee.signum());
            assertEquals(0, i.paidPenalty.signum());
        });
        // 原流水仍在账本（事实不删），但分摊明细已清空、净额为 0
        PaymentRecord record = service.getPayments(id).get(0);
        assertTrue(record.lines.isEmpty(), "被整笔冲正的流水不再产生分摊");
    }

    @Test
    void partialReversalReducesEffectiveAmountAndCanContinuePaying() {
        String id = "rev-partial";
        open(id, 12, LocalDate.of(2025, 2, 1));
        BigDecimal total = scheduledTotal(service.getAccount(id));

        pay(id, "p1", "500", LocalDate.of(2025, 2, 1));
        long seq = service.getPayments(id).get(0).sequence;
        ReversalOutcome r1 = reverse(id, "r1", seq, "200", LocalDate.of(2025, 2, 2), "金额多记");
        log.info("[判定] 部分冲正200: 剩余可冲={} 剩余应还={} (计划总额-300={})",
                r1.remainingReversible, r1.remainingTotal, total.subtract(new BigDecimal("300")));
        assertEquals(0, new BigDecimal("300").compareTo(r1.remainingReversible));
        assertEquals(0, total.subtract(new BigDecimal("300")).compareTo(r1.remainingTotal),
                "冲正后有效已收=300");

        // 冲正后账户处于未结清，可继续正常还款
        PaymentOutcome p2 = pay(id, "p2", "300", LocalDate.of(2025, 2, 3));
        log.info("[判定] 冲正后继续还款300: 剩余应还={} (计划总额-600={})",
                p2.remainingTotal, total.subtract(new BigDecimal("600")));
        assertEquals(0, total.subtract(new BigDecimal("600")).compareTo(p2.remainingTotal),
                "有效已收=300(原净额)+300(新还款)=600");

        // 对同一笔还款再部分冲正 300，累计 500 恰好等于原额
        ReversalOutcome r2 = reverse(id, "r2", seq, "300", LocalDate.of(2025, 2, 4), "剩余退回");
        assertEquals(0, r2.remainingReversible.signum());
        assertEquals(0, total.subtract(new BigDecimal("300")).compareTo(r2.remainingTotal),
                "二次冲正后有效已收只剩新还款300");
        log.info("[最终] 累计冲正=原额500, 剩余应还={} 状态={}", r2.remainingTotal, r2.accountStatus);
    }

    @Test
    void reversalIdempotencyReturnsFirstResultAndDoesNotDoubleReverse() {
        String id = "rev-idem";
        open(id, 12, LocalDate.of(2025, 2, 1));
        pay(id, "p1", "500", LocalDate.of(2025, 2, 1));
        long seq = service.getPayments(id).get(0).sequence;

        ReversalOutcome first = reverse(id, "rk", seq, "200", LocalDate.of(2025, 2, 2), "原因A");
        ReversalOutcome second = reverse(id, "rk", seq, "200", LocalDate.of(2025, 2, 3), "原因A");
        log.info("[输入] 同键同内容提交两次; [判定] first.duplicate={} second.duplicate={} 冲正流水笔数={}",
                first.duplicate, second.duplicate, service.getReversals(id).size());
        assertFalse(first.duplicate);
        assertTrue(second.duplicate);
        assertEquals(1, service.getReversals(id).size(), "重复提交不得产生第二条冲正流水");
        assertEquals(0, first.remainingTotal.compareTo(second.remainingTotal));
    }

    @Test
    void sameKeyDifferentContentRejectedAsConflict() {
        String id = "rev-conflict";
        open(id, 12, LocalDate.of(2025, 2, 1));
        pay(id, "p1", "500", LocalDate.of(2025, 2, 1));
        pay(id, "p2", "300", LocalDate.of(2025, 2, 1));
        long seq1 = service.getPayments(id).get(0).sequence;
        long seq2 = service.getPayments(id).get(1).sequence;

        reverse(id, "ck", seq1, "100", LocalDate.of(2025, 2, 2), "原因A");

        // 同键不同金额
        ServicingException diffAmount = assertThrows(ServicingException.class, () ->
                reverse(id, "ck", seq1, "200", LocalDate.of(2025, 2, 2), "原因A"));
        // 同键串到别的还款
        ServicingException diffTarget = assertThrows(ServicingException.class, () ->
                reverse(id, "ck", seq2, "100", LocalDate.of(2025, 2, 2), "原因A"));
        // 同键串到还款操作（跨操作类型）
        ServicingException diffKind = assertThrows(ServicingException.class, () ->
                pay(id, "ck", "100", LocalDate.of(2025, 2, 2)));
        log.info("[判定] 同键不同金额={} 串还款={} 串操作类型={}",
                diffAmount.getReason(), diffTarget.getReason(), diffKind.getReason());
        assertEquals(RejectReason.IDEMPOTENCY_KEY_CONFLICT, diffAmount.getReason());
        assertEquals(RejectReason.IDEMPOTENCY_KEY_CONFLICT, diffTarget.getReason());
        assertEquals(RejectReason.IDEMPOTENCY_KEY_CONFLICT, diffKind.getReason());
        assertEquals(1, service.getReversals(id).size());
    }

    @Test
    void sameKeyFromAnotherAccountRejectedAsConflict() {
        String id1 = "rev-cross-1";
        String id2 = "rev-cross-2";
        open(id1, 12, LocalDate.of(2025, 2, 1));
        open(id2, 12, LocalDate.of(2025, 2, 1));
        pay(id1, "p1", "500", LocalDate.of(2025, 2, 1));
        pay(id2, "p1", "500", LocalDate.of(2025, 2, 1));
        long seq1 = service.getPayments(id1).get(0).sequence;
        long seq2 = service.getPayments(id2).get(0).sequence;
        // 使用未经账户前缀的全局键，模拟调用方把键串到另一个账户
        service.postReversal(new ReversalCommand(id1, "global-cross-key", seq1,
                        new BigDecimal("100"), LocalDate.of(2025, 2, 2), "原因A"),
                LocalDateTime.of(2025, 2, 2, 12, 0));
        ServicingException ex = assertThrows(ServicingException.class, () ->
                service.postReversal(new ReversalCommand(id2, "global-cross-key", seq2,
                                new BigDecimal("100"), LocalDate.of(2025, 2, 2), "原因A"),
                        LocalDateTime.of(2025, 2, 2, 12, 0)));
        log.info("[判定] 同键串户 => {}", ex.getReason());
        assertEquals(RejectReason.IDEMPOTENCY_KEY_CONFLICT, ex.getReason());
        assertEquals(0, service.getReversals(id2).size());
    }

    @Test
    void cumulativeReversalExceedingPaymentRejected() {
        String id = "rev-exceed";
        open(id, 12, LocalDate.of(2025, 2, 1));
        pay(id, "p1", "500", LocalDate.of(2025, 2, 1));
        long seq = service.getPayments(id).get(0).sequence;
        reverse(id, "r1", seq, "300", LocalDate.of(2025, 2, 2), "部分退回");

        ServicingException ex = assertThrows(ServicingException.class, () ->
                reverse(id, "r2", seq, "201", LocalDate.of(2025, 2, 3), "超额退回"));
        log.info("[输入] 已冲300再冲201(原额500); [最终] 拒绝原因={} 消息={}",
                ex.getReason(), ex.getMessage());
        assertEquals(RejectReason.REVERSAL_EXCEEDS_PAYMENT, ex.getReason());
        // 恰好等于剩余可冲 200 应成功（边界）
        ReversalOutcome boundary = reverse(id, "r3", seq, "200", LocalDate.of(2025, 2, 3), "边界");
        assertEquals(0, boundary.remainingReversible.signum());
        // 整笔冲净后再冲任何金额都拒绝
        ServicingException again = assertThrows(ServicingException.class, () ->
                reverse(id, "r4", seq, "0.01", LocalDate.of(2025, 2, 4), "重复冲"));
        assertEquals(RejectReason.REVERSAL_EXCEEDS_PAYMENT, again.getReason());
        log.info("[最终] 边界冲正成功，整笔冲净后再冲 => {}", again.getReason());
    }

    @Test
    void reversalOfUnknownOrForeignPaymentRejected() {
        String id1 = "rev-target-1";
        String id2 = "rev-target-2";
        open(id1, 12, LocalDate.of(2025, 2, 1));
        open(id2, 12, LocalDate.of(2025, 2, 1));
        pay(id1, "p1", "500", LocalDate.of(2025, 2, 1));
        long seq1 = service.getPayments(id1).get(0).sequence;

        // 根本不存在的还款序号
        ServicingException missing = assertThrows(ServicingException.class, () ->
                reverse(id1, "r-miss", seq1 + 999, "10", LocalDate.of(2025, 2, 2), "x"));
        // 标识串到别的账户：用 id2 去冲 id1 的还款
        ServicingException foreign = assertThrows(ServicingException.class, () ->
                reverse(id2, "r-foreign", seq1, "10", LocalDate.of(2025, 2, 2), "x"));
        log.info("[判定] 目标不存在={} 串户冲正={}", missing.getReason(), foreign.getReason());
        assertEquals(RejectReason.REVERSAL_TARGET_NOT_FOUND, missing.getReason());
        assertEquals(RejectReason.REVERSAL_TARGET_NOT_FOUND, foreign.getReason());
    }

    @Test
    void invalidReversalAmountRejected() {
        String id = "rev-amount";
        open(id, 12, LocalDate.of(2025, 2, 1));
        pay(id, "p1", "500", LocalDate.of(2025, 2, 1));
        long seq = service.getPayments(id).get(0).sequence;
        ServicingException ex = assertThrows(ServicingException.class, () ->
                service.postReversal(new ReversalCommand(id, id + "-r0", seq, BigDecimal.ZERO,
                                LocalDate.of(2025, 2, 2), "x"),
                        LocalDateTime.of(2025, 2, 2, 12, 0)));
        log.info("[最终] 零金额冲正 => {}", ex.getReason());
        assertEquals(RejectReason.REVERSAL_AMOUNT_INVALID, ex.getReason());
    }

    @Test
    void reversalReplaysBackdatedAllocationAndPenaltyByEffectiveDate() {
        String id = "rev-replay";
        open(id, 12, LocalDate.of(2025, 2, 1));
        // 2/10 早期还本一笔（压低后续罚息基数）
        pay(id, "early", "1000", LocalDate.of(2025, 2, 10));
        long seqEarly = service.getPayments(id).get(0).sequence;
        // 4/20 逾期后再还一笔小额
        PaymentOutcome late = pay(id, "late", "300", LocalDate.of(2025, 4, 20));
        log.info("[输入] 2/10还本1000后，4/20还款300");

        // 冲正 2/10 那笔（登记日 4/25）：自原生效日 2/10 起不再算作已收，整体回溯重放
        ReversalOutcome r = reverse(id, "r-replay", seqEarly, "1000",
                LocalDate.of(2025, 4, 25), "记错账户退回");

        // 重放等价性：冲正后的净现金流序列（只剩 4/20 的 300）必须与一个
        // "从未有过 2/10 那笔、只在 4/20 还 300" 的对照账户得到完全一致的派生口径
        String idCmp = "rev-replay-cmp";
        open(idCmp, 12, LocalDate.of(2025, 2, 1));
        pay(idCmp, "late", "300", LocalDate.of(2025, 4, 20));

        LoanAccount cmp = service.getAccount(idCmp);
        LoanAccount rev = service.getAccount(id);
        for (int k = 0; k < rev.installments.size(); k++) {
            var x = rev.installments.get(k);
            var y = cmp.installments.get(k);
            assertEquals(0, x.paidPrincipal.compareTo(y.paidPrincipal),
                    "期" + (k + 1) + "还本：冲正回溯口径应等于该笔从未存在");
            assertEquals(0, x.paidInterest.compareTo(y.paidInterest), "期" + (k + 1) + "还息");
            assertEquals(0, x.paidFee.compareTo(y.paidFee), "期" + (k + 1) + "费用");
            assertEquals(0, x.paidPenalty.compareTo(y.paidPenalty), "期" + (k + 1) + "已付罚息");
            assertEquals(0, x.outstandingPenalty.compareTo(y.outstandingPenalty),
                    "期" + (k + 1) + "未付罚息");
        }
        log.info("[判定] 冲正回溯重算与对照账户逐期口径一致（含罚息基数重算）: 未付罚息={}",
                outstandingPenalty(rev));

        // 剩余 = 计划未还 + 应计未付罚息（口径取自同一 asOf 的视图本身）；守恒仍成立
        AccountView view = service.getAccountView(id, LocalDate.of(2025, 4, 25));
        BigDecimal scheduledUnpaid = view.installments.stream()
                .map(i -> i.scheduledPrincipal.subtract(i.paidPrincipal).max(BigDecimal.ZERO)
                        .add(i.scheduledInterest.subtract(i.paidInterest).max(BigDecimal.ZERO))
                        .add(i.scheduledFee.subtract(i.paidFee).max(BigDecimal.ZERO)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, scheduledUnpaid.add(view.outstandingPenalty)
                .compareTo(view.remainingTotal), "剩余=计划未还+未付罚息");
        // 有效现金流 300：计划已收 + 已付罚息 = 300（300 中一部分冲抵了逾期罚息）
        BigDecimal total = view.installments.stream()
                .map(i -> i.scheduledPrincipal.add(i.scheduledInterest).add(i.scheduledFee))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paidPenaltyTotal = view.installments.stream()
                .map(i -> i.paidPenalty).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal scheduledPaid = total.subtract(scheduledUnpaid);
        log.info("[判定] 计划已收={} 已付罚息={} 合计={}", scheduledPaid, paidPenaltyTotal,
                scheduledPaid.add(paidPenaltyTotal));
        assertEquals(0, new BigDecimal("300").compareTo(scheduledPaid.add(paidPenaltyTotal)),
                "有效现金 300 = 计划已收 + 已付罚息");
        // 被冲正流水的原分摊被清空，且 4/20 那笔的分摊已按冲正后的事实重算
        PaymentRecord reversedRecord = service.getPayments(id).stream()
                .filter(p -> p.sequence == seqEarly).findFirst().orElseThrow();
        assertTrue(reversedRecord.lines.isEmpty());
    }

    @Test
    void reversalOfSettlingPaymentReopensAccountAndAllowsRepayment() {
        String id = "rev-reopen";
        open(id, 1, LocalDate.of(2025, 2, 1)); // 单期，便于一次结清
        BigDecimal total = scheduledTotal(service.getAccount(id));
        PaymentOutcome settled = pay(id, "settle", total.toPlainString(),
                LocalDate.of(2025, 2, 1));
        long seq = service.getPayments(id).get(0).sequence;
        assertEquals(AccountStatus.SETTLED, settled.accountStatus);
        log.info("[输入] 一次结清 status=SETTLED total={}", total);

        // 已结清账户上再还款被拒绝（既有语义）
        ServicingException payRejected = assertThrows(ServicingException.class, () ->
                pay(id, "extra", "1", LocalDate.of(2025, 2, 2)));
        assertEquals(RejectReason.ACCOUNT_NOT_POSTABLE, payRejected.getReason());

        // 对结清那笔做部分冲正：账户应退回 ACTIVE 并可继续还款
        ReversalOutcome r = reverse(id, "r-reopen", seq, "100",
                LocalDate.of(2025, 2, 3), "退回");
        log.info("[判定] 冲正结清还款100 => status={} 剩余={}", r.accountStatus, r.remainingTotal);
        assertEquals(AccountStatus.ACTIVE, r.accountStatus);
        assertEquals(0, new BigDecimal("100").compareTo(r.remainingTotal));

        PaymentOutcome repay = pay(id, "repay", "100", LocalDate.of(2025, 2, 4));
        assertEquals(AccountStatus.SETTLED, repay.accountStatus);
        assertEquals(0, repay.remainingTotal.signum());
        log.info("[最终] 补回100后重新结清 status={}", repay.accountStatus);
    }

    @Test
    void reversalExposingOverdueDebtReclassifiesRisk() {
        String id = "rev-risk";
        open(id, 12, LocalDate.of(2025, 2, 1));
        // 首期当天恰好还清第一期（账户仍 ACTIVE，第一期无逾期）
        BigDecimal firstDue = service.getAccount(id).installments.get(0).scheduledPrincipal
                .add(service.getAccount(id).installments.get(0).scheduledInterest);
        pay(id, "p1", firstDue.toPlainString(), LocalDate.of(2025, 2, 1));
        long seq = service.getPayments(id).get(0).sequence;
        assertEquals(RiskState.NORMAL, service.getAccount(id).riskState);

        // 4/20 整笔冲正首期还款：第一期自 2 月起的逾期被暴露（逾期约 75 天）
        ReversalOutcome r = reverse(id, "r-risk", seq, firstDue.toPlainString(),
                LocalDate.of(2025, 4, 20), "退回");
        RiskTransitionRecord last = service.getRiskHistory(id).get(service.getRiskHistory(id).size() - 1);
        log.info("[判定] 冲正暴露逾期: 剩余={} 风险={} 末次迁移={}->{} 原因={} 版本={}",
                r.remainingTotal, r.riskState, last.from, last.to, last.reason, last.ruleVersion);
        assertEquals(RiskState.OVERDUE, r.riskState, "V1下逾期远超30天应自动归为 OVERDUE");
        assertEquals(RiskState.OVERDUE, last.to);
        assertTrue(last.reason.contains("AUTO_AFTER_REVERSAL"));

        // 此时再对该账户做显式风险重估结论一致（不抖动）
        RiskTransitionRecord again = service.reevaluateRisk(id, LocalDate.of(2025, 4, 20));
        assertEquals(RiskState.OVERDUE, again.to);
    }

    @Test
    void fundOperationsRejectedOnClosedAndWrittenOffAccounts() {
        // CLOSED：关账后还款与冲正都拒绝
        String id = "rev-closed";
        open(id, 12, LocalDate.of(2025, 2, 1));
        pay(id, "p1", "500", LocalDate.of(2025, 2, 1));
        long seq = service.getPayments(id).get(0).sequence;
        service.closeAccount(id, "年终关账");

        ServicingException payClosed = assertThrows(ServicingException.class, () ->
                pay(id, "p2", "10", LocalDate.of(2025, 2, 2)));
        ServicingException revClosed = assertThrows(ServicingException.class, () ->
                reverse(id, "rc", seq, "10", LocalDate.of(2025, 2, 2), "x"));
        log.info("[判定] CLOSED: 还款=>{} 冲正=>{}", payClosed.getReason(), revClosed.getReason());
        assertEquals(RejectReason.ACCOUNT_NOT_POSTABLE, payClosed.getReason());
        assertEquals(RejectReason.ACCOUNT_NOT_POSTABLE, revClosed.getReason());

        // WRITTEN_OFF：核销终态同样拒绝一切资金类操作
        String id2 = "rev-wo";
        open(id2, 12, LocalDate.of(2025, 2, 1));
        pay(id2, "p1", "500", LocalDate.of(2025, 2, 1));
        long seq2 = service.getPayments(id2).get(0).sequence;
        service.transitionRisk(id2, RiskState.WRITTEN_OFF, LocalDate.of(2025, 7, 1), "核销");
        ServicingException payWo = assertThrows(ServicingException.class, () ->
                pay(id2, "p2", "10", LocalDate.of(2025, 7, 2)));
        ServicingException revWo = assertThrows(ServicingException.class, () ->
                reverse(id2, "rw", seq2, "10", LocalDate.of(2025, 7, 2), "x"));
        log.info("[判定] WRITTEN_OFF: 还款=>{} 冲正=>{}", payWo.getReason(), revWo.getReason());
        assertEquals(RejectReason.ACCOUNT_NOT_POSTABLE, payWo.getReason());
        assertEquals(RejectReason.ACCOUNT_NOT_POSTABLE, revWo.getReason());
    }

    @Test
    void reversalIsTraceableToTargetAmountReasonAndEffectiveDate() {
        String id = "rev-trace";
        open(id, 12, LocalDate.of(2025, 2, 1));
        pay(id, "p1", "500", LocalDate.of(2025, 2, 1));
        long seq = service.getPayments(id).get(0).sequence;
        reverse(id, "r-trace", seq, "500", LocalDate.of(2025, 2, 8), "银行退汇 BANK-RET-001");

        AccountView view = service.getAccountView(id, LocalDate.of(2025, 2, 9));
        assertEquals(1, view.reversals.size());
        ReversalRecord r = view.reversals.get(0);
        log.info("[最终] 冲正追溯: key={} 被冲还款seq={} 金额={} 生效日={} 登记日={} 原因={}",
                r.idempotencyKey, r.paymentSequence, r.amount, r.effectiveDate,
                r.submittedAt, r.reason);
        assertEquals(seq, r.paymentSequence);
        assertEquals(0, new BigDecimal("500").compareTo(r.amount));
        assertEquals(LocalDate.of(2025, 2, 8), r.effectiveDate);
        assertEquals("银行退汇 BANK-RET-001", r.reason);
        // 被冲还款事实仍可查（原金额不变），其净额由冲正流水推导
        PaymentRecord origin = view.payments.stream()
                .filter(p -> p.sequence == seq).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("500").compareTo(origin.amount));
        assertTrue(origin.lines.isEmpty(), "整笔冲正后原流水分摊为空");
    }
}
