package com.github.h3yow.loanservicingenginejava.engine;

import com.github.h3yow.loanservicingenginejava.config.EngineProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 罚息计算（按日计息，实际天数 ACT）：
 * <ul>
 *   <li>起罚日 = 到期日 + 宽限期 + 1；起罚日当天的还款不计罚息，
 *       即计息区间为 [起罚日, 还款生效日/评估日)；</li>
 *   <li>日利率 = 罚息年利率 / 计息分母（ACT/365 → 365，ACT/360 → 360）；</li>
 *   <li>每段罚息 = 逾期基数 × 日利率 × 天数，四舍五入到分；</li>
 *   <li>逾期基数（DUE 口径）= 该期未付的应还本金 + 应还利息；
 *       OUTSTANDING 口径 = 账户全部剩余本金（账户存在逾期期时计提）。</li>
 * </ul>
 * 投影时按时间轴行走，部分还款只改变还款时点之后的基数，
 * 回溯/补录后整段重算，因此不会重复累计。
 */
@Component
public class PenaltyCalculator {

    private final EngineProperties properties;

    public PenaltyCalculator(EngineProperties properties) {
        this.properties = properties;
    }

    public LocalDate penaltyStartDate(LocalDate dueDate) {
        return dueDate.plusDays((long) properties.getGraceDays() + 1);
    }

    public int dayCountDivisor() {
        return "ACT/360".equalsIgnoreCase(properties.getDayCountBasis()) ? 360 : 365;
    }

    public BigDecimal dailyRate() {
        return properties.getPenaltyAnnualRate()
                .divide(BigDecimal.valueOf(dayCountDivisor()), 10, RoundingMode.HALF_UP);
    }

    public int accrualDays(LocalDate fromInclusive, LocalDate toExclusive) {
        int days = (int) ChronoUnit.DAYS.between(fromInclusive, toExclusive);
        return Math.max(days, 0);
    }

    /** 单段罚息：[fromInclusive, toExclusive) 内按固定基数计提。 */
    public BigDecimal segmentPenalty(BigDecimal overdueBase, int days) {
        if (days <= 0 || overdueBase.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return overdueBase.multiply(dailyRate())
                .multiply(BigDecimal.valueOf(days))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
