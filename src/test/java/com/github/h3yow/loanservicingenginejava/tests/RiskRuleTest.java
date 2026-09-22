package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.domain.LoanAccount;
import com.github.h3yow.loanservicingenginejava.domain.LoanTerms;
import com.github.h3yow.loanservicingenginejava.domain.RepaymentMethod;
import com.github.h3yow.loanservicingenginejava.domain.RiskState;
import com.github.h3yow.loanservicingenginejava.engine.RiskRuleBook;
import com.github.h3yow.loanservicingenginejava.engine.RiskStateMachine;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 风险规则版本与状态机：版本按生效日选取、升级不改历史判定、迁移白名单。
 */
class RiskRuleTest {

    private static final Logger log = LoggerFactory.getLogger(RiskRuleTest.class);
    private final RiskRuleBook ruleBook = new RiskRuleBook();
    private final RiskStateMachine machine = new RiskStateMachine();

    private LoanAccount account(RiskState state) {
        LoanTerms terms = new LoanTerms(new BigDecimal("1000"), new BigDecimal("0.12"), 12,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 1),
                RepaymentMethod.EQUAL_INSTALLMENT, BigDecimal.ZERO);
        LoanAccount a = new LoanAccount("a", terms);
        a.riskState = state;
        return a;
    }

    @Test
    void versionSelectedByEffectiveDate() {
        // 20 天逾期：V1（阈值30）=> WATCH；V2（阈值15，2026-01-01 生效）=> OVERDUE
        RiskState underV1 = ruleBook.classify(
                ruleBook.versionAt(LocalDate.of(2025, 12, 31)).orElseThrow(),
                20, account(RiskState.NORMAL));
        RiskState underV2 = ruleBook.classify(
                ruleBook.versionAt(LocalDate.of(2026, 1, 1)).orElseThrow(),
                20, account(RiskState.NORMAL));
        log.info("[输入] 逾期20天; [判定] 2025年适用V1=>{}; 2026年适用V2=>{}; [最终] 同一逾期天数历史结论不被改写",
                underV1, underV2);
        assertEquals(RiskState.WATCH, underV1);
        assertEquals(RiskState.OVERDUE, underV2);
    }

    @Test
    void classificationThresholdsV2() {
        var v2 = ruleBook.versionAt(LocalDate.of(2026, 6, 1)).orElseThrow();
        assertEquals(RiskState.NORMAL, ruleBook.classify(v2, 0, account(RiskState.NORMAL)));
        assertEquals(RiskState.WATCH, ruleBook.classify(v2, 1, account(RiskState.NORMAL)));
        assertEquals(RiskState.OVERDUE, ruleBook.classify(v2, 15, account(RiskState.NORMAL)));
        assertEquals(RiskState.NPL, ruleBook.classify(v2, 60, account(RiskState.NORMAL)));
        log.info("[判定] V2阈值: 0天NORMAL 1天WATCH 15天OVERDUE 60天NPL");
    }

    @Test
    void writtenOffNeverAutoDowngraded() {
        var v2 = ruleBook.versionAt(LocalDate.of(2026, 6, 1)).orElseThrow();
        assertEquals(RiskState.WRITTEN_OFF,
                ruleBook.classify(v2, 0, account(RiskState.WRITTEN_OFF)));
        log.info("[判定] 核销为终态，自动分类不得将其降级");
    }

    @Test
    void stateMachineWhitelist() {
        assertTrue(machine.canTransition(RiskState.NORMAL, RiskState.OVERDUE));
        assertTrue(machine.canTransition(RiskState.OVERDUE, RiskState.NORMAL));
        assertTrue(machine.canTransition(RiskState.NPL, RiskState.WRITTEN_OFF));
        assertFalse(machine.canTransition(RiskState.NPL, RiskState.NORMAL),
                "不良不得直接恢复正常");
        assertFalse(machine.canTransition(RiskState.WRITTEN_OFF, RiskState.NORMAL),
                "核销为终态");
        assertFalse(machine.canTransition(RiskState.NORMAL, RiskState.NORMAL),
                "同态迁移非法");
        log.info("[判定] 合法: NORMAL<->OVERDUE、NPL->核销; 非法: NPL->NORMAL、核销迁出、同态");
    }

    @Test
    void historicalVersionRemainsAvailable() {
        assertTrue(ruleBook.byVersion("V1").isPresent());
        assertTrue(ruleBook.byVersion("V2").isPresent());
        assertEquals("V2", ruleBook.latestVersion());
        log.info("[判定] 历史版本V1可用于回溯复算，最新版本={}", ruleBook.latestVersion());
    }
}
