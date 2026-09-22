package com.github.h3yow.loanservicingenginejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 引擎可配置项（application.properties 前缀 loan.engine）。
 */
@Component
@ConfigurationProperties(prefix = "loan.engine")
public class EngineProperties {

    /** 宽限期天数：到期日 + graceDays（含）之内还款不计逾期/罚息。 */
    private int graceDays = 3;

    /** 日计息口径：ACT/365 或 ACT/360。 */
    private String dayCountBasis = "ACT/365";

    /** 罚息年化利率。 */
    private java.math.BigDecimal penaltyAnnualRate = new java.math.BigDecimal("0.18");

    /** 业务日切时间（HH:mm），晚于该时刻的提交计入下一业务日。 */
    private String businessCutoff = "00:00";

    /** 罚息计提口径：DUE（仅对逾期未还金额）/OUTSTANDING（对全部剩余本金）。 */
    private String penaltyBasis = "DUE";

    public int getGraceDays() {
        return graceDays;
    }

    public void setGraceDays(int graceDays) {
        this.graceDays = graceDays;
    }

    public String getDayCountBasis() {
        return dayCountBasis;
    }

    public void setDayCountBasis(String dayCountBasis) {
        this.dayCountBasis = dayCountBasis;
    }

    public java.math.BigDecimal getPenaltyAnnualRate() {
        return penaltyAnnualRate;
    }

    public void setPenaltyAnnualRate(java.math.BigDecimal penaltyAnnualRate) {
        this.penaltyAnnualRate = penaltyAnnualRate;
    }

    public String getBusinessCutoff() {
        return businessCutoff;
    }

    public void setBusinessCutoff(String businessCutoff) {
        this.businessCutoff = businessCutoff;
    }

    public String getPenaltyBasis() {
        return penaltyBasis;
    }

    public void setPenaltyBasis(String penaltyBasis) {
        this.penaltyBasis = penaltyBasis;
    }
}
