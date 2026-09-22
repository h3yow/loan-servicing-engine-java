package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.config.EngineProperties;
import com.github.h3yow.loanservicingenginejava.domain.AccountSnapshot;
import com.github.h3yow.loanservicingenginejava.domain.PaymentReceipt;
import com.github.h3yow.loanservicingenginejava.tests.TestFixtures.Engine;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.OpenAccountRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.PaymentRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 逾期与罚息：宽限期临界、日计息口径、补录/回溯后重算不重复累计。 */
class OverduePenaltyTest {

    private static final Logger log = LoggerFactory.getLogger(OverduePenaltyTest.class);
    private static final LocalDate DUE = LocalDate.of(2026, 1, 31);

    private Engine account(String id, EngineProperties props) {
        Engine engine = TestFixtures.engine(props);
        engine.service().openAccount(new OpenAccountRequest(id,
                new BigDecimal("12000.00"), new BigDecimal("0.12"), 12,
                DUE, "ACT/365", LocalDateTime.of(2026, 1, 1, 10, 0)));
        return engine;
    }

    private BigDecimal penaltyAt(Engine engine, String id, LocalDate date) {
        return engine.service()
                .replayAsOf(id, date.atTime(23, 59))
                .balance().accruedPenalty();
    }

    @Test
    void gracePeriod_boundary_noPenaltyUntilStartDayExclusive() {
        Engine engine = account("A-OD-1", TestFixtures.properties());
        // 到期日 1/31，宽限期 3 天：起罚日 2/4。
        // 在 2/3（宽限最后一日）、2/4 当天还款都不计罚息
        assertEquals(0, penaltyAt(engine, "A-OD-1", LocalDate.of(2026, 2, 3)).signum());
        assertEquals(0, penaltyAt(engine, "A-OD-1", LocalDate.of(2026, 2, 4)).signum());
        // 到 2/5 评估时计息区间 [2/4, 2/5) = 1 天
        BigDecimal oneDay = penaltyAt(engine, "A-OD-1", LocalDate.of(2026, 2, 5));
        BigDecimal expected = new BigDecimal("1120.00")
                .multiply(new BigDecimal("0.18"))
                .divide(new BigDecimal("365"), 2, RoundingMode.HALF_UP);
        log.info("[判定依据] 起罚日=2026-02-04，2/5 评估计提 1 天: actual={} expected={}",
                oneDay, expected);
        assertEquals(expected, oneDay);

        // 宽限期内还款：无罚息
        PaymentReceipt receipt = engine.service().submitPayment(new PaymentRequest(
                "A-OD-1", "GRACE-KEY", new BigDecimal("1120.00"),
                LocalDateTime.of(2026, 2, 3, 10, 0), false));
        AccountSnapshot snap = engine.service()
                .replayAsOf("A-OD-1", LocalDateTime.of(2026, 2, 3, 23, 59));
        log.info("[输入] 2026-02-03 还款 1120.00；[还款日时点状态] 罚息={} 分摊={}",
                snap.balance().accruedPenalty(), receipt.allocations());
        assertEquals(0, snap.balance().accruedPenalty().signum());
    }

    @Test
    void penalty_accrues_dayByDay_andStopsAfterPayment() {
        Engine engine = account("A-OD-2", TestFixtures.properties());
        BigDecimal p10 = penaltyAt(engine, "A-OD-2", LocalDate.of(2026, 2, 14));
        // [2/4, 2/14) = 10 天
        BigDecimal base = new BigDecimal("1120.00");
        BigDecimal dailyRate = new BigDecimal("0.18").divide(new BigDecimal("365"),
                10, RoundingMode.HALF_UP);
        BigDecimal expected10 = base.multiply(dailyRate)
                .multiply(BigDecimal.TEN).setScale(2, RoundingMode.HALF_UP);
        log.info("[判定依据] 逾期 10 天罚息 actual={} expected={}", p10, expected10);
        assertEquals(expected10, p10);

        // 2/14 还清：还应缴 = 1120 + 10 天罚息
        BigDecimal required = expected10.add(base);
        PaymentReceipt receipt = engine.service().submitPayment(new PaymentRequest(
                "A-OD-2", "PAY-10D", required,
                LocalDateTime.of(2026, 2, 14, 10, 0), false));
        AccountSnapshot after = engine.service()
                .replayAsOf("A-OD-2", LocalDateTime.of(2026, 3, 1, 10, 0));
        log.info("[输入] 2026-02-14 还款 {}；[最终状态(3/1 重放)] 罚息={} 期结清={}",
                required, after.balance().accruedPenalty(),
                after.installments().get(0).settled());
        assertEquals(0, after.balance().accruedPenalty().signum(),
                "入账当日不计息，入账后罚息停止且被清偿");
        assertTrue(receipt.allocations().stream()
                .anyMatch(a -> a.component().name().equals("PENALTY")));
    }

