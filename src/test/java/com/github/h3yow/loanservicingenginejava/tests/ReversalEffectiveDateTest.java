package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.domain.AccountStatus;
import com.github.h3yow.loanservicingenginejava.domain.AccountView;
import com.github.h3yow.loanservicingenginejava.domain.AllocationLine;
import com.github.h3yow.loanservicingenginejava.domain.LoanAccount;
import com.github.h3yow.loanservicingenginejava.domain.LoanTerms;
import com.github.h3yow.loanservicingenginejava.domain.PaymentCommand;
import com.github.h3yow.loanservicingenginejava.domain.PaymentRecord;
import com.github.h3yow.loanservicingenginejava.domain.ReversalCommand;
import com.github.h3yow.loanservicingenginejava.domain.ReversalOutcome;
import com.github.h3yow.loanservicingenginejava.domain.RiskState;
import com.github.h3yow.loanservicingenginejava.domain.RiskTransitionRecord;
import com.github.h3yow.loanservicingenginejava.domain.RepaymentMethod;
import com.github.h3yow.loanservicingenginejava.service.LoanServicingService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冲正生效日口径（时间维度）：
 * <ul>
 *   <li>按早于冲正生效日的日期查询，口径与冲正前完全一致（历史不被改写）；</li>
 *   <li>生效日当天（含）起按剩余有效流水重述：分摊、剩余应还、罚息、风险状态；</li>
 *   <li>多次部分冲正按各自生效日形成阶梯，任一时点只反映已生效的冲正；</li>
 *   <li>结清账户冲正后退回未结清并可继续还款；</li>
 *   <li>同一账户场景用随机 ID 独立建立，保证与执行顺序无关、可换序稳定复现。</li>
 * </ul>
 */
@SpringBootTest
class ReversalEffectiveDateTest {

    private static final Logger log = LoggerFactory.getLogger(ReversalEffectiveDateTest.class);

    @Autowired
    LoanServicingService service;

    private String newId(String tag) {
        return "revdate-" + tag + "-" + UUID.randomUUID();
    }

    private void open(String id, int n, LocalDate firstDue) {
        service.openAccount(id, new LoanTerms(new BigDecimal("12000"), new BigDecimal("0.12"), n,
                firstDue.minusMonths(1), firstDue, RepaymentMethod.EQUAL_INSTALLMENT,
                BigDecimal.ZERO));
    }

    private void pay(String id, String key, String amount, LocalDate date) {
        service.postPayment(new PaymentCommand(id, id + "-" + key, new BigDecimal(amount), date),
                LocalDateTime.of(date, java.time.LocalTime.of(10, 0)));
    }

    private ReversalOutcome reverse(String id, String key, long seq, String amount,
                                    LocalDate date, String reason) {
        return service.postReversal(
                new ReversalCommand(id, id + "-" + key, seq, new BigDecimal(amount), date, reason),
                LocalDateTime.of(date, java.time.LocalTime.of(12, 0)));
    }

    /** 逐期汇总：计划未还总额 + 未付罚息。 */
    private BigDecimal remainingOf(AccountView v) {
        return v.remainingTotal;
    }

