package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.domain.LoanAccount;
import com.github.h3yow.loanservicingenginejava.domain.LoanTerms;
import com.github.h3yow.loanservicingenginejava.domain.PaymentCommand;
import com.github.h3yow.loanservicingenginejava.domain.RepaymentMethod;
import com.github.h3yow.loanservicingenginejava.domain.RejectReason;
import com.github.h3yow.loanservicingenginejava.domain.ReversalCommand;
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
 * 冲正并发：不同键并发冲正同一笔不超额、同一笔钱不会被冲两次；
 * 同键并发恰好一笔真实冲正；冲正与新还款并发下口径守恒。
 */
@SpringBootTest
class ReversalConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ReversalConcurrencyTest.class);

    @Autowired
    LoanServicingService service;

    private void open(String id, int n) {
        service.openAccount(id, new LoanTerms(new BigDecimal("120000"), new BigDecimal("0.12"), n,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 1),
                RepaymentMethod.EQUAL_INSTALLMENT, BigDecimal.ZERO));
    }

    private LocalDateTime at(LocalDate d, int minute) {
        return LocalDateTime.of(d.getYear(), d.getMonth(), d.getDayOfMonth(), 10, minute);
    }

    @Test
    void concurrentDistinctReversalsNeverExceedOriginalPayment() throws Exception {
        String id = "rconc-exceed";
        open(id, 12);
        LocalDate d = LocalDate.of(2025, 2, 1);
        service.postPayment(new PaymentCommand(id, id + "-big", new BigDecimal("2000"), d), at(d, 0));
        long seq = service.getPayments(id).get(0).sequence;
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
                    service.postReversal(new ReversalCommand(id, id + "-rv-" + idx, seq,
                                    new BigDecimal("100"), d, "并发退回"), at(d, 30));
                    accepted.incrementAndGet();
                } catch (ServicingException e) {
                    if (e.getReason() == RejectReason.REVERSAL_EXCEEDS_PAYMENT) {
                        rejected.incrementAndGet();
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

        BigDecimal reversed = service.getReversals(id).stream()
                .map(r -> r.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("[输入] 20线程对同一笔2000各冲100; [判定] 接受={} 超额拒绝={} 累计冲正={} 冲正流水={}",
                accepted.get(), rejected.get(), reversed, service.getReversals(id).size());
        assertTrue(errors.isEmpty(), "并发冲正不应出现超额拒绝以外的异常: " + errors);
        assertEquals(20, accepted.get(), "累计至多冲掉原额2000（每笔100，恰好20笔）");
        assertEquals(0, threads - accepted.get(), rejected.get());
        assertEquals(0, new BigDecimal("2000").compareTo(reversed), "同一笔钱不会被冲两次");
        // 整笔冲净后：原流水分摊清空、账上回到未入账口径
        assertTrue(service.getPayments(id).get(0).lines.isEmpty());
        LoanAccount account = service.getAccount(id);
        BigDecimal scheduledPaid = account.installments.stream()
                .map(i -> i.paidPrincipal.add(i.paidInterest).add(i.paidFee))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, scheduledPaid.signum(), "整笔冲净后各期计划已收归零");
    }

    @Test
    void concurrentSameReversalKeyExactlyOneApplied() throws Exception {
        String id = "rconc-samekey";
        open(id, 12);
        LocalDate d = LocalDate.of(2025, 2, 1);
        service.postPayment(new PaymentCommand(id, id + "-p", new BigDecimal("1000"), d), at(d, 0));
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
                    var out = service.postReversal(new ReversalCommand(id, id + "-same-rv", seq,
                                    new BigDecimal("100"), d, "重复键"), at(d, 30));
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
        log.info("[输入] 16线程同键同内容冲正100; [判定] 真实冲正={} 幂等返回={} 冲正流水笔数={}",
                applied.get(), duplicate.get(), service.getReversals(id).size());
        assertTrue(errors.isEmpty());
        assertEquals(1, applied.get(), "同键并发只允许一笔真实冲正");
        assertEquals(threads - 1, duplicate.get());
        assertEquals(1, service.getReversals(id).size());
        assertEquals(0, new BigDecimal("100").compareTo(
                service.getReversals(id).get(0).amount));
    }

    @Test
    void concurrentReversalAndPaymentKeepBooksConsistent() throws Exception {
        String id = "rconc-mixed";
        open(id, 12);
        LocalDate d = LocalDate.of(2025, 2, 1);
        // 先入账两笔各 500，之后冲正与新还款并发
        service.postPayment(new PaymentCommand(id, id + "-base1", new BigDecimal("500"), d), at(d, 0));
        service.postPayment(new PaymentCommand(id, id + "-base2", new BigDecimal("500"), d), at(d, 1));
        long seq1 = service.getPayments(id).get(0).sequence;
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    if (idx % 2 == 0) {
                        // 偶数线程：对第一笔 500 各冲 100（只有前 5 个能成功）
                        service.postReversal(new ReversalCommand(id, id + "-mix-rv-" + idx, seq1,
                                new BigDecimal("100"), d, "混合并发"), at(d, 30));
                    } else {
                        // 奇数线程：新还款 100
                        service.postPayment(new PaymentCommand(id, id + "-mix-pay-" + idx,
                                new BigDecimal("100"), d), at(d, 31));
                    }
                } catch (ServicingException e) {
                    if (e.getReason() != RejectReason.REVERSAL_EXCEEDS_PAYMENT
                            && e.getReason() != RejectReason.OVERPAYMENT) {
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

        BigDecimal grossPayments = service.getPayments(id).stream()
                .map(p -> p.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalReversed = service.getReversals(id).stream()
                .map(r -> r.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netReceived = grossPayments.subtract(totalReversed);
        LoanAccount account = service.getAccount(id);
        BigDecimal scheduledPaid = account.installments.stream()
                .map(i -> i.paidPrincipal.add(i.paidInterest).add(i.paidFee)
                        .add(i.paidPenalty))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("[输入] 冲正与还款8+8并发; [判定] 还款总额={} 冲正总额={} 净入账={} 各期已收(含罚息)={}",
                grossPayments, totalReversed, netReceived, scheduledPaid);
        assertTrue(errors.isEmpty(), "混合并发不应出现超额/超冲以外异常: " + errors);
        // 守恒：净入账 = 各期已收（当日无应计罚息时等于计划已收）
        assertEquals(0, netReceived.compareTo(scheduledPaid),
                "冲正与新还款并发下净入账必须等于账本各期已收");
        // 第一笔 500 最多被冲 500
        BigDecimal reversedFirst = service.getReversals(id).stream()
                .filter(r -> r.paymentSequence == seq1)
                .map(r -> r.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertTrue(reversedFirst.compareTo(new BigDecimal("500")) <= 0);
        // 各期不得超额冲抵
        account.installments.forEach(i -> {
            assertTrue(i.paidPrincipal.compareTo(i.scheduledPrincipal) <= 0);
            assertTrue(i.paidInterest.compareTo(i.scheduledInterest) <= 0);
        });
    }
}
