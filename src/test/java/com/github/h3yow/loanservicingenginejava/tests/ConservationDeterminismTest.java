package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.domain.AccountSnapshot;
import com.github.h3yow.loanservicingenginejava.domain.PaymentReceipt;
import com.github.h3yow.loanservicingenginejava.tests.TestFixtures.Engine;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.FeeRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.OpenAccountRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.PaymentRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 守恒与确定性：任意时刻余额构成守恒；相同输入重复重放结果一致。 */
class ConservationDeterminismTest {

    private static final Logger log = LoggerFactory.getLogger(ConservationDeterminismTest.class);

    @Test
    void conservation_holds_atEveryPoint() {
        Engine engine = TestFixtures.engine();
        engine.service().openAccount(new OpenAccountRequest("A-CONS-1",
                new BigDecimal("36000.00"), new BigDecimal("0.12"), 24,
                LocalDate.of(2026, 1, 31), "ACT/365",
                LocalDateTime.of(2026, 1, 1, 10, 0)));
        engine.service().chargeFee(new FeeRequest("A-CONS-1", "FEE-C1",
                new BigDecimal("88.88"), LocalDateTime.of(2026, 2, 1, 9, 0)));

        // 部分逾期还款（含费用与罚息分摊）
        engine.service().submitPayment(new PaymentRequest("A-CONS-1", "K-1",
                new BigDecimal("500.00"), LocalDateTime.of(2026, 2, 20, 10, 0), false));
        engine.service().submitPayment(new PaymentRequest("A-CONS-1", "K-2",
                new BigDecimal("300.00"), LocalDateTime.of(2026, 3, 15, 10, 0), false));
        // 回溯补录一笔更早的还款
        engine.service().submitPayment(new PaymentRequest("A-CONS-1", "K-BACK",
                new BigDecimal("200.00"), LocalDateTime.of(2026, 2, 10, 10, 0), false));

        LocalDateTime[] checkpoints = {
                LocalDateTime.of(2026, 2, 5, 12, 0),
                LocalDateTime.of(2026, 2, 10, 12, 0),
                LocalDateTime.of(2026, 2, 20, 12, 0),
                LocalDateTime.of(2026, 4, 1, 12, 0),
                LocalDateTime.of(2026, 6, 1, 12, 0),
        };
        for (LocalDateTime cp : checkpoints) {
            AccountSnapshot snap = engine.service().replayAsOf("A-CONS-1", cp);
            BigDecimal sum = snap.balance().outstandingPrincipal()
                    .add(snap.balance().outstandingInterest())
                    .add(snap.balance().outstandingFee())
                    .add(snap.balance().accruedPenalty());
            log.info("[判定依据] {} 本金={} 利息={} 费用={} 罚息={} 合计={} 总待还={}",
                    cp, snap.balance().outstandingPrincipal(),
                    snap.balance().outstandingInterest(),
                    snap.balance().outstandingFee(),
                    snap.balance().accruedPenalty(), sum, snap.balance().totalDue());
            assertEquals(snap.balance().totalDue(), sum,
                    "守恒在 " + cp + " 被破坏");
        }
    }

    @Test
    void replay_sameInput_sameResult() {
        Engine engine = TestFixtures.engine();
        engine.service().openAccount(new OpenAccountRequest("A-CONS-2",
                new BigDecimal("12000.00"), new BigDecimal("0.12"), 12,
                LocalDate.of(2026, 1, 31), "ACT/365",
                LocalDateTime.of(2026, 1, 1, 10, 0)));
        engine.service().chargeFee(new FeeRequest("A-CONS-2", "FEE-C2",
                new BigDecimal("30.00"), LocalDateTime.of(2026, 2, 1, 9, 0)));
        engine.service().submitPayment(new PaymentRequest("A-CONS-2", "D-1",
                new BigDecimal("400.00"), LocalDateTime.of(2026, 2, 20, 10, 0), false));

        LocalDateTime asOf = LocalDateTime.of(2026, 5, 1, 10, 0);
        AccountSnapshot r1 = engine.service().replayAsOf("A-CONS-2", asOf);
        AccountSnapshot r2 = engine.service().replayAsOf("A-CONS-2", asOf);
        AccountSnapshot r3 = engine.service().replayAsOf("A-CONS-2", asOf);

        log.info("[输入] 同一事件序列重放 3 次，asOf={}", asOf);
        log.info("[判定依据] 总待还依次={}, {}, {}",
                r1.balance().totalDue(), r2.balance().totalDue(), r3.balance().totalDue());
        assertEquals(r1, r2, "重放两次结果必须完全一致");
        assertEquals(r2, r3, "重放三次结果必须完全一致");

        // 回执也必须逐字段一致（含分摊明细）
        PaymentReceipt p1 = r1.payments().get(0);
        PaymentReceipt p2 = r2.payments().get(0);
        assertEquals(p1, p2);
        log.info("[最终状态] 重放确定性成立：{}", r1.balance());
    }
}