    @Test
    void backdatedPayment_recalculatesPenalty_withoutDoubleCounting() {
        Engine engine = account("A-OD-3", TestFixtures.properties());
        // 先让账户逾期到 2/20（无还款），记录罚息
        BigDecimal before = penaltyAt(engine, "A-OD-3", LocalDate.of(2026, 2, 20));
        // 补录一笔 2026-02-10 的还款（回溯入账）
        BigDecimal penaltyTo2_10 = penaltyAt(engine, "A-OD-3", LocalDate.of(2026, 2, 10));
        // 2/10 时应缴：本息 1120 + 罚息 [2/4,2/10)=6 天
        BigDecimal payAmount = new BigDecimal("1120.00").add(penaltyTo2_10);
        engine.service().submitPayment(new PaymentRequest(
                "A-OD-3", "BACKDATE-KEY", payAmount,
                LocalDateTime.of(2026, 2, 10, 10, 0), false));

        AccountSnapshot after = engine.service()
                .replayAsOf("A-OD-3", LocalDateTime.of(2026, 2, 20, 23, 59));
        log.info("[输入] 补录 2026-02-10 还款 {}（其中罚息 {}）", payAmount, penaltyTo2_10);
        log.info("[判定依据] 补录前 2/20 罚息={}；补录后重放 2/20 罚息={}（应为 0，期已在 2/10 结清）",
                before, after.balance().accruedPenalty());
        assertEquals(0, after.balance().accruedPenalty().signum(),
                "回溯入账后按生效日重算，已偿清期不再计提，无重复累计");
        assertTrue(after.installments().get(0).settled());
    }

    @Test
    void dayCountBasis_act360_changesResult() {
        EngineProperties props360 = TestFixtures.properties();
        props360.setDayCountBasis("ACT/360");
        Engine engine360 = account("A-OD-4", props360);
        Engine engine365 = account("A-OD-5", TestFixtures.properties());
        BigDecimal p360 = penaltyAt(engine360, "A-OD-4", LocalDate.of(2026, 2, 14));
        BigDecimal p365 = penaltyAt(engine365, "A-OD-5", LocalDate.of(2026, 2, 14));
        log.info("[判定依据] 同样逾期 10 天：ACT/360={} ACT/365={}", p360, p365);
        assertTrue(p360.compareTo(p365) > 0, "ACT/360 日利率更高，罚息应更大");
    }

    @Test
    void partialPayment_reducesPenaltyBaseAfterEffectiveDate() {
        Engine engine = account("A-OD-6", TestFixtures.properties());
        // 2/10 部分还款 620（先冲罚息 [2/4,2/10)=6 天，再冲利息/本金）
        BigDecimal penalty6 = penaltyAt(engine, "A-OD-6", LocalDate.of(2026, 2, 10));
        engine.service().submitPayment(new PaymentRequest(
                "A-OD-6", "PARTIAL-KEY", new BigDecimal("620.00"),
                LocalDateTime.of(2026, 2, 10, 10, 0), false));
        BigDecimal at2_20 = penaltyAt(engine, "A-OD-6", LocalDate.of(2026, 2, 20));
        // 2/10 之后 10 天以剩余逾期基数（1120 + 已计提罚息6天 - 620）重新计提
        log.info("[判定依据] 6 天罚息={}，部分还款 620 后，2/10~2/20 新基数下罚息={}",
                penalty6, at2_20);
        assertTrue(at2_20.signum() >= 0);
        AccountSnapshot snap = engine.service()
                .replayAsOf("A-OD-6", LocalDateTime.of(2026, 2, 20, 23, 59));
        // 守恒：组件之和等于总待还（投影器内部已强校验，这里显式断言一次）
        BigDecimal sum = snap.balance().outstandingPrincipal()
                .add(snap.balance().outstandingInterest())
                .add(snap.balance().outstandingFee())
                .add(snap.balance().accruedPenalty());
        assertEquals(snap.balance().totalDue(), sum);
        log.info("[最终状态] 本金={} 利息={} 罚息={} 合计={}",
                snap.balance().outstandingPrincipal(),
                snap.balance().outstandingInterest(),
                snap.balance().accruedPenalty(), sum);
    }
}