    private BigDecimal scheduledPaidOf(AccountView v) {
        return v.installments.stream()
                .map(i -> i.paidPrincipal.add(i.paidInterest).add(i.paidFee))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal penaltyPaidOf(AccountView v) {
        return v.installments.stream()
                .map(i -> i.paidPenalty).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void historyBeforeEffectiveDateIsIdenticalToBeforeReversal() {
        String id = newId("history");
        LocalDate due = LocalDate.of(2025, 2, 1);
        open(id, 12, due);
        pay(id, "p1", "1000", LocalDate.of(2025, 2, 1));
        long seq = service.getPayments(id).get(0).sequence;

        // 冲正前，多个历史日的"存档"口径（当时无任何冲正）
        LocalDate[] probeDates = {
                LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 4),
                LocalDate.of(2025, 3, 15), LocalDate.of(2025, 4, 19)
        };
        List<AccountView> beforeSnapshots = new ArrayList<>();
        for (LocalDate d : probeDates) {
            beforeSnapshots.add(service.getAccountView(id, d));
        }

        // 4/20 起整笔冲正生效
        LocalDate eff = LocalDate.of(2025, 4, 20);
        ReversalOutcome r = reverse(id, "r1", seq, "1000", eff, "银行退回");
        log.info("[输入] 2/1还款1000 seq={} 4/20整笔冲正; [最终] 生效日起剩余={} 状态={}/{}",
                seq, r.remainingTotal, r.accountStatus, r.riskState);

        // 早于生效日的每个历史日，必须与冲正前完全一致
        for (int i = 0; i < probeDates.length; i++) {
            LocalDate d = probeDates[i];
            AccountView pre = beforeSnapshots.get(i);
            AccountView post = service.getAccountView(id, d);
            assertEquals(remainingOf(pre), remainingOf(post),
                    "历史日 " + d + " 剩余应还必须与冲正前一致");
            assertEquals(scheduledPaidOf(pre), scheduledPaidOf(post),
                    "历史日 " + d + " 计划已收必须与冲正前一致");
            assertEquals(penaltyPaidOf(pre), penaltyPaidOf(post),
                    "历史日 " + d + " 已付罚息必须与冲正前一致");
            assertEquals(pre.outstandingPenalty, post.outstandingPenalty,
                    "历史日 " + d + " 未付罚息必须与冲正前一致");
            assertEquals(pre.riskState, post.riskState,
                    "历史日 " + d + " 风险状态必须与冲正前一致");
            for (int k = 0; k < pre.installments.size(); k++) {
                var a = pre.installments.get(k);
                var b = post.installments.get(k);
                assertEquals(a.paidPrincipal, b.paidPrincipal, "历史日 " + d + " 期" + (k + 1) + "还本");
                assertEquals(a.paidInterest, b.paidInterest, "历史日 " + d + " 期" + (k + 1) + "还息");
                assertEquals(a.paidFee, b.paidFee, "历史日 " + d + " 期" + (k + 1) + "费用");
                assertEquals(a.paidPenalty, b.paidPenalty, "历史日 " + d + " 期" + (k + 1) + "已付罚息");
                assertEquals(a.outstandingPenalty, b.outstandingPenalty,
                        "历史日 " + d + " 期" + (k + 1) + "未付罚息");
            }
            // 历史查询看到的流水分摊仍是冲正前的原始分摊（净额1000全部入账）
            PaymentRecord paymentAtDate = post.payments.stream()
                    .filter(p -> p.sequence == seq).findFirst().orElseThrow();
            BigDecimal allocated = paymentAtDate.lines.stream()
                    .map(l -> l.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(0, new BigDecimal("1000").compareTo(allocated),
                    "历史日 " + d + " 该笔还款仍应表现为已全额分摊");
        }
        log.info("[判定] 冲正生效日 {} 之前的所有历史口径与冲正前逐期一致（含流水分摊）", eff);
    }

    @Test
    void effectiveDateBoundaryDayAppliesReversalFromThatDayInclusive() {
        String id = newId("boundary");
        LocalDate due = LocalDate.of(2025, 2, 1);
        open(id, 12, due);
        pay(id, "p1", "1000", due);
        long seq = service.getPayments(id).get(0).sequence;

        AccountView dayBefore = service.getAccountView(id, LocalDate.of(2025, 2, 4));
        reverse(id, "r1", seq, "1000", LocalDate.of(2025, 2, 5), "银行退回");

        AccountView onEffective = service.getAccountView(id, LocalDate.of(2025, 2, 5));
        AccountView dayAfter = service.getAccountView(id, LocalDate.of(2025, 2, 6));

        log.info("[输入] 冲正生效日=2025-02-05; [判定] 前一日剩余={} 生效日剩余={} 次日剩余={}",
                remainingOf(dayBefore), remainingOf(onEffective), remainingOf(dayAfter));
        // 前一天：还款仍然有效
        assertEquals(0, new BigDecimal("1000").compareTo(scheduledPaidOf(dayBefore).add(penaltyPaidOf(dayBefore))),
                "生效日前一天有效已收仍为1000");
        // 生效日当天（含）：冲正已生效，还款净额为0
        assertEquals(0, BigDecimal.ZERO.compareTo(scheduledPaidOf(onEffective).add(penaltyPaidOf(onEffective))),
                "生效日当天有效已收必须为0");
        assertEquals(0, BigDecimal.ZERO.compareTo(scheduledPaidOf(dayAfter).add(penaltyPaidOf(dayAfter))),
                "生效日次日有效已收必须为0");
        // 生效日（含）起冲正已生效：2/5 与 2/6 的计划未还部分相同，
        // 差异只可能来自 2/6 当天新增的一日应计罚息（本金已恢复逾期）
        BigDecimal principalUnchanged = onEffective.remainingPrincipal.add(onEffective.remainingInterest)
                .add(onEffective.remainingFee);
        BigDecimal principalUnchangedNext = dayAfter.remainingPrincipal.add(dayAfter.remainingInterest)
                .add(dayAfter.remainingFee);
        assertEquals(0, principalUnchanged.compareTo(principalUnchangedNext),
                "生效日与次日计划未还必须一致；仅罚息可按日自然增长");
        assertTrue(dayAfter.outstandingPenalty.compareTo(onEffective.outstandingPenalty) >= 0,
                "冲正恢复逾期后，次日未付罚息不得少于生效日当天");
        // 生效日当天原流水分摊已清空
        assertTrue(onEffective.payments.get(0).lines.isEmpty(),
                "生效日当天整笔冲净的流水分摊为空");
    }

    @Test
    void multiplePartialReversalsFormEffectiveDateSteps() {
        String id = newId("steps");
        LocalDate due = LocalDate.of(2025, 2, 1);
        open(id, 12, due);
        pay(id, "p1", "1000", due);
        long seq = service.getPayments(id).get(0).sequence;

        LocalDate d1 = LocalDate.of(2025, 3, 1);
        LocalDate d2 = LocalDate.of(2025, 4, 1);
        reverse(id, "r1", seq, "400", d1, "部分退回400");
        reverse(id, "r2", seq, "300", d2, "再退回300");

        AccountView before = service.getAccountView(id, LocalDate.of(2025, 2, 28));
        AccountView step1 = service.getAccountView(id, d1);
        AccountView between = service.getAccountView(id, LocalDate.of(2025, 3, 15));
        AccountView step2 = service.getAccountView(id, d2);
        AccountView after = service.getAccountView(id, LocalDate.of(2025, 5, 1));

        BigDecimal receivedBefore = scheduledPaidOf(before).add(penaltyPaidOf(before));
        BigDecimal receivedStep1 = scheduledPaidOf(step1).add(penaltyPaidOf(step1));
        BigDecimal receivedBetween = scheduledPaidOf(between).add(penaltyPaidOf(between));
        BigDecimal receivedStep2 = scheduledPaidOf(step2).add(penaltyPaidOf(step2));
        BigDecimal receivedAfter = scheduledPaidOf(after).add(penaltyPaidOf(after));
        log.info("[输入] 3/1冲400、4/1再冲300（原1000）; [判定] 有效已收阶梯: 2/28={} 3/1={} 3/15={} 4/1={} 5/1={}",
                receivedBefore, receivedStep1, receivedBetween, receivedStep2, receivedAfter);

        assertEquals(0, new BigDecimal("1000").compareTo(receivedBefore), "第一笔冲正前有效已收1000");
        assertEquals(0, new BigDecimal("600").compareTo(receivedStep1), "3/1起有效已收600");
        assertEquals(0, new BigDecimal("600").compareTo(receivedBetween), "两次冲正之间维持600");
        assertEquals(0, new BigDecimal("300").compareTo(receivedStep2), "4/1起有效已收300");
        assertEquals(0, new BigDecimal("300").compareTo(receivedAfter), "之后维持300");

        // 冲正事实都在账本可查，但历史视图的余额只反映当时已生效的冲正
        assertEquals(2, service.getReversals(id).size());
    }

    @Test
    void reversalExposingOverdueReclassifiesRiskOnlyFromEffectiveDate() {
        String id = newId("risk");
        LocalDate due = LocalDate.of(2025, 2, 1);
        open(id, 12, due);
        BigDecimal firstDueAmount = service.getAccountView(id, due).installments.get(0).scheduledPrincipal
                .add(service.getAccountView(id, due).installments.get(0).scheduledInterest);
        pay(id, "p1", firstDueAmount.toPlainString(), due);
        long seq = service.getPayments(id).get(0).sequence;

        LocalDate eff = LocalDate.of(2025, 4, 20);
        reverse(id, "r1", seq, firstDueAmount.toPlainString(), eff, "退回");

        AccountView beforeEff = service.getAccountView(id, LocalDate.of(2025, 4, 19));
        AccountView onEff = service.getAccountView(id, eff);
        List<RiskTransitionRecord> history = service.getRiskHistory(id);
        RiskTransitionRecord auto = history.get(history.size() - 1);
        log.info("[输入] 首期足额还款后4/20整笔冲正; [判定] 冲正前风险={} 生效日风险={} 自动迁移={}->{} 依据={}",
                beforeEff.riskState, onEff.riskState, auto.from, auto.to, auto.reason);

        assertEquals(RiskState.NORMAL, beforeEff.riskState, "生效日前风险历史不变（仍NORMAL）");
        assertEquals(RiskState.OVERDUE, onEff.riskState, "生效日当天起暴露逾期，归OVERDUE");
        assertEquals(RiskState.OVERDUE, auto.to);
        assertTrue(auto.reason.contains("AUTO_AFTER_REVERSAL"));
        // 逾期天数 > 30（V1 下 OVERDUE）
        assertTrue(onEff.installments.get(0).scheduledPrincipal.subtract(
                onEff.installments.get(0).paidPrincipal).signum() > 0,
                "生效日起首期本金恢复为未还");
    }

    @Test
    void settledAccountReopensAtEffectiveDateAndAcceptsNewPayments() {
        String id = newId("reopen");
        LocalDate due = LocalDate.of(2025, 2, 1);
        open(id, 1, due);
        BigDecimal total = scheduledTotal(service.getAccount(id));
        pay(id, "settle", total.toPlainString(), due);
        long seq = service.getPayments(id).get(0).sequence;

        LocalDate eff = LocalDate.of(2025, 2, 10);
        ReversalOutcome r = reverse(id, "r1", seq, "100", eff, "退回100");
        assertEquals(AccountStatus.ACTIVE, r.accountStatus);

        // 生效日前视图：账户历史上仍是结清
        AccountView preEffView = service.getAccountView(id, LocalDate.of(2025, 2, 9));
        assertEquals(AccountStatus.SETTLED, preEffView.status,
                "生效日前历史状态仍为SETTLED");
        // 生效日起退回 ACTIVE
        assertEquals(AccountStatus.ACTIVE, service.getAccountView(id, eff).status);
        assertEquals(AccountStatus.ACTIVE, service.getAccount(id).status);

        // 可继续还款：按冲正后实际剩余应还（本金100 + 暴露逾期后产生的少量罚息）足额补回，
        // 账户重新结清
        BigDecimal outstandingAtReopen = service.getAccountView(id, eff).remainingTotal;
        LocalDate repayDate = LocalDate.of(2025, 2, 11);
        BigDecimal repayAmount = service.getAccountView(id, repayDate).remainingTotal;
        var repay = service.postPayment(
                new PaymentCommand(id, id + "-repay", repayAmount, repayDate),
                LocalDateTime.of(repayDate, java.time.LocalTime.of(10, 0)));
        log.info("[最终] 冲正暴露剩余={}(含罚息)，补回{} => status={} 剩余={}",
                outstandingAtReopen, repayAmount, repay.accountStatus, repay.remainingTotal);
        assertEquals(AccountStatus.SETTLED, repay.accountStatus);
        assertEquals(0, repay.remainingTotal.signum());
    }

    /**
     * 顺序稳定性：把"建立事实-冲正-跨生效日查询"的步骤随机打乱重复执行多轮，
     * 每轮使用独立随机账户。结论只取决于事实与 asOf，与执行顺序无关。
     */
    @Test
    void resultsAreStableAcrossRandomizedExecution() {
        List<Integer> order = new ArrayList<>();
        for (int round = 0; round < 8; round++) {
            order.add(round);
        }
        Collections.shuffle(order);
        LocalDate eff = LocalDate.of(2025, 3, 1);
        BigDecimal[] preRemaining = new BigDecimal[8];
        BigDecimal[] postRemaining = new BigDecimal[8];
        for (int round : order) {
            String id = newId("shuffle" + round);
            open(id, 12, LocalDate.of(2025, 2, 1));
            pay(id, "p", "1000", LocalDate.of(2025, 2, 1));
            long seq = service.getPayments(id).get(0).sequence;
            preRemaining[round] = service.getAccountView(id, LocalDate.of(2025, 2, 28)).remainingTotal;
            reverse(id, "r", seq, "400", eff, "退回");
            postRemaining[round] = service.getAccountView(id, eff).remainingTotal;
        }
        for (int round = 0; round < 8; round++) {
            assertEquals(0, preRemaining[0].compareTo(preRemaining[round]),
                    "随机顺序第" + round + "轮：冲正前口径必须一致");
            assertEquals(0, postRemaining[0].compareTo(postRemaining[round]),
                    "随机顺序第" + round + "轮：生效日口径必须一致");
        }
        log.info("[判定] 8轮随机顺序: 冲正前剩余={} 生效日剩余={} 各轮完全一致",
                preRemaining[0], postRemaining[0]);
    }

    /**
     * 冲正之后再补录一笔更早的还款（提交更晚、生效日更早）：
     * 冲正门控仍以"冲正生效日"为界——补录的早期还款在冲正生效日已存在，
     * 故生效日起该笔正常参与推演；冲正生效日之前的历史不受冲正影响但包含补录还款
     * （补录按既有回溯口径重算历史分摊）。
     */
    @Test
    void earlierBackdatedPaymentPostedAfterReversalIsReplayedConsistently() {
        String id = newId("backdated");
        LocalDate due = LocalDate.of(2025, 2, 1);
        open(id, 12, due);
        pay(id, "late", "300", LocalDate.of(2025, 4, 20));
        long lateSeq = service.getPayments(id).get(0).sequence;

        // 4/25 整笔冲正 4/20 那笔
        reverse(id, "r1", lateSeq, "300", LocalDate.of(2025, 4, 25), "退回");
        assertEquals(0, service.getAccountView(id, LocalDate.of(2025, 4, 24))
                        .payments.get(0).lines.stream().map(l -> l.amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(new BigDecimal("300")),
                "4/24 视图中 4/20 还款仍全额有效");

        // 补录一笔 2/10 的早期还款（提交于冲正之后）
        pay(id, "early", "500", LocalDate.of(2025, 2, 10));

        AccountView beforeEff = service.getAccountView(id, LocalDate.of(2025, 4, 24));
        AccountView afterEff = service.getAccountView(id, LocalDate.of(2025, 4, 25));
        BigDecimal receivedBefore = scheduledPaidOf(beforeEff).add(penaltyPaidOf(beforeEff));
        BigDecimal receivedAfter = scheduledPaidOf(afterEff).add(penaltyPaidOf(afterEff));
        log.info("[输入] 4/20还款300、4/25冲正、再补录2/10还款500; [判定] 4/24有效已收={} 4/25有效已收={}",
                receivedBefore, receivedAfter);
        // 冲正生效日之前：4/20 的 300 仍有效，加上补录的 2/10 的 500
        assertEquals(0, new BigDecimal("800").compareTo(receivedBefore),
                "冲正生效日前：补录还款有效、被冲还款也仍有效，合计800");
        // 冲正生效日起：4/20 的 300 被冲掉，只剩补录的 500
        assertEquals(0, new BigDecimal("500").compareTo(receivedAfter),
                "冲正生效日起：被冲300消失，仅补录500有效");
    }

    /**
     * 并发混合操作后的时间门控：16 个线程并发还款与冲正（不同键），
     * 结束后任一历史日视图的守恒成立，且生效日前/后口径只受当时已生效冲正影响。
     */
    @Test
    void concurrentMixedOperationsProduceConsistentTemporalViews() throws Exception {
        String id = newId("concurrent");
        open(id, 12, LocalDate.of(2025, 2, 1));
        LocalDate payDate = LocalDate.of(2025, 2, 1);
        pay(id, "base", "2000", payDate);
        long seq = service.getPayments(id).get(0).sequence;

        int threads = 16;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ConcurrentLinkedQueue<Throwable> errors =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    if (idx % 2 == 0) {
                        // 冲正 100，生效日 2/10
                        reverse(id, "rv" + idx, seq, "100",
                                LocalDate.of(2025, 2, 10), "并发退回");
                    } else {
                        // 新还款 100，生效日 2/5
                        pay(id, "pay" + idx, "100", LocalDate.of(2025, 2, 5));
                    }
                } catch (com.github.h3yow.loanservicingenginejava.service.ServicingException e) {
                    if (e.getReason() != com.github.h3yow.loanservicingenginejava.domain.RejectReason.REVERSAL_EXCEEDS_PAYMENT
                            && e.getReason() != com.github.h3yow.loanservicingenginejava.domain.RejectReason.OVERPAYMENT
                            && e.getReason() != com.github.h3yow.loanservicingenginejava.domain.RejectReason.ACCOUNT_NOT_POSTABLE) {
                        errors.add(e);
                    }
                } catch (Exception e) {
                    errors.add(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), "并发操作不应出现预期外异常: " + errors);

        BigDecimal totalReversed = service.getReversals(id).stream()
                .map(r -> r.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertTrue(totalReversed.compareTo(new BigDecimal("2000")) <= 0,
                "同一笔钱不会被冲两次");

        // 2/9：冲正（2/10生效）尚未发生，只有原2000 + 并发新还款（2/5生效）
        AccountView before = service.getAccountView(id, LocalDate.of(2025, 2, 9));
        // 2/10 起：冲正生效
        AccountView after = service.getAccountView(id, LocalDate.of(2025, 2, 10));
        long newPayments = service.getPayments(id).size() - 1L;
        BigDecimal expectedBefore = new BigDecimal("2000").add(new BigDecimal(newPayments * 100L));
        BigDecimal receivedBefore = scheduledPaidOf(before).add(penaltyPaidOf(before));
        BigDecimal receivedAfter = scheduledPaidOf(after).add(penaltyPaidOf(after));
        log.info("[输入] 16线程并发（8冲正@2/10、8还款@2/5）; [判定] 实际冲正总额={} 新还款笔数={} 2/9有效已收={} 2/10有效已收={}",
                totalReversed, newPayments, receivedBefore, receivedAfter);
        assertEquals(0, expectedBefore.compareTo(receivedBefore),
                "冲正生效日前：冲正均未生效，有效已收=原2000+新还款");
        assertEquals(0, expectedBefore.subtract(totalReversed).compareTo(receivedAfter),
                "冲正生效日起：有效已收=生效日前口径-累计冲正");
        // 逐期非负、不超额
        after.installments.forEach(i -> {
            assertTrue(i.paidPrincipal.compareTo(i.scheduledPrincipal) <= 0);
            assertTrue(i.paidInterest.compareTo(i.scheduledInterest) <= 0);
            assertTrue(i.outstandingPenalty.signum() >= 0);
        });
    }

    private BigDecimal scheduledTotal(LoanAccount a) {        return a.installments.stream()
                .map(i -> i.scheduledPrincipal.add(i.scheduledInterest).add(i.scheduledFee))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
