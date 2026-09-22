package com.github.h3yow.loanservicingenginejava.engine;

import com.github.h3yow.loanservicingenginejava.domain.Installment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 逾期判定：宽限期与业务日规则可配置。
 *
 * <p>口径：某期到期日 D、宽限期 G 天，则最后宽限截止日为 D+G；
 * 在截止日当天仍不算逾期，次日起算逾期，逾期天数 = asOf − (D+G)。
 * 若开启工作日顺延且截止日落周六/周日，则顺延至下一周一后再判定。
 * 仅当该期尚欠计划本金/利息/费用（不含罚息）时才计逾期。</p>
 */
@Component
public class OverdueEvaluator {

    /**
     * 返回最严重逾期期数的逾期天数（已过宽限期）；无逾期返回 0。
     */
    public int maxOverdueDays(List<Installment> installments, LocalDate asOf,
                              int graceDays, boolean extendToBusinessDay) {
        int max = 0;
        for (Installment installment : installments) {
            int days = overdueDays(installment, asOf, graceDays, extendToBusinessDay);
            if (days > max) {
                max = days;
            }
        }
        return max;
    }

    public boolean isOverdue(Installment installment, LocalDate asOf, int graceDays) {
        return overdueDays(installment, asOf, graceDays, false) > 0;
    }

    public int overdueDays(Installment installment, LocalDate asOf, int graceDays,
                           boolean extendToBusinessDay) {
        if (!hasUnpaidScheduled(installment)) {
            return 0;
        }
        LocalDate deadline = installment.dueDate.plusDays(graceDays);
        if (extendToBusinessDay) {
            deadline = extendToNextBusinessDay(deadline);
        }
        long days = ChronoUnit.DAYS.between(deadline, asOf);
        return days > 0 ? (int) days : 0;
    }

    /**
     * 某期计划欠款（本金+利息+费用，不含罚息）是否大于 0。
     */
    public boolean hasUnpaidScheduled(Installment i) {
        return i.scheduledPrincipal.subtract(i.paidPrincipal)
                .add(i.scheduledInterest.subtract(i.paidInterest))
                .add(i.scheduledFee.subtract(i.paidFee))
                .compareTo(BigDecimal.ZERO) > 0;
    }

    private LocalDate extendToNextBusinessDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY) {
            return date.plusDays(2);
        }
        if (dow == DayOfWeek.SUNDAY) {
            return date.plusDays(1);
        }
        return date;
    }
}
