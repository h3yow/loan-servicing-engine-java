package com.github.h3yow.loanservicingenginejava.engine;

import com.github.h3yow.loanservicingenginejava.domain.Installment;
import com.github.h3yow.loanservicingenginejava.domain.Terms;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 还款计划生成：等本按月（每期应还本金均摊），
 * 每期应还利息 = 期初剩余本金 × 月利率（年利率/12，内部保留 10 位小数）。
 * 本金分摊四舍五入到分、尾差并入最后一期；利息同样按分四舍五入、
 * 尾差并入最后一期，保证各期本金之和恒等于合同本金、
 * 各期利息之和恒等于按合同口径计算的总利息。
 */
@Component
public class ScheduleGenerator {

    private static final BigDecimal MONTHS = BigDecimal.valueOf(12);

    public List<Installment> generate(Terms terms, LocalDate firstDueDate) {
        if (terms.termMonths() <= 0) {
            throw new IllegalArgumentException("termMonths must be positive");
        }
        int n = terms.termMonths();
        BigDecimal principal = terms.principal().setScale(2, RoundingMode.HALF_UP);
        BigDecimal monthlyRate = terms.annualRate()
                .divide(MONTHS, 10, RoundingMode.HALF_UP);
        BigDecimal perPrincipal = principal.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);

        // 总利息（分）：逐期用未舍入基数算到分再求和，作为利息守恒基准
        BigDecimal exactTotalInterest = BigDecimal.ZERO;
        List<BigDecimal> rawInterest = new ArrayList<>();
        BigDecimal allocatedPrincipalTmp = BigDecimal.ZERO;
        for (int k = 1; k <= n; k++) {
            BigDecimal sPrincipal = (k == n)
                    ? principal.subtract(allocatedPrincipalTmp)
                    : perPrincipal;
            BigDecimal remainingBefore = principal.subtract(allocatedPrincipalTmp);
            BigDecimal sInterest = remainingBefore.multiply(monthlyRate)
                    .setScale(2, RoundingMode.HALF_UP);
            rawInterest.add(sInterest);
            exactTotalInterest = exactTotalInterest.add(sInterest);
            allocatedPrincipalTmp = allocatedPrincipalTmp.add(sPrincipal);
        }

        List<Installment> result = new ArrayList<>();
        BigDecimal allocatedPrincipal = BigDecimal.ZERO;
        BigDecimal allocatedInterest = BigDecimal.ZERO;
        LocalDate dueDate = firstDueDate;
        for (int k = 1; k <= n; k++) {
            BigDecimal sPrincipal = (k == n)
                    ? principal.subtract(allocatedPrincipal)
                    : perPrincipal;
            BigDecimal sInterest = (k == n)
                    ? exactTotalInterest.subtract(allocatedInterest)
                    : rawInterest.get(k - 1);
            result.add(new Installment(k, dueDate, sPrincipal, sInterest,
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    false));
            allocatedPrincipal = allocatedPrincipal.add(sPrincipal);
            allocatedInterest = allocatedInterest.add(sInterest);
            dueDate = dueDate.plusMonths(1);
        }
        return List.copyOf(result);
    }
}
