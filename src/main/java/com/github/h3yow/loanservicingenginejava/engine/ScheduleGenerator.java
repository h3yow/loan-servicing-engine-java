package com.github.h3yow.loanservicingenginejava.engine;

import com.github.h3yow.loanservicingenginejava.domain.Installment;
import com.github.h3yow.loanservicingenginejava.domain.LoanTerms;
import com.github.h3yow.loanservicingenginejava.domain.RepaymentMethod;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 还款计划生成：支持等额本息与等额本金。
 *
 * <p>口径说明：</p>
 * <ul>
 *   <li>期利率 = 年利率 / 12（按月还款）；</li>
 *   <li>等额本息每期还款额 = P·r·(1+r)^n / ((1+r)^n − 1)；</li>
 *   <li>等额本金每期本金 = P/n，当期利息 = 期初剩余本金 × 期利率；</li>
 *   <li>feeRate 为按本金一次性费率，均摊到各期作为计划费用；</li>
 *   <li>金额按 2 位小数四舍五入，末期吸收尾差，保证计划本金之和恒等于放款本金。</li>
 * </ul>
 */
@Component
public class ScheduleGenerator {

    private static final int MONEY_SCALE = 2;
    private static final int CALC_SCALE = 10;

    public List<Installment> generate(LoanTerms terms) {
        return switch (terms.method) {
            case EQUAL_INSTALLMENT -> equalInstallment(terms);
            case EQUAL_PRINCIPAL -> equalPrincipal(terms);
        };
    }

    private List<Installment> equalInstallment(LoanTerms terms) {
        int n = terms.installmentCount;
        BigDecimal r = terms.annualRate.divide(BigDecimal.valueOf(12), CALC_SCALE, RoundingMode.HALF_UP);
        BigDecimal feeEach = terms.principal.multiply(terms.feeRate)
                .divide(BigDecimal.valueOf(n), MONEY_SCALE, RoundingMode.HALF_UP);

        BigDecimal annuity;
        if (r.signum() == 0) {
            annuity = terms.principal.divide(BigDecimal.valueOf(n), CALC_SCALE, RoundingMode.HALF_UP);
        } else {
            BigDecimal pow = BigDecimal.ONE.add(r).pow(n);
            annuity = terms.principal.multiply(r).multiply(pow)
                    .divide(pow.subtract(BigDecimal.ONE), CALC_SCALE, RoundingMode.HALF_UP);
        }

        List<Installment> result = new ArrayList<>();
        BigDecimal remainingPrincipal = terms.principal;
        BigDecimal principalScheduledTotal = BigDecimal.ZERO;
        for (int i = 1; i <= n; i++) {
            LocalDate dueDate = terms.firstDueDate.plusMonths(i - 1L);
            BigDecimal interest = remainingPrincipal.multiply(r)
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal principal;
            BigDecimal fee;
            if (i < n) {
                principal = annuity.subtract(interest).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                fee = feeEach;
            } else {
                // 末期吸收本金与费用尾差
                principal = remainingPrincipal;
                fee = terms.principal.multiply(terms.feeRate)
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                        .subtract(feeEach.multiply(BigDecimal.valueOf(n - 1L)));
            }
            result.add(new Installment(i, dueDate, principal, interest, fee));
            remainingPrincipal = remainingPrincipal.subtract(principal);
            principalScheduledTotal = principalScheduledTotal.add(principal);
        }
        if (principalScheduledTotal.compareTo(terms.principal) != 0) {
            throw new IllegalStateException("计划本金之和(" + principalScheduledTotal
                    + ")不等于放款本金(" + terms.principal + ")");
        }
        return result;
    }

    private List<Installment> equalPrincipal(LoanTerms terms) {
        int n = terms.installmentCount;
        BigDecimal r = terms.annualRate.divide(BigDecimal.valueOf(12), CALC_SCALE, RoundingMode.HALF_UP);
        BigDecimal principalEach = terms.principal.divide(BigDecimal.valueOf(n),
                MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal feeEach = terms.principal.multiply(terms.feeRate)
                .divide(BigDecimal.valueOf(n), MONEY_SCALE, RoundingMode.HALF_UP);

        List<Installment> result = new ArrayList<>();
        BigDecimal remainingPrincipal = terms.principal;
        for (int i = 1; i <= n; i++) {
            LocalDate dueDate = terms.firstDueDate.plusMonths(i - 1L);
            BigDecimal interest = remainingPrincipal.multiply(r)
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal principal;
            BigDecimal fee;
            if (i < n) {
                principal = principalEach;
                fee = feeEach;
            } else {
                principal = remainingPrincipal;
                fee = terms.principal.multiply(terms.feeRate)
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                        .subtract(feeEach.multiply(BigDecimal.valueOf(n - 1L)));
            }
            result.add(new Installment(i, dueDate, principal, interest, fee));
            remainingPrincipal = remainingPrincipal.subtract(principal);
        }
        return result;
    }
}
