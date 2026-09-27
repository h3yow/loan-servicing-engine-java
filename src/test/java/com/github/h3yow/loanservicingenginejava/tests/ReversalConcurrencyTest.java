package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.domain.AccountStatus;
import com.github.h3yow.loanservicingenginejava.domain.Installment;
import com.github.h3yow.loanservicingenginejava.domain.LoanTerms;
import com.github.h3yow.loanservicingenginejava.domain.PaymentCommand;
import com.github.h3yow.loanservicingenginejava.domain.PaymentRecord;
import com.github.h3yow.loanservicingenginejava.domain.ReversalCommand;
import com.github.h3yow.loanservicingenginejava.domain.ReversalOutcome;
import com.github.h3yow.loanservicingenginejava.domain.RepaymentMethod;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冲正并发：同一笔还款并发冲正绝不被多冲（受原金额约束）、
 * 冲正与新还款并发后余额/守恒一致、同键并发恰好一笔真实冲正。
 */
@SpringBootTest
class ReversalConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ReversalConcurrencyTest.class);

    @Autowired
    LoanServicingService service;

    private LoanTerms terms(String id, int n) {
        return new LoanTerms(new BigDecimal("120000"), new BigDecimal("0.12"), n,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 1),
                RepaymentMethod.EQUAL_INSTALLMENT, BigDecimal.ZERO);
    }

    private LocalDateTime at(LocalDate d, int minute) {
        return LocalDateTime.of(d, LocalDateTime.of(d, java.time.LocalTime.of(10, 0)).toLocalTime())
                .plusMinutes(minute);
    }

    @Test
    void concurrentReversalsNeverExceedOriginalPayment() throws Exception {
        String id = "rc-over-rev";
        service.openAccount(id, terms(id, 12));
        LocalDate d = LocalDate.of(2025, 2, 1);
        // 两笔还款各1000，分别只冲它们自己的目标
        service.postPayment(new PaymentCommand(id, "rcp1", new BigDecimal("1000"), d), at(d, 0));
        service.postPayment(new PaymentCommand(id, "rcp2", new BigDecimal("1000"), d), at(d, 1));
        List<Long> seqs = service.getPayments(id).stream().map(p -> p.sequence).toList();

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    long target = seqs.get(idx % 2);
                    service.postReversal(new ReversalCommand(id, "rcr-" + idx, target,
                            new BigDecimal("600"), d, "并发冲正"), at(d, 2));
                    accepted.incrementAndGet();
                } catch (ServicingException e) {
                    rejected.incrementAndGet();
                    assertEquals(com.github.h3yow.loanservicingenginejava.domain.RejectReason.REVERSAL_EXCEEDS_PAYMENT,
                            e.getReason());
                } catch (Exception e) {
                    errors.add(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // 每个目标最多容纳 1 笔600（第二笔600将使累计1200>1000）
        long reversedCount = service.getReversals(id).size();
        BigDecimal totalReversed = service.getReversals(id).stream()
                .map(r -> r.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        for (Long seq : seqs) {
            BigDecimal perTarget = service.getReversals(id).stream()
                    .filter(r -> r.targetPaymentSequence == seq)
                    .map(r -> r.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertTrue(perTarget.compareTo(new BigDecimal("1000")) <= 0,
                    "同一笔还款累计冲正不得超过原金额: " + perTarget);
        }
        log.info("[输入] 20线程对两笔1000还款各冲600; [判定] 接受={} 拒绝={} 异常={} 冲正笔数={} 冲正总额={}",
                accepted.get(), rejected.get(), errors.size(), reversedCount, totalReversed);
        errors.forEach(e -> log.error("并发异常", e));
        assertTrue(errors.isEmpty());
        assertEquals(2, reversedCount, "每个目标恰好接受一笔600");
        assertEquals(0, new BigDecimal("1200.00").compareTo(totalReversed));
    }

    @Test
    void concurrentReversalsAndPaymentsLeaveConsistentLedger() throws Exception {
        String id = "rc-mixed";
        service.openAccount(id, terms(id, 12));
        LocalDate d = LocalDate.of(2025, 2, 1);
        // 预先放 8 笔各 500 的还款，作为冲正目标
        for (int i = 0; i < 8; i++) {
            service.postPayment(new PaymentCommand(id, "rcm-pay-" + i, new BigDecimal("500"), d),
                    at(d, i));
        }
        List<Long> seqs = service.getPayments(id).stream().map(p -> p.sequence).toList();

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        AtomicInteger payOk = new AtomicInteger();
        AtomicInteger revOk = new AtomicInteger();
        AtomicInteger revRejected = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    if (idx % 2 == 0) {
                        service.postReversal(new ReversalCommand(id, "rcm-rev-" + idx,
                                seqs.get(idx % seqs.size()), new BigDecimal("500"), d, "退回"), at(d, 10));
                        revOk.incrementAndGet();
                    } else {
                        service.postPayment(new PaymentCommand(id, "rcm-new-" + idx,
                                new BigDecimal("100"), d), at(d, 11));
                        payOk.incrementAndGet();
                    }
                } catch (ServicingException e) {
                    revRejected.incrementAndGet();
                } catch (Exception e) {
                    errors.add(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        errors.forEach(e -> log.error("并发异常", e));
        assertTrue(errors.isEmpty(), "冲正与还款并发不应产生非业务异常");

        var view = service.getAccountView(id, d);
        BigDecimal effectivePaid = service.getPayments(id).stream()
                .map(p -> p.amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .subtract(service.getReversals(id).stream()
                        .map(r -> r.amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal scheduledTotal = view.installments.stream()
                .map(i -> i.scheduledPrincipal.add(i.scheduledInterest).add(i.scheduledFee))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 守恒：计划总额(无新罚息口径场景, 当日还款) = 有效已收 + 剩余应还
        BigDecimal rhs = effectivePaid.add(view.remainingTotal).subtract(view.outstandingPenalty);
        log.info("[判定] 新还款成功={} 冲正成功={} 冲正拒绝={} 计划总额={} 有效已收={} 剩余={} 罚息={}",
                payOk.get(), revOk.get(), revRejected.get(), scheduledTotal,
                effectivePaid, view.remainingTotal, view.outstandingPenalty);
        assertEquals(0, scheduledTotal.compareTo(rhs), "并发后守恒恒等式必须成立");

        // 逐期不得超额冲抵
        for (Installment i : view.installments) {
            assertTrue(i.paidPrincipal.compareTo(i.scheduledPrincipal) <= 0);
            assertTrue(i.paidInterest.compareTo(i.scheduledInterest) <= 0);
        }
        // 账户状态与剩余一致
        assertTrue((view.remainingTotal.signum() == 0)
                == (view.status == AccountStatus.SETTLED));
    }

    @Test
    void concurrentSameKeyReversalExactlyOneApplied() throws Exception {
        String id = "rc-samekey";
        service.openAccount(id, terms(id, 12));
        LocalDate d = LocalDate.of(2025, 2, 1);
        service.postPayment(new PaymentCommand(id, "rcsk-pay", new BigDecimal("1000"), d), at(d, 0));
        long seq = service.getPayments(id).get(0).sequence;

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ReversalOutcome out = service.postReversal(
                            new ReversalCommand(id, "rcsk-key", seq, new BigDecimal("300"), d, "同键"),
                            at(d, 5));
                    if (out.duplicate) {
                        duplicate.incrementAndGet();
                    } else {
                        applied.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.add(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        long reversalCount = service.getReversals(id).size();
        log.info("[输入] 16线程同键冲正300; [判定] 真实冲正={} 幂等返回={} 冲正流水={} 异常={}",
                applied.get(), duplicate.get(), reversalCount, errors.size());
        errors.forEach(e -> log.error("并发异常", e));
        assertTrue(errors.isEmpty());
        assertEquals(1, applied.get());
        assertEquals(threads - 1, duplicate.get());
        assertEquals(1, reversalCount);
    }
}
