package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.domain.Installment;
import com.github.h3yow.loanservicingenginejava.domain.LoanTerms;
import com.github.h3yow.loanservicingenginejava.domain.RepaymentMethod;
import com.github.h3yow.loanservicingenginejava.engine.ScheduleGenerator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划生成：本金守恒、到期日按月推进、费用均摊（末期吸收尾差）。
 */
class ScheduleGeneratorTest {

    private static final Logger log = LoggerFactory.getLogger(ScheduleGeneratorTest.class);
    private final ScheduleGenerator generator = new ScheduleGenerator();

    private LoanTerms terms(BigDecimal principal, BigDecimal rate, int n,
                            RepaymentMethod method, BigDecimal feeRate) {
        return new LoanTerms(principal, rate, n,
                LocalDate.of(2025, 1, 10), LocalDate.of(2025, 2, 10), method, feeRate);
    }

    @Test
    void equalInstallmentPrincipalConservation() {
        LoanTerms t = terms(new BigDecimal("12000"), new BigDecimal("0.12"), 12,
                RepaymentMethod.EQUAL_INSTALLMENT, BigDecimal.ZERO);
        List<Installment> schedule = generator.generate(t);
        BigDecimal principalSum = schedule.stream().map(i -> i.scheduledPrincipal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("[输入] 等额本息 本金12000 年利率0.12 12期; [判定] 计划本金合计={}; [最终] 期数={}",
                principalSum, schedule.size());
        schedule.forEach(i -> log.info("  期{} 到期{} 本金={} 利息={}", i.seq, i.dueDate,
                i.scheduledPrincipal, i.scheduledInterest));
        assertEquals(12, schedule.size());
        assertEquals(0, new BigDecimal("12000.00").compareTo(principalSum));
        assertEquals(LocalDate.of(2026, 1, 10), schedule.get(11).dueDate);
        // 每期还款额（本金+利息）相等
        BigDecimal firstPay = schedule.get(0).scheduledPrincipal.add(schedule.get(0).scheduledInterest);
        for (int k = 1; k < 11; k++) {
            BigDecimal pay = schedule.get(k).scheduledPrincipal.add(schedule.get(k).scheduledInterest);
            assertEquals(0, firstPay.compareTo(pay), "第" + (k + 1) + "期还款额应相等");
        }
    }

    @Test
    void equalPrincipalEachPrincipalEqual() {
        LoanTerms t = terms(new BigDecimal("10000"), new BigDecimal("0.24"), 10,
                RepaymentMethod.EQUAL_PRINCIPAL, BigDecimal.ZERO);
        List<Installment> schedule = generator.generate(t);
        BigDecimal principalSum = schedule.stream().map(i -> i.scheduledPrincipal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("[输入] 等额本金 本金10000 10期; [判定] 每期本金={} 合计={}; [最终] 利息逐期递减",
                schedule.get(0).scheduledPrincipal, principalSum);
        assertEquals(0, new BigDecimal("10000.00").compareTo(principalSum));
        for (int k = 0; k < 9; k++) {
            assertEquals(0, new BigDecimal("1000.00").compareTo(schedule.get(k).scheduledPrincipal));
            assertTrue(schedule.get(k).scheduledInterest.compareTo(schedule.get(k + 1).scheduledInterest) >= 0);
        }
    }

    @Test
    void feeSpreadWithLastInstallmentTail() {
        LoanTerms t = terms(new BigDecimal("10000"), new BigDecimal("0.12"), 3,
                RepaymentMethod.EQUAL_INSTALLMENT, new BigDecimal("0.03"));
        List<Installment> schedule = generator.generate(t);
        BigDecimal feeSum = schedule.stream().map(i -> i.scheduledFee)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("[输入] 本金10000 费率0.03 3期; [判定] 费用合计={} 期望300.00", feeSum);
        assertEquals(0, new BigDecimal("300.00").compareTo(feeSum));
    }

    @Test
    void zeroRateEqualInstallment() {
        LoanTerms t = terms(new BigDecimal("3000"), BigDecimal.ZERO, 3,
                RepaymentMethod.EQUAL_INSTALLMENT, BigDecimal.ZERO);
        List<Installment> schedule = generator.generate(t);
        BigDecimal principalSum = schedule.stream().map(i -> i.scheduledPrincipal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("[输入] 零利率 本金3000 3期; [最终] 计划本金合计={} 全部利息为0", principalSum);
        assertEquals(0, new BigDecimal("3000.00").compareTo(principalSum));
        schedule.forEach(i -> assertEquals(0, BigDecimal.ZERO.compareTo(i.scheduledInterest)));
    }
}
