package com.github.h3yow.loanservicingenginejava.config;

import com.github.h3yow.loanservicingenginejava.domain.DayCountConvention;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * 可配置项：宽限期、日计息口径、业务日切、罚息利率等。
 */
@ConfigurationProperties(prefix = "loan.servicing")
public class ServicingProperties {

    private int graceDays = 3;
    private DayCountConvention dayCount = DayCountConvention.ACT_365_FIXED;
    private int cutoffHour = 0;
    private BigDecimal annualPenaltyRate = new BigDecimal("0.18");
    private boolean extendOverdueToBusinessDay = false;

    public int getGraceDays() {
        return graceDays;
    }

    public void setGraceDays(int graceDays) {
        this.graceDays = graceDays;
    }

    public DayCountConvention getDayCount() {
        return dayCount;
    }

    public void setDayCount(DayCountConvention dayCount) {
        this.dayCount = dayCount;
    }

    public int getCutoffHour() {
        return cutoffHour;
    }

    public void setCutoffHour(int cutoffHour) {
        this.cutoffHour = cutoffHour;
    }

    public BigDecimal getAnnualPenaltyRate() {
        return annualPenaltyRate;
    }

    public void setAnnualPenaltyRate(BigDecimal annualPenaltyRate) {
        this.annualPenaltyRate = annualPenaltyRate;
    }

    public boolean isExtendOverdueToBusinessDay() {
        return extendOverdueToBusinessDay;
    }

    public void setExtendOverdueToBusinessDay(boolean extendOverdueToBusinessDay) {
        this.extendOverdueToBusinessDay = extendOverdueToBusinessDay;
    }
}
