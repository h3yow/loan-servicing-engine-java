package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.domain.AllocationBucket;
import com.github.h3yow.loanservicingenginejava.domain.Installment;
import com.github.h3yow.loanservicingenginejava.engine.OverdueEvaluator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逾期判定：宽限期临界（含截止日当天不逾期）、周末顺延、已结清期不算逾期。
 */
class OverdueEvaluatorTest {

    private static final Logger log = LoggerFactory.getLogger(OverdueEvaluatorTest.class);
    private final OverdueEvaluator evaluator = new OverdueEvaluator();

    private Installment installment(String due) {
        return new Installment(1, LocalDate.parse(due), new BigDecimal("100"),
                new BigDecimal("10"), BigDecimal.ZERO);
    }

    @Test
    void graceBoundary() {
        Installment i = installment("2025-02-01");
        // 到期 2/1，宽限 3 天，截止日 2/4（含）不算逾期；2/5 起逾期 1 天
        assertEquals(0, evaluator.overdueDays(i, LocalDate.of(2025, 2, 4), 3, false));
        assertEquals(1, evaluator.overdueDays(i, LocalDate.of(2025, 2, 5), 3, false));
        assertEquals(10, evaluator.overdueDays(i, LocalDate.of(2025, 2, 14), 3, false));
        log.info("[判定] 到期2/1 宽限3天: 2/4逾期0天 2/5逾期1天 2/14逾期10天");
    }

    @Test
    void zeroGraceStartsNextDay() {
        Installment i = installment("2025-02-01");
        assertEquals(0, evaluator.overdueDays(i, LocalDate.of(2025, 2, 1), 0, false));
        assertEquals(1, evaluator.overdueDays(i, LocalDate.of(2025, 2, 2), 0, false));
        log.info("[判定] 零宽限: 到期当天不逾期 次日逾期1天");
    }

    @Test
    void businessDayExtensionSaturdayDeadline() {
        // 2025-02-01 是周六；+3 = 2025-02-04 周二，不顺延
        Installment midweek = installment("2025-02-01");
        assertEquals(1, evaluator.overdueDays(midweek, LocalDate.of(2025, 2, 5), 3, true));

        // 到期 2025-01-30（周四），+3 = 2/2 周日，顺延至 2/3 周一：2/3 不逾期，2/4 逾期 1 天
        Installment nearWeekend = installment("2025-01-30");
        assertEquals(0, evaluator.overdueDays(nearWeekend, LocalDate.of(2025, 2, 3), 3, true));
        assertEquals(1, evaluator.overdueDays(nearWeekend, LocalDate.of(2025, 2, 4), 3, true));
        // 不开顺延：2/3 已逾期 1 天
        assertEquals(1, evaluator.overdueDays(nearWeekend, LocalDate.of(2025, 2, 3), 3, false));
        log.info("[判定] 截止日落周日: 顺延则周一不逾期/周二逾期1天; 不顺延则周一已逾期");
    }

    @Test
    void fullyPaidInstallmentNotOverdue() {
        Installment i = installment("2025-02-01");
        i.paidPrincipal = new BigDecimal("100");
        i.paidInterest = new BigDecimal("10");
        assertFalse(evaluator.isOverdue(i, LocalDate.of(2025, 6, 1), 3));
        log.info("[判定] 计划已全额清偿的期不再计逾期");
    }

    @Test
    void partiallyPaidStillOverdue() {
        Installment i = installment("2025-02-01");
        i.paidPrincipal = new BigDecimal("50");
        assertTrue(evaluator.isOverdue(i, LocalDate.of(2025, 6, 1), 3));
        int max = evaluator.maxOverdueDays(List.of(i), LocalDate.of(2025, 6, 1), 3, false);
        assertEquals(117, max);
        log.info("[判定] 部分还款但未结清: 仍逾期 {} 天", max);
    }
}
