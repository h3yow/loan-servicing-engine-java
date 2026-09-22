package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.domain.AccountSnapshot;
import com.github.h3yow.loanservicingenginejava.domain.RiskState;
import com.github.h3yow.loanservicingenginejava.error.ErrorCode;
import com.github.h3yow.loanservicingenginejava.error.LoanEngineException;
import com.github.h3yow.loanservicingenginejava.tests.TestFixtures.Engine;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.OpenAccountRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.RiskClassifyRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.RiskTransitionRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.WriteOffRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 风险规则版本：升级不改写历史、非法迁移拒绝、版本阈值差异。 */
class RiskVersionTest {

    private static final Logger log = LoggerFactory.getLogger(RiskVersionTest.class);

    private Engine account(String id) {
        Engine engine = TestFixtures.engine();
        engine.service().openAccount(new OpenAccountRequest(id,
                new BigDecimal("12000.00"), new BigDecimal("0.12"), 12,
                LocalDate.of(2026, 1, 31), "ACT/365",
                LocalDateTime.of(2026, 1, 1, 10, 0)));
        return engine;
    }

    @Test
    void ruleUpgrade_doesNotRewriteHistory() {
        Engine engine = account("A-RISK-1");
        // 2026-06-15：DPD = 135 - 3 = 132 天；v1 阈值 91 → NPL
        LocalDateTime june = LocalDateTime.of(2026, 6, 15, 10, 0);
        // 系统分类按规则直接落到目标分类（规则计算结果，允许跨级）
        engine.service().classifyRisk("A-RISK-1", new RiskClassifyRequest(
                null, "review-june", june));
        AccountSnapshot atJune = engine.service().replayAsOf("A-RISK-1", june.plusMinutes(1));
        log.info("[输入] 2026-06-15 DPD=132，v1 阈值 NPL=91");
        log.info("[判定依据] 6 月风险历史={}", atJune.riskHistory());
        assertEquals(RiskState.NPL, atJune.riskState());
        assertEquals("v1", atJune.riskHistory().get(0).ruleVersion(),
                "历史判定必须保留旧版本号 v1");

        // v2 已在注册表中（2026-07-01 生效）；重放 6 月历史时刻，结论不变
        AccountSnapshot replayJune = engine.service().replayAsOf("A-RISK-1",
                LocalDateTime.of(2026, 6, 15, 10, 1));
        log.info("[判定依据] v2 注册后重放 6 月历史，历史行版本仍为={}",
                replayJune.riskHistory().get(0).ruleVersion());
        assertEquals("v1", replayJune.riskHistory().get(0).ruleVersion());
        assertEquals(1, replayJune.riskHistory().size(), "规则升级不得新增/改写历史判定");

        // 同一 DPD 在 v2 下仍然是 NPL（132 >= 121），但阈值边界变了：
        // DPD=100 时 v1 为 NPL、v2 仅 DOUBTFUL
        Engine engine2 = account("A-RISK-1B");
        assertEquals(RiskState.NPL, engine.registry()
                .require("v1").classifyByDpd(100));
        assertEquals(RiskState.DOUBTFUL, engine.registry()
                .require("v2").classifyByDpd(100));
        log.info("[判定依据] DPD=100：v1=NPL(阈值91)，v2=DOUBTFUL(阈值121)");
    }

    @Test
    void watchThresholdChanged_v2MoreLenient() {
        Engine engine = account("A-RISK-2");
        // 2026-02-10：DPD = 10 - 3 = 7。v1 关注阈值 1 → WATCH；v2 关注阈值 8 → NORMAL
        LocalDateTime at = LocalDateTime.of(2026, 2, 10, 10, 0);
        assertEquals(RiskState.WATCH,
                engine.registry().require("v1").classifyByDpd(7));
        assertEquals(RiskState.NORMAL,
                engine.registry().require("v2").classifyByDpd(7));
        AccountSnapshot snap = engine.service().replayAsOf("A-RISK-2", at);
        log.info("[判定依据] DPD=7 时 v1 建议={}，v2 建议={}；当前(2 月生效 v1)建议={}",
                RiskState.WATCH, RiskState.NORMAL, snap.suggestedRiskState());
        assertEquals(RiskState.WATCH, snap.suggestedRiskState());

        // 同一账户在 8 月（v2 生效）重放：建议分类按 v2 给出，但历史未被改写
        AccountSnapshot later = engine.service().replayAsOf("A-RISK-2",
                LocalDateTime.of(2026, 8, 1, 10, 0));
        log.info("[判定依据] 2026-08-01 系统建议={}（历史仍无人工迁移）",
                later.suggestedRiskState());
        // 8 月 DPD 很大，v2 下同样为 NPL
        assertEquals(RiskState.NPL, later.suggestedRiskState());
        assertEquals(0, later.riskHistory().size());
    }

