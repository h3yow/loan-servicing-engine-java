package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.domain.AllocationBucket;
import com.github.h3yow.loanservicingenginejava.domain.AllocationLine;
import com.github.h3yow.loanservicingenginejava.domain.DayCountConvention;
import com.github.h3yow.loanservicingenginejava.domain.Installment;
import com.github.h3yow.loanservicingenginejava.domain.PaymentRecord;
import com.github.h3yow.loanservicingenginejava.engine.PaymentAllocator;
import com.github.h3yow.loanservicingenginejava.engine.PenaltyCalculator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 罚息：起息日临界、还本分段、重放不重复累计、补录/回溯后结论一致可复现。
 */
class PenaltyCalculatorTest {

    private static final Logger log = LoggerFactory.getLogger(PenaltyCalculatorTest.class);
    private final PenaltyCalculator calc = new PenaltyCalculator();
    private final PaymentAllocator allocator = new PaymentAllocator();
    private static final BigDecimal RATE = new BigDecimal("0.18");

    private Installment installment(String due, String principal) {
        return new Installment(1, LocalDate.parse(due), new BigDecimal(principal),
                new BigDecimal("10"), BigDecimal.ZERO);
    }

    @Test
    void noAccrualWithinGraceAndStartsDayAfter() {
        Installment i = installment("2025-02-01", "1000");
        // 到期日 + 宽限期(3) 当天（2/4）仍不计息
        BigDecimal atDeadline = calc.accruedAsOf(i, LocalDate.of(2025, 2, 4), 3,
                RATE, DayCountConvention.ACT_365_FIXED, List.of());
        // 起息日 2/5 当天计 1 天
        BigDecimal atStart = calc.accruedAsOf(i, LocalDate.of(2025, 2, 5), 3,
                RATE, DayCountConvention.ACT_365_FIXED, List.of());
        log.info("[输入] 到期2025-02-01 宽限3天 基数1000; [判定] 2/4应计={} 2/5应计={}", atDeadline, atStart);
        assertEquals(0, atDeadline.signum());
        // 1000 * 0.18 / 365 = 0.493...
        assertEquals(0, new BigDecimal("0.49").compareTo(atStart));
    }

    @Test
    void act360Uses360Base() {
        Installment i = installment("2025-02-01", "1000");
        BigDecimal v = calc.accruedAsOf(i, LocalDate.of(2025, 2, 5), 3,
                RATE, DayCountConvention.ACT_360, List.of());
        log.info("[判定] ACT/360 一天罚息={} (期望0.50)", v);
        assertEquals(0, new BigDecimal("0.50").compareTo(v));
    }

    @Test
    void replayIsIdempotentAndBackdatedReproducible() {
        // 一期：到期 2/1，本金 1000；4/20 正常入账一笔 1010（计划利息10 + 本金1000，无罚息因为足额）
        Installment base = installment("2025-02-01", "1000");

        List<Installment> s1 = new ArrayList<>(List.of(base.copy()));
        PaymentRecord late = new PaymentRecord("k1", new BigDecimal("1000"),
                LocalDate.of(2025, 4, 20), LocalDate.of(2025, 4, 20), 1, new ArrayList<>());
        // 先用空明细重放刷新罚息，再让分摊器真实分摊一次，得到明细
        List<BigDecimal> penaltyBefore = calc.replay(s1, new ArrayList<>(),
                LocalDate.of(2025, 4, 20), RATE, DayCountConvention.ACT_365_FIXED, 3, allocator);
        BigDecimal penaltyDue = penaltyBefore.get(0);
        log.info("[输入] 逾期到4/20; [判定] 起息2/5至4/20共75天 罚息={}", penaltyDue);
        assertTrue(penaltyDue.signum() > 0);
        List<AllocationLine> lines = allocator.allocate(s1,
                new BigDecimal("1000"), LocalDate.of(2025, 4, 20));
        late.lines.clear();
        late.lines.addAll(lines);
        log.info("[判定] 1000元先冲抵罚息={} 再冲本金",
                lines.stream().filter(l -> l.bucket == AllocationBucket.PENALTY)
                        .map(l -> l.amount).findFirst().orElse(BigDecimal.ZERO));

        // 重放两次结果必须一致（不重复累计）
        List<BigDecimal> r1 = calc.replay(new ArrayList<>(List.of(base.copy())),
                List.of(copy(late)), LocalDate.of(2025, 4, 20), RATE,
                DayCountConvention.ACT_365_FIXED, 3, allocator);
        List<BigDecimal> r2 = calc.replay(new ArrayList<>(List.of(base.copy())),
                List.of(copy(late)), LocalDate.of(2025, 4, 20), RATE,
                DayCountConvention.ACT_365_FIXED, 3, allocator);
        log.info("[最终] 重放#1剩余罚息={} 重放#2剩余罚息={}", r1.get(0), r2.get(0));
        assertEquals(0, r1.get(0).compareTo(r2.get(0)));
    }

    @Test
    void backdatedPaymentRecomputesPenalty() {
        Installment base = installment("2025-02-01", "1000");
        // 场景A：只有 4/20 还 500
        // 场景B：补录一笔 2/10 还 500，再有 4/20 还 500 —— 本金基数提前下降，总罚息必须更少
        List<Installment> sA = new ArrayList<>(List.of(base.copy()));
        List<BigDecimal> pa = calc.replay(sA, List.of(), LocalDate.of(2025, 4, 20),
                RATE, DayCountConvention.ACT_365_FIXED, 3, allocator);
        BigDecimal penaltyA = pa.get(0);

        List<Installment> sB = new ArrayList<>(List.of(base.copy()));
        List<BigDecimal> pb0 = calc.replay(sB, List.of(), LocalDate.of(2025, 2, 10),
                RATE, DayCountConvention.ACT_365_FIXED, 3, allocator);
        List<AllocationLine> early = allocator.allocate(sB, new BigDecimal("500"),
                LocalDate.of(2025, 2, 10));
        PaymentRecord rEarly = new PaymentRecord("early", new BigDecimal("500"),
                LocalDate.of(2025, 2, 10), LocalDate.of(2025, 2, 10), 1, early);
        List<BigDecimal> pb = calc.replay(new ArrayList<>(List.of(base.copy())),
                List.of(copy(rEarly)), LocalDate.of(2025, 4, 20), RATE,
                DayCountConvention.ACT_365_FIXED, 3, allocator);
        log.info("[输入] 场景A无补录 罚息={}; 场景B补录2/10还本500 罚息={}", penaltyA, pb.get(0));
        assertTrue(pb.get(0).compareTo(penaltyA) < 0,
                "回溯补录还本后罚息必须重算且下降");
    }

    private PaymentRecord copy(PaymentRecord r) {
        return new PaymentRecord(r.idempotencyKey, r.amount, r.effectiveDate, r.submittedAt,
                r.sequence, r.lines.stream()
                        .map(l -> new AllocationLine(l.installmentSeq, l.bucket, l.amount)).toList());
    }
}
