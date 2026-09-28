package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.domain.AccountView;
import com.github.h3yow.loanservicingenginejava.domain.LoanAccount;
import com.github.h3yow.loanservicingenginejava.domain.LoanTerms;
import com.github.h3yow.loanservicingenginejava.domain.PaymentCommand;
import com.github.h3yow.loanservicingenginejava.domain.RepaymentMethod;
import com.github.h3yow.loanservicingenginejava.domain.RejectReason;
import com.github.h3yow.loanservicingenginejava.domain.ReversalCommand;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新时间口径（生效日前不变、生效日起重述）下的并发一致性：
 * 同一账户上并发的还款、冲正与风险状态变更不得造成余额/分摊/罚息/状态对不上；
 * 同一笔钱不会被冲两次；守恒恒等式持续成立。
 *
 * <p>账户/键均带唯一前缀，可与任意其它用例、任意执行顺序混跑。</p>
 */
@SpringBootTest
class ReversalTemporalConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ReversalTemporalConcurrencyTest.class);

    @Autowired
    LoanServicingService service;

    private void open(String id, int n, String principal) {
        service.openAccount(id, new LoanTerms(new BigDecimal(principal), new BigDecimal("0.12"), n,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 1),
                RepaymentMethod.EQUAL_INSTALLMENT, BigDecimal.ZERO));
    }

    private LocalDateTime at(LocalDate d, int minute) {
        return LocalDateTime.of(d.getYear(), d.getMonth(), d.getDayOfMonth(), 10, minute);
    }

    /** 各期非负、无超额冲抵的轻量守恒检查。 */
    private void assertNoOverAllocation(AccountView v) {
        v.installments.forEach(i -> {
            assertTrue(i.paidPrincipal.compareTo(i.scheduledPrincipal) <= 0, "期" + i.seq + "还本超额");
            assertTrue(i.paidInterest.compareTo(i.scheduledInterest) <= 0, "期" + i.seq + "还息超额");
            assertTrue(i.paidFee.compareTo(i.scheduledFee) <= 0, "期" + i.seq + "费用超额");
            assertTrue(i.outstandingPenalty.signum() >= 0, "期" + i.seq + "罚息为负");
        });
    }

    @Test
    void concurrentPaymentsReversalsAndRiskTransitionsStayConsistent() throws Exception {
        String id = "rct-mixed-" + System.nanoTime();
        open(id, 12, "120000");
        LocalDate d = LocalDate.of(2025, 2, 1);
        // 先入账两笔各 500，作为冲正目标
        service.postPayment(new PaymentCommand(id, id + "-base1", new BigDecimal("500"), d), at(d, 0));
        service.postPayment(new PaymentCommand(id, id + "-base2", new BigDecimal("500"), d), at(d, 1));
        long seq1 = service.getPayments(id).get(0).sequence;
        long seq2 = service.getPayments(id).get(1).sequence;
        // 先推到中间态 WATCH：并发风险线程在 NORMAL/WATCH/OVERDUE 间做相邻合法迁移
        service.transitionRisk(id, RiskState.WATCH, d, "预置中间态");

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger payOk = new AtomicInteger();
        AtomicInteger revOk = new AtomicInteger();
        AtomicInteger riskOk = new AtomicInteger();
        AtomicInteger rejectedExpected = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    switch (idx % 3) {
                        case 0 -> {
                            // 还款 100（可能因恰好结清而 OVERPAYMENT，属预期业务拒绝）
                            service.postPayment(new PaymentCommand(id, id + "-pay-" + idx,
                                    new BigDecimal("100"), d), at(d, 30 + idx));
                            payOk.incrementAndGet();
                        }
                        case 1 -> {
                            // 对两笔基础还款交替冲正，每笔最多冲到原额，超出为预期拒绝
                            long target = (idx % 6 == 1) ? seq1 : seq2;
                            service.postReversal(new ReversalCommand(id, id + "-rev-" + idx, target,
                                    new BigDecimal("100"), d, "混合并发冲正"), at(d, 30 + idx));
                            revOk.incrementAndGet();
                        }
                        default -> {
                            // 风险迁移：在 NORMAL/WATCH/OVERDUE 间做合法相邻迁移；
                            // 并发竞争同一起态时落败方为 ILLEGAL_RISK_TRANSITION（预期拒绝）。
                            // 先把账户推到中间态 WATCH，保证并发迁移有机会真实落账。
                            RiskState target = riskTarget(idx);
                            service.transitionRisk(id, target, d, "并发风险迁移");
                            riskOk.incrementAndGet();
                        }
                    }
                } catch (ServicingException e) {
                    if (e.getReason() == RejectReason.REVERSAL_EXCEEDS_PAYMENT
                            || e.getReason() == RejectReason.OVERPAYMENT
                            || e.getReason() == RejectReason.ILLEGAL_RISK_TRANSITION
                            || e.getReason() == RejectReason.ACCOUNT_NOT_POSTABLE) {
                        rejectedExpected.incrementAndGet();
                    } else {
                        errors.add(e);
                    }
                } catch (Exception e) {
                    errors.add(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // 最终账本一致性检查
        LoanAccount account = service.getAccount(id);
        BigDecimal gross = service.getPayments(id).stream()
                .map(p -> p.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reversed = service.getReversals(id).stream()
                .map(r -> r.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netReceived = gross.subtract(reversed);
        AccountView view = service.getAccountView(id, LocalDate.of(2025, 2, 1));
        BigDecimal allocated = view.installments.stream()
                .map(i -> i.paidPrincipal.add(i.paidInterest).add(i.paidFee).add(i.paidPenalty))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("[输入] 24线程(还款/冲正/风险)并发; [判定] 还款成功={} 冲正成功={} 风险成功={} 预期拒绝={} 意外异常={}",
                payOk.get(), revOk.get(), riskOk.get(), rejectedExpected.get(), errors.size());
        log.info("[最终] 还款总额={} 冲正总额={} 净入账={} 各期已收={} 风险={}",
                gross, reversed, netReceived, allocated, account.riskState);

        assertTrue(errors.isEmpty(), "不得出现超额/非法迁移以外的异常: " + errors);
        // 守恒：当日无应计罚息，净入账必须等于各期已收
        assertEquals(0, netReceived.compareTo(allocated),
                "并发下净入账必须等于账本各期已收（分摊/余额对得上）");
        assertNoOverAllocation(view);
        // 同一笔钱不会被冲两次：每笔基础还款累计冲正不超过原额
        BigDecimal rev1 = service.getReversals(id).stream()
                .filter(r -> r.paymentSequence == seq1).map(r -> r.amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rev2 = service.getReversals(id).stream()
                .filter(r -> r.paymentSequence == seq2).map(r -> r.amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertTrue(rev1.compareTo(new BigDecimal("500")) <= 0, "第一笔不会被冲超过一次总额");
        assertTrue(rev2.compareTo(new BigDecimal("500")) <= 0, "第二笔不会被冲超过一次总额");
        // 风险状态必须落在合法枚举且与风险历史末态一致
        RiskState historyRisk = service.getRiskHistory(id).isEmpty()
                ? RiskState.NORMAL
                : service.getRiskHistory(id).get(service.getRiskHistory(id).size() - 1).to;
        assertEquals(historyRisk, account.riskState, "当前风险状态必须与风险历史末态一致");

        // 并发产生的事实在多个 asOf 上都满足时间窗守恒：
        // 时间窗净已收 + 剩余应还 = 计划总额 + 应计罚息毛额
        BigDecimal scheduledTotal = view.installments.stream()
                .map(i -> i.scheduledPrincipal.add(i.scheduledInterest).add(i.scheduledFee))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        for (LocalDate asOf : new LocalDate[]{d, d.plusDays(1), d.plusDays(30), d.plusDays(120)}) {
            AccountView v = service.getAccountView(id, asOf);
            BigDecimal windowNet = v.payments.stream().map(p -> p.amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .subtract(v.reversals.stream().map(r -> r.amount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
            BigDecimal grossPenalty = v.installments.stream()
                    .map(i -> i.outstandingPenalty.add(i.paidPenalty))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal lhs = scheduledTotal.add(grossPenalty);
            BigDecimal rhs = windowNet.add(v.remainingTotal);
            assertEquals(0, lhs.compareTo(rhs),
                    "asOf=" + asOf + " 时间窗守恒: 计划+罚息毛额=" + lhs + " 净已收+剩余=" + rhs);
            assertNoOverAllocation(v);
        }
        log.info("[最终] 多时点(0/1/30/120天)时间窗守恒均成立");
    }

    private RiskState riskTarget(int idx) {
        // 起点 WATCH：只发相邻合法迁移（去 WATCH），并发竞争同一起态落败为预期拒绝
        return (idx % 2 == 0) ? RiskState.NORMAL : RiskState.OVERDUE;
    }
}
