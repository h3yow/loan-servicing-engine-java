package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.config.EngineProperties;
import com.github.h3yow.loanservicingenginejava.engine.AccountProjector;
import com.github.h3yow.loanservicingenginejava.engine.BusinessCalendar;
import com.github.h3yow.loanservicingenginejava.engine.PenaltyCalculator;
import com.github.h3yow.loanservicingenginejava.engine.ScheduleGenerator;
import com.github.h3yow.loanservicingenginejava.repo.AccountStore;
import com.github.h3yow.loanservicingenginejava.risk.RiskRuleRegistry;
import com.github.h3yow.loanservicingenginejava.service.LoanService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试装配：手工组装引擎（无外部依赖），时钟固定在 2026-09-22，
 * 使所有结论可复现。
 */
final class TestFixtures {

    static final ZoneId ZONE = ZoneOffset.UTC;
    static final String FIXED_NOW = "2026-09-22T10:00:00Z";

    private TestFixtures() {
    }

    static EngineProperties properties() {
        EngineProperties p = new EngineProperties();
        p.setGraceDays(3);
        p.setDayCountBasis("ACT/365");
        p.setPenaltyAnnualRate(new BigDecimal("0.18"));
        p.setBusinessCutoff("00:00");
        p.setPenaltyBasis("DUE");
        return p;
    }

    static Engine engine() {
        return engine(properties());
    }

    static Engine engine(EngineProperties props) {
        Clock clock = Clock.fixed(Instant.parse(FIXED_NOW), ZONE);
        AccountStore store = new AccountStore();
        RiskRuleRegistry registry = new RiskRuleRegistry();
        ScheduleGenerator scheduleGenerator = new ScheduleGenerator();
        PenaltyCalculator penaltyCalculator = new PenaltyCalculator(props);
        BusinessCalendar calendar = new BusinessCalendar(props);
        AccountProjector projector = new AccountProjector(scheduleGenerator,
                penaltyCalculator, registry, props);
        LoanService service = new LoanService(store, projector, calendar,
                registry, props, clock);
        return new Engine(store, registry, projector, service, props, clock);
    }

    record Engine(AccountStore store,
                  RiskRuleRegistry registry,
                  AccountProjector projector,
                  LoanService service,
                  EngineProperties props,
                  Clock clock) {
    }
}
