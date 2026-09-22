package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.domain.LoanAccount;
import com.github.h3yow.loanservicingenginejava.domain.LoanTerms;
import com.github.h3yow.loanservicingenginejava.domain.PaymentCommand;
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
 * 并发：同一账户并发还款不丢更新、不超额入账、总额守恒；
 * 同键并发恰好一笔成功，其余命中幂等。
 */
@SpringBootTest
class ConcurrencyServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyServiceTest.class);

    @Autowired
    LoanServicingService service;

    private LoanTerms terms(int n) {
        return new LoanTerms(new BigDecimal("120000"), new BigDecimal("0.12"), n,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 1),
                RepaymentMethod.EQUAL_INSTALLMENT, BigDecimal.ZERO);
    }

    @Test
    void concurrentDistinctPaymentsAllAppliedExactlyOnce() throws Exception {
        String id = "conc-distinct";
        service.openAccount(id, terms(12));
        int threads = 20;
        BigDecimal each = new BigDecimal("100");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    service.postPayment(
                            new PaymentCommand(id, "ck-" + idx, each, LocalDate.of(2025, 2, 1)),
                            LocalDateTime.of(2025, 2, 1, 10, idx % 60));
                    ok.incrementAndGet();
                } catch (Exception e) {
                    errors.add(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        long accepted = service.getPayments(id).stream()
                .filter(p -> p.idempotencyKey.startsWith("ck-")).count();
        BigDecimal acceptedSum = service.getPayments(id).stream()
                .map(p -> p.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("[输入] 20线程各还100(不同键); [判定] 成功={} 异常={} 流水笔数={} 入账总额={}",
                ok.get(), errors.size(), accepted, acceptedSum);
        errors.forEach(e -> log.error("并发异常", e));
        assertTrue(errors.isEmpty(), "并发还款不应产生异常");
        assertEquals(threads, accepted, "每笔恰好入账一次");
        assertEquals(0, new BigDecimal("2000.00").compareTo(acceptedSum), "入账总额守恒");
    }

    @Test
    void concurrentSameKeyExactlyOneCharge() throws Exception {
        String id = "conc-samekey";
        service.openAccount(id, terms(12));
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger charged = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    var out = service.postPayment(
                            new PaymentCommand(id, "same-key", new BigDecimal("100"),
                                    LocalDate.of(2025, 2, 1)),
                            LocalDateTime.of(2025, 2, 1, 10, 0));
                    if (out.duplicate) {
                        duplicate.incrementAndGet();
                    } else {
                        charged.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.add(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        long records = service.getPayments(id).size();
        log.info("[输入] 16线程同键同金额; [判定] 实际扣款={} 幂等返回={} 流水笔数={} 异常={}",
                charged.get(), duplicate.get(), records, errors.size());
        assertTrue(errors.isEmpty());
        assertEquals(1, charged.get(), "同键并发只允许一笔真实扣款");
        assertEquals(threads - 1, duplicate.get());
        assertEquals(1, records);
    }

    @Test
    void concurrentPaymentsNeverExceedOutstanding() throws Exception {
        String id = "conc-overpay";
        service.openAccount(id, terms(1)); // 单期，总额较小
        LoanAccount a = service.getAccount(id);
        BigDecimal due = a.installments.stream()
                .map(i -> i.scheduledPrincipal.add(i.scheduledInterest))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int threads = 30;
        BigDecimal each = due.divide(BigDecimal.valueOf(10), 2, java.math.RoundingMode.HALF_UP);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger accepted = new AtomicInteger();
        ConcurrentLinkedQueue<BigDecimal> acceptedAmounts = new ConcurrentLinkedQueue<>();
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    service.postPayment(new PaymentCommand(id, "ov-" + idx, each,
                                    LocalDate.of(2025, 2, 1)),
                            LocalDateTime.of(2025, 2, 1, 10, 0));
                    accepted.incrementAndGet();
                    acceptedAmounts.add(each);
                } catch (ServicingException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        BigDecimal acceptedSum = acceptedAmounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("[输入] 30线程各还总额的1/10; [判定] 接受={} 拒绝(超额)={} 接受总额={} 应还={}",
                accepted.get(), rejected.get(), acceptedSum, due);
        assertTrue(acceptedSum.compareTo(due) <= 0, "并发下入账总额不得超过应还总额");
        // 账上不得出现负余额
        LoanAccount finalAccount = service.getAccount(id);
        for (var i : finalAccount.installments) {
            assertTrue(i.paidPrincipal.compareTo(i.scheduledPrincipal) <= 0);
            assertTrue(i.paidInterest.compareTo(i.scheduledInterest) <= 0);
        }
    }
}
