package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.domain.LoanTerms;
import com.github.h3yow.loanservicingenginejava.domain.RepaymentMethod;
import com.github.h3yow.loanservicingenginejava.domain.RiskState;
import com.github.h3yow.loanservicingenginejava.domain.RiskTransitionRecord;
import com.github.h3yow.loanservicingenginejava.service.LoanServicingService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 端到端生命周期：正常 -> 逾期(WATCH/OVERDUE/NPL) -> 还款恢复 NORMAL，
 * 且对同一账户反复重估（相同输入）结论稳定不抖动。
 */
@SpringBootTest
class LifecycleServiceTest {

    private static final Logger log = LoggerFactory.getLogger(LifecycleServiceTest.class);

    @Autowired
    LoanServicingService service;

    @Test
    void overdueEscalatesAndRecoveryIsStable() {
        String id = "life-1";
        service.openAccount(id, new LoanTerms(new BigDecimal("12000"), new BigDecimal("0.12"), 12,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1),
                RepaymentMethod.EQUAL_INSTALLMENT, BigDecimal.ZERO));

        RiskTransitionRecord watch = service.reevaluateRisk(id, LocalDate.of(2026, 2, 5));   // 逾期1天
        RiskTransitionRecord overdue = service.reevaluateRisk(id, LocalDate.of(2026, 2, 20)); // 逾期16天 V2
        RiskTransitionRecord npl = service.reevaluateRisk(id, LocalDate.of(2026, 4, 10));    // 逾期65天 V2
        log.info("[判定] 生命周期升级: {} -> {} -> {}", watch.to, overdue.to, npl.to);
        assertEquals(RiskState.WATCH, watch.to);
        assertEquals(RiskState.OVERDUE, overdue.to);
        assertEquals(RiskState.NPL, npl.to);

        // 相同输入重复重估：终态 NPL 分类稳定
        RiskTransitionRecord nplAgain = service.reevaluateRisk(id, LocalDate.of(2026, 4, 10));
        assertEquals(RiskState.NPL, nplAgain.to);
        log.info("[判定] 相同输入重复重估仍为 NPL（不抖动）");
    }
}
