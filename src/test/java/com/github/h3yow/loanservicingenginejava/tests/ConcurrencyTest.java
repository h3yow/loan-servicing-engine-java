package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.domain.AccountSnapshot;
import com.github.h3yow.loanservicingenginejava.domain.ComponentType;
import com.github.h3yow.loanservicingenginejava.error.LoanEngineException;
import com.github.h3yow.loanservicingenginejava.tests.TestFixtures.Engine;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.OpenAccountRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.PaymentRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.RiskTransitionRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并发：同一账户并发还款与状态变更后余额、状态保持一致。 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    private Engine account(String id) {
        Engine engine = TestFixtures.engine();
        engine.service().openAccount(new OpenAccountRequest(id,
                new BigDecimal("12000.00"), new BigDecimal("0.12"), 12,
                LocalDate.of(2026, 1, 31), "ACT/365",
                LocalDateTime.of(2026, 1, 1, 10, 0)));
        return engine;
    }

    @Test
    void concurrentPayments_withDistinctKeys_balanceConsistent() throws Exception {
        Engine engine = account("A-CONC-1");
        int threads = 8;
        // 逾期后每期应缴 1120，多期到期：所有线程在不同日期还不同期的钱。
        // 为简化，全部针对同一当前应缴池提交小额，允许部分成功部分被超额拒绝，
        // 最终守恒必须成立：成功扣款之和 == 总待还减少额。
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    engine.service().submitPayment(new PaymentRequest(
                            "A-CONC-1", "C-KEY-" + idx, new BigDecimal("200.00"),
                            LocalDateTime.of(2026, 5, 10, 10, idx), false));
                    return Boolean.TRUE;
                } catch (LoanEngineException e) {
                    return Boolean.FALSE;
                }
            }));
        }
        int accepted = 0;
        for (Future<Boolean> f : futures) {
            if (f.get()) {
                accepted++;
            }
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        AccountSnapshot snap = engine.service()
                .replayAsOf("A-CONC-1", LocalDateTime.of(2026, 5, 10, 12, 0));
        // 成功扣款总额
        long paidCents = snap.payments().stream()
                .flatMap(r -> r.allocations().stream())
                .map(a -> a.amount().movePointRight(2).longValue())
                .reduce(0L, Long::sum);
        // 快照余额守恒
        BigDecimal components = snap.balance().outstandingPrincipal()
                .add(snap.balance().outstandingInterest())
                .add(snap.balance().outstandingFee())
                .add(snap.balance().accruedPenalty());
        log.info("[输入] 8 个线程各提交 200.00（不同幂等键，同日不同分钟）");
        log.info("[判定依据] 接受笔数={} 总分摊(分)={} 总待还={} 组件之和={}",
                accepted, paidCents, snap.balance().totalDue(), components);
        assertEquals(components, snap.balance().totalDue());
        assertEquals(accepted, snap.payments().size());
        // 每一笔成功扣款都恰好分摊 200.00
        for (var r : snap.payments()) {
            assertEquals(new BigDecimal("200.00"), r.allocated());
        }
        // 总扣款（分） == 接受笔数 * 20000
        assertEquals((long) accepted * 20000L, paidCents);
    }

    @Test
    void concurrentPaymentAndRiskTransition_finalStateCoherent() throws Exception {
        Engine engine = account("A-CONC-2");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        ExecutorCompletionService<Object> ecs = new ExecutorCompletionService<>(pool);

        // 多个还款（逾期后）+ 多个风险迁移并发；风险迁移只允许逐级，非法的被拒绝
        ecs.submit(() -> engine.service().submitPayment(new PaymentRequest(
                "A-CONC-2", "RC-1", new BigDecimal("500.00"),
                LocalDateTime.of(2026, 5, 10, 10, 0), false)));
        ecs.submit(() -> engine.service().transitionRisk("A-CONC-2",
                new RiskTransitionRequest("WATCH", "v1", "manual-1",
                        LocalDateTime.of(2026, 5, 10, 10, 1))));
        ecs.submit(() -> engine.service().submitPayment(new PaymentRequest(
                "A-CONC-2", "RC-2", new BigDecimal("500.00"),
                LocalDateTime.of(2026, 5, 10, 10, 2), false)));
        ecs.submit(() -> engine.service().transitionRisk("A-CONC-2",
                new RiskTransitionRequest("SUBSTANDARD", "v1", "manual-2",
                        LocalDateTime.of(2026, 5, 10, 10, 3))));

        int successes = 0;
        int rejected = 0;
        for (int i = 0; i < 4; i++) {
            try {
                ecs.take().get();
                successes++;
            } catch (ExecutionException e) {
                rejected++;
                log.info("[并发拒绝] {}", e.getCause().getMessage());
            }
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        AccountSnapshot snap = engine.service()
                .replayAsOf("A-CONC-2", LocalDateTime.of(2026, 5, 10, 11, 0));
        log.info("[输入] 2 笔还款 + 2 笔风险迁移并发");
        log.info("[最终状态] 成功={} 拒绝={} 风险状态={} 风险历史条数={} 总待还={}",
                successes, rejected, snap.riskState(),
                snap.riskHistory().size(), snap.balance().totalDue());

        // 风险历史必须按生效时间有序（NORMAL->WATCH->SUBSTANDARD），无跳变
        List<String> chain = snap.riskHistory().stream()
                .map(h -> h.fromState() + "->" + h.toState())
                .collect(Collectors.toList());
        log.info("[判定依据] 风险迁移链={}", chain);
        assertTrue(chain.stream().allMatch(t ->
                t.equals("NORMAL->WATCH") || t.equals("WATCH->SUBSTANDARD")));
        // 余额守恒
        BigDecimal components = snap.balance().outstandingPrincipal()
                .add(snap.balance().outstandingInterest())
                .add(snap.balance().outstandingFee())
                .add(snap.balance().accruedPenalty());
        assertEquals(snap.balance().totalDue(), components);
    }

    @Test
    void sameKeyConcurrentSubmit_chargesExactlyOnce() throws Exception {
        Engine engine = account("A-CONC-3");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> engine.service().submitPayment(new PaymentRequest(
                    "A-CONC-3", "SAME-KEY", new BigDecimal("1120.00"),
                    LocalDateTime.of(2026, 2, 3, 10, 0), false)));
        }
        int ok = 0;
        int dup = 0;
        List<Throwable> errors = new ArrayList<>();
        for (Future<Object> f : pool.invokeAll(tasks)) {
            try {
                f.get();
                ok++;
            } catch (ExecutionException e) {
                errors.add(e.getCause());
                dup++;
            }
        }
        pool.shutdown();
        AccountSnapshot snap = engine.service()
                .replayAsOf("A-CONC-3", LocalDateTime.of(2026, 2, 3, 12, 0));
        log.info("[输入] 6 线程同键 SAME-KEY 同时提交 1120.00");
        log.info("[最终状态] 成功返回={} 其余按重复返回 事件笔数={} 剩余本金={}",
                ok, snap.payments().size(), snap.balance().outstandingPrincipal());
        // 只有一笔入账事件；其余线程要么拿到同一回执，要么在锁后重放得到同一回执
        assertEquals(1, snap.payments().size());
        assertEquals(new BigDecimal("11000.00"), snap.balance().outstandingPrincipal());
        // 并发下不应有非幂等冲突之外的错误
        assertTrue(errors.isEmpty(), "意外错误: " + errors);
    }
}
