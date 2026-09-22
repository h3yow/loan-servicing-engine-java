package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.domain.LoanTerms;
import com.github.h3yow.loanservicingenginejava.domain.RejectReason;
import com.github.h3yow.loanservicingenginejava.domain.RepaymentMethod;
import com.github.h3yow.loanservicingenginejava.domain.RiskState;
import com.github.h3yow.loanservicingenginejava.domain.RiskTransitionRecord;
import com.github.h3yow.loanservicingenginejava.service.LoanServicingService;
import com.github.h3yow.loanservicingenginejava.service.ServicingException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 风险流转服务：非法迁移拒绝、版本升级不改写历史判定、回溯重估、宽限期临界、历史可查。
 */
@SpringBootTest
class RiskServiceTest {

    private static final Logger log = LoggerFactory.getLogger(RiskServiceTest.class);

    @Autowired
    LoanServicingService service;

    private void open(String id, LocalDate firstDue) {
        LoanTerms terms = new LoanTerms(new BigDecimal("12000"), new BigDecimal("0.12"), 12,
                firstDue.minusMonths(1), firstDue, RepaymentMethod.EQUAL_INSTALLMENT, BigDecimal.ZERO);
        service.openAccount(id, terms);
    }

    @Test
    void illegalTransitionRejectedWithDistinctReason() {
        String id = "risk-illegal";
        open(id, LocalDate.of(2025, 2, 1));
        service.transitionRisk(id, RiskState.NPL, LocalDate.of(2025, 6, 1), "manual-npl");
        ServicingException ex = assertThrows(ServicingException.class, () ->
                service.transitionRisk(id, RiskState.NORMAL, LocalDate.of(2025, 6, 2), "try-recover"));
        log.info("[输入] NPL->NORMAL; [最终] 拒绝原因={}", ex.getReason());
        assertEquals(RejectReason.ILLEGAL_RISK_TRANSITION, ex.getReason());
        assertEquals(RiskState.NPL, service.getAccount(id).riskState);
    }

    @Test
    void writtenOffTerminalRejectsAnyExit() {
        String id = "risk-terminal";
        open(id, LocalDate.of(2025, 2, 1));
        service.transitionRisk(id, RiskState.WRITTEN_OFF, LocalDate.of(2025, 7, 1), "wo");
        ServicingException ex = assertThrows(ServicingException.class, () ->
                service.transitionRisk(id, RiskState.NORMAL, LocalDate.of(2025, 7, 2), "back"));
        log.info("[输入] WRITTEN_OFF->NORMAL; [最终] 拒绝原因={}", ex.getReason());
        assertEquals(RejectReason.ILLEGAL_RISK_TRANSITION, ex.getReason());
    }

    @Test
    void ruleUpgradeDoesNotRewriteHistoricalJudgment() {
        // 首期到期 2025-11-01：逾期 20 天 = 2025-11-21（V1 阈值30 => WATCH）
        // 同一逾期天数放到 2026 年（V2 阈值15 => OVERDUE）
        String id = "risk-version";
        open(id, LocalDate.of(2025, 11, 1));
        RiskTransitionRecord underV1 = service.reevaluateRisk(id, LocalDate.of(2025, 11, 21));
        String firstVersion = service.getAccount(id).ruleVersion;

        // 升级生效日之后重估同日历史：历史结论仍按 V1（这里是对同一日期的判定复算）
        RiskTransitionRecord historicalAgain = service.reevaluateRisk(id, LocalDate.of(2025, 11, 21));
        log.info("[输入] 逾期20天; [判定] 2025-11-21 => {} (版本{}), 复算 => {} (版本{}); [最终] 历史判定不被升级改写",
                underV1.to, underV1.ruleVersion, historicalAgain.to, historicalAgain.ruleVersion);
        assertEquals(RiskState.WATCH, underV1.to);
        assertEquals("V1", underV1.ruleVersion);
        assertEquals(RiskState.WATCH, historicalAgain.to);
        assertEquals("V1", historicalAgain.ruleVersion);

        // 2026 年同样逾期 20 天（构造 2025-12 到期账户）触发 V2
        String id2 = "risk-version-2";
        open(id2, LocalDate.of(2025, 12, 15));
        RiskTransitionRecord underV2 = service.reevaluateRisk(id2, LocalDate.of(2026, 1, 4));
        log.info("[判定] 2026-01-04 逾期20天 => {} (版本{})", underV2.to, underV2.ruleVersion);
        assertEquals(RiskState.OVERDUE, underV2.to);
        assertEquals("V2", underV2.ruleVersion);
    }

    @Test
    void graceBoundaryRiskClassification() {
        String id = "risk-grace";
        open(id, LocalDate.of(2025, 2, 1));
        // 截止日 2/4（宽限3天）仍 NORMAL；2/5 逾期 1 天 => WATCH
        RiskTransitionRecord atDeadline = service.reevaluateRisk(id, LocalDate.of(2025, 2, 4));
        RiskTransitionRecord nextDay = service.reevaluateRisk(id, LocalDate.of(2025, 2, 5));
        log.info("[判定] 2/4=>{}({}) 2/5=>{}({})", atDeadline.to, atDeadline.ruleVersion,
                nextDay.to, nextDay.ruleVersion);
        assertEquals(RiskState.NORMAL, atDeadline.to);
        assertEquals(RiskState.WATCH, nextDay.to);
    }

    @Test
    void transitionHistoryIsVersionedAndQueryable() {
        String id = "risk-history";
        open(id, LocalDate.of(2025, 2, 1));
        service.transitionRisk(id, RiskState.WATCH, LocalDate.of(2025, 2, 5), "late");
        service.transitionRisk(id, RiskState.OVERDUE, LocalDate.of(2025, 3, 15), "worse");
        service.transitionRisk(id, RiskState.NORMAL, LocalDate.of(2025, 4, 1), "paid");
        var history = service.getRiskHistory(id);
        log.info("[最终] 风险流转历史:");
        history.forEach(h -> log.info("  {} {} -> {} version={} reason={}",
                h.effectiveDate, h.from, h.to, h.ruleVersion, h.reason));
        assertEquals(3, history.size());
        assertTrue(history.stream().allMatch(h -> h.ruleVersion != null));
        assertEquals(RiskState.WATCH, history.get(1).from);
        assertEquals(RiskState.OVERDUE, history.get(1).to);
        assertEquals(RiskState.NORMAL, history.get(2).to);
    }
}