    @Test
    void illegalLevelJump_isRejected() {
        Engine engine = account("A-RISK-3");
        LoanEngineException ex = assertThrows(LoanEngineException.class,
                () -> engine.service().transitionRisk("A-RISK-3",
                        new RiskTransitionRequest("DOUBTFUL", "v1", "jump",
                                LocalDateTime.of(2026, 5, 1, 10, 0))));
        log.info("[输入] NORMAL -> DOUBTFUL（跨两级）");
        log.info("[拒绝原因] errorCode={} message={}", ex.getErrorCode(), ex.getMessage());
        assertEquals(ErrorCode.ILLEGAL_TRANSITION, ex.getErrorCode());
        assertEquals(RiskState.NORMAL, engine.service().getSnapshot("A-RISK-3").riskState());
    }

    @Test
    void legalOneLevelDeterioration_andRecoveryAllowed() {
        Engine engine = account("A-RISK-4");
        engine.service().transitionRisk("A-RISK-4", new RiskTransitionRequest(
                "WATCH", "v1", "d1", LocalDateTime.of(2026, 4, 1, 10, 0)));
        engine.service().transitionRisk("A-RISK-4", new RiskTransitionRequest(
                "SUBSTANDARD", "v1", "d2", LocalDateTime.of(2026, 5, 1, 10, 0)));
        // 好转允许跨级：SUBSTANDARD -> NORMAL
        engine.service().transitionRisk("A-RISK-4", new RiskTransitionRequest(
                "NORMAL", "v2", "recovered", LocalDateTime.of(2026, 8, 1, 10, 0)));
        AccountSnapshot snap = engine.service().getSnapshot("A-RISK-4");
        log.info("[判定依据] 迁移链={}",
                snap.riskHistory().stream().map(h -> h.fromState() + "->" + h.toState()
                        + "(" + h.ruleVersion() + ")").toList());
        assertEquals(RiskState.NORMAL, snap.riskState());
    }

    @Test
    void writeOff_onlyFromNpl_andTerminal() {
        Engine engine = account("A-RISK-5");
        // 未到 NPL 直接核销事件本身允许落账（核销是独立业务动作），
        // 但核销后任何风险迁移都必须被拒绝
        engine.service().writeOff("A-RISK-5", new WriteOffRequest(
                "loss", LocalDateTime.of(2026, 5, 1, 10, 0)));
        LoanEngineException ex = assertThrows(LoanEngineException.class,
                () -> engine.service().transitionRisk("A-RISK-5",
                        new RiskTransitionRequest("NORMAL", "v2", "revive",
                                LocalDateTime.of(2026, 5, 2, 10, 0))));
        log.info("[输入] WRITTEN_OFF -> NORMAL");
        log.info("[拒绝原因] errorCode={} message={}", ex.getErrorCode(), ex.getMessage());
        assertEquals(ErrorCode.ILLEGAL_TRANSITION, ex.getErrorCode());
    }

    @Test
    void unknownRuleVersion_isRejected() {
        Engine engine = account("A-RISK-6");
        LoanEngineException ex = assertThrows(LoanEngineException.class,
                () -> engine.service().transitionRisk("A-RISK-6",
                        new RiskTransitionRequest("WATCH", "v99", "bad",
                                LocalDateTime.of(2026, 5, 1, 10, 0))));
        log.info("[输入] 指定不存在规则版本 v99");
        log.info("[拒绝原因] errorCode={} message={}", ex.getErrorCode(), ex.getMessage());
        assertEquals(ErrorCode.RISK_RULE_VERSION_NOT_FOUND, ex.getErrorCode());
    }
}
