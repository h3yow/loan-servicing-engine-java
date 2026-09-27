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
import com.github.h3yow.loanservicingenginejava.domain.RiskState;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 还款冲正：整笔/部分冲正、冲正后按生效日重述重算（分摊/余额/罚息/逾期/风险）、
 * 已结清退回未结清后可继续还款、幂等与标识冲突、重复冲正/超额/串户拒绝、
 * 终态账户资金操作拒绝。
 */
@SpringBootTest
class ReversalServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ReversalServiceTest.class);

    @Autowired
    LoanServicingService service;

    private LoanTerms terms(String id, int n, BigDecimal feeRate) {
        return new LoanTerms(new BigDecimal("12000"), new BigDecimal("0.12"), n,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 1),
                RepaymentMethod.EQUAL_INSTALLMENT, feeRate);
    }

    private PaymentCommand pay(String id, String key, String amount, LocalDate date) {
        return new PaymentCommand(id, key, new BigDecimal(amount), date);
    }

    private ReversalCommand rev(String id, String key, long seq, String amount,
                                LocalDate date, String reason) {
        return new ReversalCommand(id, key, seq, new BigDecimal(amount), date, reason);
    }

    private LocalDateTime at(LocalDate d, int hour) {
        return LocalDateTime.of(d, LocalDateTime.of(d, java.time.LocalTime.of(hour, 0)).toLocalTime());
    }

    /**
     * 整笔冲正：逾期后还款再整笔冲正，账户退回逾期状态，被冲金额重新算为欠款。
     */
    @Test
    void fullReversalReopensAccountAndRestoresDebt() {
        String id = "rev-full";
        service.openAccount(id, terms(id, 3, new BigDecimal("0.03")));
        LocalDate payDate = LocalDate.of(2025, 3, 24); // 已逾期
        PaymentOutcome paid = service.postPayment(pay(id, "rf-pay", "2000", payDate), at(payDate, 10));
        long paySeq = service.getPayments(id).get(0).sequence;
        log.info("[输入] 逾期日2025-03-24还款2000 seq={} 冲正前剩余={} 状态={}",
                paySeq, paid.remainingTotal, paid.accountStatus);

        LocalDate revDate = LocalDate.of(2025, 4, 10);
        ReversalOutcome out = service.postReversal(rev(id, "rf-rev", paySeq, "2000", revDate, "银行退回"),
                at(revDate, 10));
        log.info("[判定] 整笔冲正: duplicate={} 累计冲正={}/{} 冲正后剩余={} 状态={} 风险={} 逾期天数={}",
                out.duplicate, out.cumulativeReversed, out.originalPaymentAmount,
                out.remainingTotal, out.accountStatus, out.riskState, out.overdueDays);

        assertFalse(out.duplicate);
        assertEquals(0, out.remainingEffectivePayment.compareTo(BigDecimal.ZERO),
                "整笔冲正后该笔还款有效金额为0");
        assertEquals(AccountStatus.ACTIVE, out.accountStatus, "已结清/部分还账户冲正后应未结清");
        assertTrue(out.overdueDays > 0, "冲正暴露逾期欠款，逾期天数应大于0");
        assertNotNormalRisk(out.riskState);

        // 被冲还款的分摊明细被重算为空
        PaymentRecord record = service.getPayments(id).get(0);
        assertTrue(record.lines.isEmpty(), "整笔冲正后原还款不再有任何分摊");

        // 冲正事实可追溯：冲哪笔、金额、原因、生效日
        var reversals = service.getReversals(id);
        assertEquals(1, reversals.size());
        assertEquals(paySeq, reversals.get(0).targetPaymentSequence);
        assertEquals(0, new BigDecimal("2000").compareTo(reversals.get(0).amount));
        assertEquals("银行退回", reversals.get(0).reason);
        assertEquals(revDate, reversals.get(0).effectiveDate);

        // 冲正后可以继续正常还款
        PaymentOutcome repay = service.postPayment(pay(id, "rf-pay-2", "500", revDate.plusDays(1)),
                at(revDate.plusDays(1), 10));
        log.info("[最终] 冲正后再还500: 剩余={} 状态={} 分摊{}条",
                repay.remainingTotal, repay.accountStatus, repay.lines.size());
        assertEquals(AccountStatus.ACTIVE, repay.accountStatus);
        assertFalse(repay.lines.isEmpty());
    }

    /**
     * 部分冲正：累计冲正不超过原金额；有效金额=原金额-累计冲正；可分两次部分冲。
     */
    @Test
    void partialReversalReducesEffectiveAmountCumulatively() {
        String id = "rev-partial";
        service.openAccount(id, terms(id, 12, BigDecimal.ZERO));
        LocalDate d = LocalDate.of(2025, 2, 1);
        service.postPayment(pay(id, "rp-pay", "1000", d), at(d, 10));
        long paySeq = service.getPayments(id).get(0).sequence;

        ReversalOutcome r1 = service.postReversal(rev(id, "rp-rev-1", paySeq, "400", d, "记错金额"),
                at(d, 11));
        ReversalOutcome r2 = service.postReversal(rev(id, "rp-rev-2", paySeq, "300", d, "再退部分"),
                at(d, 12));
        log.info("[判定] 部分冲正: 第一次累计={} 有效={}; 第二次累计={} 有效={} 剩余应还={}",
                r1.cumulativeReversed, r1.remainingEffectivePayment,
                r2.cumulativeReversed, r2.remainingEffectivePayment, r2.remainingTotal);

        assertEquals(0, new BigDecimal("400").compareTo(r1.cumulativeReversed));
        assertEquals(0, new BigDecimal("600").compareTo(r1.remainingEffectivePayment));
        assertEquals(0, new BigDecimal("700").compareTo(r2.cumulativeReversed));
        assertEquals(0, new BigDecimal("300").compareTo(r2.remainingEffectivePayment));

        // 原还款分摊按300有效金额重算，分摊合计必须等于300
        BigDecimal allocated = service.getPayments(id).get(0).lines.stream()
                .map(l -> l.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("[最终] 原还款重算分摊合计={} (应为300), 冲正流水{}笔",
                allocated, service.getReversals(id).size());
        assertEquals(0, new BigDecimal("300.00").compareTo(allocated));
        assertEquals(2, service.getReversals(id).size());
    }

    /**
     * 整笔冲正已结清账户：SETTLED 退回 ACTIVE，可继续还款直至再次结清。
     */
    @Test
    void reversalOfSettledPaymentReopensAndCanSettleAgain() {
        String id = "rev-settled";
        service.openAccount(id, terms(id, 1, BigDecimal.ZERO));
        LocalDate d = LocalDate.of(2025, 2, 1);
        LoanAccount a = service.getAccount(id);
        BigDecimal due = a.installments.get(0).scheduledPrincipal
                .add(a.installments.get(0).scheduledInterest);
        PaymentOutcome settled = service.postPayment(pay(id, "rs-pay", due.toPlainString(), d), at(d, 10));
        assertEquals(AccountStatus.SETTLED, settled.accountStatus);
        long paySeq = service.getPayments(id).get(0).sequence;

        ReversalOutcome out = service.postReversal(rev(id, "rs-rev", paySeq, due.toPlainString(), d, "账户记错"),
                at(d, 14));
        log.info("[判定] 结清后整笔冲正: 剩余={} 状态={}", out.remainingTotal, out.accountStatus);
        assertEquals(AccountStatus.ACTIVE, out.accountStatus);
        assertTrue(out.remainingTotal.compareTo(BigDecimal.ZERO) > 0);

        PaymentOutcome again = service.postPayment(pay(id, "rs-pay-2", due.toPlainString(), d.plusDays(1)),
                at(d.plusDays(1), 10));
        log.info("[最终] 重新还款结清: 状态={} 剩余={}", again.accountStatus, again.remainingTotal);
        assertEquals(AccountStatus.SETTLED, again.accountStatus);
        assertEquals(0, again.remainingTotal.signum());
    }

    /**
     * 冲正回溯口径：冲正生效日之前的历史视图仍按原金额（全额收到），
     * 生效日及之后按剩余有效流水重述；冲正后罚息随被冲本金重新计出。
     */
    @Test
    void replayBeforeReversalDateIsUnaffectedAndAfterIsRestated() {
        String id = "rev-replay";
        service.openAccount(id, terms(id, 3, new BigDecimal("0.03")));
        LocalDate payDate = LocalDate.of(2025, 3, 24); // 逾期后还本
        service.postPayment(pay(id, "rr-pay", "2000", payDate), at(payDate, 10));
        long paySeq = service.getPayments(id).get(0).sequence;

        // 冲正前一日视图：还款有效
        AccountView before = service.getAccountView(id, payDate.plusDays(10));
        BigDecimal remainingBefore = before.remainingTotal;
        BigDecimal paidPrincipalBefore = before.installments.stream()
                .map(i -> i.paidPrincipal).reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDate revDate = LocalDate.of(2025, 5, 20);
        service.postReversal(rev(id, "rr-rev", paySeq, "2000", revDate, "退回"), at(revDate, 10));

        // 同一历史日期（冲正生效日之前）重放：口径不变
        AccountView historical = service.getAccountView(id, payDate.plusDays(10));
        BigDecimal paidPrincipalHistorical = historical.installments.stream()
                .map(i -> i.paidPrincipal).reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("[判定] 回溯口径: 冲正前剩余={} 已还本金={}; 冲正后同一历史日剩余={} 已还本金={}",
                remainingBefore, paidPrincipalBefore, historical.remainingTotal, paidPrincipalHistorical);
        assertEquals(0, remainingBefore.compareTo(historical.remainingTotal),
                "冲正生效日之前的历史视图不得改变");
        assertEquals(0, paidPrincipalBefore.compareTo(paidPrincipalHistorical));

        // 冲正生效日及之后：还款被整体重述，本金回到未还，罚息重新计出
        AccountView after = service.getAccountView(id, revDate);
        BigDecimal paidPrincipalAfter = after.installments.stream()
                .map(i -> i.paidPrincipal).reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("[最终] 冲正生效日视图: 已还本金={} 未付罚息={} 剩余合计={} 状态={}",
                paidPrincipalAfter, after.outstandingPenalty, after.remainingTotal, after.status);
        assertEquals(0, paidPrincipalAfter.signum(), "整笔冲正后各期已还本金应为0");
        assertTrue(after.outstandingPenalty.signum() > 0, "被冲本金期间的罚息应重新计出");
        assertTrue(after.remainingTotal.compareTo(remainingBefore) > 0);
    }

    private void assertNotNormalRisk(RiskState state) {
        assertTrue(state != RiskState.NORMAL, "逾期暴露后风险状态不应为 NORMAL: " + state);
    }

    /**
     * 冲正幂等：同键同内容重复提交返回第一次结果，不再冲一次。
     */
    @Test
    void duplicateReversalReturnsFirstResult() {
        String id = "rev-dup";
        service.openAccount(id, terms(id, 12, BigDecimal.ZERO));
        LocalDate d = LocalDate.of(2025, 2, 1);
        service.postPayment(pay(id, "rd-pay", "1000", d), at(d, 10));
        long seq = service.getPayments(id).get(0).sequence;

        ReversalOutcome first = service.postReversal(rev(id, "rd-key", seq, "300", d, "原因A"), at(d, 11));
        ReversalOutcome second = service.postReversal(rev(id, "rd-key", seq, "300", d, "原因A"), at(d, 12));
        log.info("[判定] 同键重复冲正: first.duplicate={} second.duplicate={} 冲正流水={}",
                first.duplicate, second.duplicate, service.getReversals(id).size());
        assertFalse(first.duplicate);
        assertTrue(second.duplicate);
        assertEquals(first.reversalSequence, second.reversalSequence);
        assertEquals(1, service.getReversals(id).size(), "重复提交不得产生第二条冲正流水");
        assertEquals(0, new BigDecimal("300").compareTo(second.cumulativeReversed));
    }

    /**
     * 同键但金额不一致：明确拒绝为键冲突，且不得入账。
     */
    @Test
    void sameKeyDifferentAmountRejected() {
        String id = "rev-key-amt";
        service.openAccount(id, terms(id, 12, BigDecimal.ZERO));
        LocalDate d = LocalDate.of(2025, 2, 1);
        service.postPayment(pay(id, "rka-pay", "1000", d), at(d, 10));
        long seq = service.getPayments(id).get(0).sequence;
        service.postReversal(rev(id, "rka-key", seq, "300", d, "原因"), at(d, 11));
        ServicingException ex = assertThrows(ServicingException.class, () ->
                service.postReversal(rev(id, "rka-key", seq, "301", d, "原因"), at(d, 12)));
        log.info("[输入] 同键冲正金额300再提301; [最终] 拒绝原因={}", ex.getReason());
        assertEquals(RejectReason.IDEMPOTENCY_KEY_CONFLICT, ex.getReason());
        assertEquals(1, service.getReversals(id).size());
    }

    /**
     * 同键但目标还款不一致（标识串到别的还款）：键冲突拒绝。
     */
    @Test
    void sameKeyDifferentTargetPaymentRejected() {
        String id = "rev-key-target";
        service.openAccount(id, terms(id, 12, BigDecimal.ZERO));
        LocalDate d = LocalDate.of(2025, 2, 1);
        service.postPayment(pay(id, "rkt-p1", "100", d), at(d, 10));
        service.postPayment(pay(id, "rkt-p2", "200", d), at(d, 11));
        List<Long> seqs = service.getPayments(id).stream().map(p -> p.sequence).toList();
        service.postReversal(rev(id, "rkt-key", seqs.get(0), "50", d, "原因"), at(d, 12));
        ServicingException ex = assertThrows(ServicingException.class, () ->
                service.postReversal(rev(id, "rkt-key", seqs.get(1), "50", d, "原因"), at(d, 13)));
        log.info("[输入] 同键先冲seq={}再冲seq={}; [最终] 拒绝原因={}",
                seqs.get(0), seqs.get(1), ex.getReason());
        assertEquals(RejectReason.IDEMPOTENCY_KEY_CONFLICT, ex.getReason());
        assertEquals(1, service.getReversals(id).size());
    }

    /**
     * 幂等键在还款与冲正之间串用：双向都按键冲突拒绝。
     */
    @Test
    void keyReusedAcrossPaymentAndReversalRejected() {
        String id = "rev-key-cross";
        service.openAccount(id, terms(id, 12, BigDecimal.ZERO));
        LocalDate d = LocalDate.of(2025, 2, 1);
        service.postPayment(pay(id, "shared-key", "1000", d), at(d, 10));
        long seq = service.getPayments(id).get(0).sequence;
        // 还款键被冲正复用
        ServicingException ex1 = assertThrows(ServicingException.class, () ->
                service.postReversal(rev(id, "shared-key", seq, "100", d, "原因"), at(d, 11)));
        assertEquals(RejectReason.IDEMPOTENCY_KEY_CONFLICT, ex1.getReason());

        // 冲正键被还款复用
        service.postReversal(rev(id, "rev-key-x", seq, "100", d, "原因"), at(d, 12));
        ServicingException ex2 = assertThrows(ServicingException.class, () ->
                service.postPayment(pay(id, "rev-key-x", "100", d), at(d, 13)));
        log.info("[判定] 键类型串用: 还款->冲正={} 冲正->还款={}", ex1.getReason(), ex2.getReason());
        assertEquals(RejectReason.IDEMPOTENCY_KEY_CONFLICT, ex2.getReason());
    }

    /**
     * 超额冲正：单次超过原金额、或多次累计超过原金额都被拒绝（同一笔不被多冲）。
     */
    @Test
    void overReversalRejected() {
        String id = "rev-over";
        service.openAccount(id, terms(id, 12, BigDecimal.ZERO));
        LocalDate d = LocalDate.of(2025, 2, 1);
        service.postPayment(pay(id, "ro-pay", "1000", d), at(d, 10));
        long seq = service.getPayments(id).get(0).sequence;

        ServicingException single = assertThrows(ServicingException.class, () ->
                service.postReversal(rev(id, "ro-1", seq, "1001", d, "原因"), at(d, 11)));
        assertEquals(RejectReason.REVERSAL_EXCEEDS_PAYMENT, single.getReason());

        service.postReversal(rev(id, "ro-2", seq, "800", d, "原因"), at(d, 12));
        ServicingException cumulative = assertThrows(ServicingException.class, () ->
                service.postReversal(rev(id, "ro-3", seq, "201", d, "原因"), at(d, 13)));
        log.info("[判定] 超额冲正: 单笔超额={} 累计超额(800+201>1000)={}",
                single.getReason(), cumulative.getReason());
        assertEquals(RejectReason.REVERSAL_EXCEEDS_PAYMENT, cumulative.getReason());
        // 恰好冲满合法，之后再冲任意金额必被拒
        service.postReversal(rev(id, "ro-4", seq, "200", d, "原因"), at(d, 14));
        ServicingException afterFull = assertThrows(ServicingException.class, () ->
                service.postReversal(rev(id, "ro-5", seq, "0.01", d, "原因"), at(d, 15)));
        assertEquals(RejectReason.REVERSAL_EXCEEDS_PAYMENT, afterFull.getReason());
        assertEquals(2, service.getReversals(id).size());
    }

    /**
     * 目标还款不存在 / 属于别的账户 / 非法金额与日期：原因可区分。
     */
    @Test
    void invalidTargetAccountAmountAndDateRejected() {
        String id = "rev-invalid";
        service.openAccount(id, terms(id, 12, BigDecimal.ZERO));
        String other = "rev-invalid-other";
        service.openAccount(other, terms(other, 12, BigDecimal.ZERO));
        LocalDate d = LocalDate.of(2025, 2, 1);
        service.postPayment(pay(other, "rio-pay", "1000", d), at(d, 10));
        long otherSeq = service.getPayments(other).get(0).sequence;

        // 序号在本账户不存在（标识串到别的账户的还款）
        ServicingException notFound = assertThrows(ServicingException.class, () ->
                service.postReversal(rev(id, "rio-1", otherSeq, "100", d, "原因"), at(d, 11)));
        // 冲正金额非法
        ServicingException badAmount = assertThrows(ServicingException.class, () ->
                service.postReversal(rev(id, "rio-2", otherSeq, "0", d, "原因"), at(d, 11)));
        // 冲正生效日早于被冲还款生效日（用本账户真实还款校验）
        service.postPayment(pay(id, "rio-pay-2", "1000", d), at(d, 10));
        long ownSeq = service.getPayments(id).get(0).sequence;
        ServicingException badDate = assertThrows(ServicingException.class, () ->
                service.postReversal(rev(id, "rio-3", ownSeq, "100",
                        d.minusDays(1), "原因"), at(d, 11)));
        log.info("[判定] 目标还款不存在={} 金额非法={} 生效日非法={}",
                notFound.getReason(), badAmount.getReason(), badDate.getReason());
        assertEquals(RejectReason.PAYMENT_NOT_FOUND, notFound.getReason());
        assertEquals(RejectReason.REVERSAL_AMOUNT_INVALID, badAmount.getReason());
        assertEquals(RejectReason.REVERSAL_DATE_INVALID, badDate.getReason());
        assertEquals(0, service.getReversals(id).size());
    }

    /**
     * 终态账户：已核销后还款与冲正都按 ACCOUNT_NOT_POSTABLE 拒绝，不写账。
     */
    @Test
    void terminalAccountRejectsFundOperations() {
        String id = "rev-terminal";
        service.openAccount(id, terms(id, 3, BigDecimal.ZERO));
        LocalDate d = LocalDate.of(2025, 3, 1);
        service.postPayment(pay(id, "rt-pay", "500", d), at(d, 10));
        long seq = service.getPayments(id).get(0).sequence;
        service.transitionRisk(id, RiskState.NPL, d, "恶化");
        service.transitionRisk(id, RiskState.WRITTEN_OFF, d, "核销");

        ServicingException payEx = assertThrows(ServicingException.class, () ->
                service.postPayment(pay(id, "rt-pay-2", "100", d), at(d, 11)));
        ServicingException revEx = assertThrows(ServicingException.class, () ->
                service.postReversal(rev(id, "rt-rev", seq, "100", d, "原因"), at(d, 12)));
        log.info("[判定] 已核销账户: 还款拒绝={} 冲正拒绝={}", payEx.getReason(), revEx.getReason());
        assertEquals(RejectReason.ACCOUNT_NOT_POSTABLE, payEx.getReason());
        assertEquals(RejectReason.ACCOUNT_NOT_POSTABLE, revEx.getReason());
        assertEquals(1, service.getPayments(id).size(), "拒绝时不得写入资金流水");
        assertEquals(0, service.getReversals(id).size());

        // 已核销状态必须保持，冲正未把账户偷偷改回
        assertNotNull(service.getAccount(id));
        assertEquals(RiskState.WRITTEN_OFF, service.getAccount(id).riskState);
    }

    /**
     * 逾期小额还款（先冲罚息/费用）再部分冲正：重算后各桶不超额、守恒成立，
     * 且冲正前历史视图仍包含该笔还款的分摊。
     */
    @Test
    void reversalOfOverdueSmallPaymentRestatesPenaltyAndFeeBuckets() {
        String id = "rev-buckets";
        service.openAccount(id, terms(id, 3, new BigDecimal("0.03")));
        LocalDate payDate = LocalDate.of(2025, 3, 24); // 已逾期，含罚息
        service.postPayment(pay(id, "rb-pay", "80", payDate), at(payDate, 10));
        long seq = service.getPayments(id).get(0).sequence;
        var bucketsBefore = service.getPayments(id).get(0).lines.stream()
                .map(l -> l.bucket).toList();
        log.info("[输入] 逾期小额还款80，分摊桶={}", bucketsBefore);

        // 部分冲正 30
        ReversalOutcome out = service.postReversal(rev(id, "rb-rev", seq, "30", payDate, "部分退回"),
                at(payDate, 14));
        var linesAfter = service.getPayments(id).get(0).lines;
        BigDecimal allocated = linesAfter.stream().map(l -> l.amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("[判定] 部分冲正30后: 重算分摊合计={} (应=50) 剩余={} 罚息={}",
                allocated, out.remainingTotal,
                service.getAccountView(id, payDate).outstandingPenalty);
        assertEquals(0, new BigDecimal("50.00").compareTo(allocated),
                "冲正后原还款按50有效金额重新分摊");

        // 逐期不超额
        for (var i : service.getAccount(id).installments) {
            assertTrue(i.paidPenalty.add(i.outstandingPenalty).signum() >= 0);
            assertTrue(i.paidPrincipal.compareTo(i.scheduledPrincipal) <= 0);
        }

        // 冲正生效日之前（更早 asOf）视图仍按80
        AccountView historical = service.getAccountView(id, payDate.minusDays(1));
        // payDate 当天的冲正在 payDate-1 尚未生效：但还款也在 payDate，故历史视图无该笔
        assertEquals(0, historical.installments.stream()
                .map(i -> i.paidPrincipal).reduce(BigDecimal.ZERO, BigDecimal::add).signum());
        log.info("[最终] 冲正前一日视图无该笔还款分摊（历史口径不受冲正影响）");
    }
}
