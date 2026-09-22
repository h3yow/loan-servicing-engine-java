package com.github.h3yow.loanservicingenginejava.risk;

import com.github.h3yow.loanservicingenginejava.domain.RiskState;

import java.time.LocalDateTime;

/**
 * 风险分类规则的一个版本。
 * 用逾期天数（DPD）阈值把账户分类到风险状态；版本一旦发布即不可变，
 * 升级通过注册新版本完成，历史判定保留当时版本号。
 *
 * @param version        版本号，如 v1 / v2
 * @param effectiveFrom  生效时间（含）
 * @param watchDpd       达到该 DPD 进入关注
 * @param substandardDpd 进入次级
 * @param doubtfulDpd    进入可疑
 * @param nplDpd         进入不良
 */
public record RiskRuleBook(String version,
                           LocalDateTime effectiveFrom,
                           int watchDpd,
                           int substandardDpd,
                           int doubtfulDpd,
                           int nplDpd) {

    public RiskRuleBook {
        if (watchDpd <= 0 || substandardDpd < watchDpd
                || doubtfulDpd < substandardDpd || nplDpd < doubtfulDpd) {
            throw new IllegalArgumentException("invalid risk thresholds for version " + version);
        }
    }

    /** 按逾期天数分类（已对宽限期后的 DPD 调用）。 */
    public RiskState classifyByDpd(int dpd) {
        if (dpd >= nplDpd) {
            return RiskState.NPL;
        }
        if (dpd >= doubtfulDpd) {
            return RiskState.DOUBTFUL;
        }
        if (dpd >= substandardDpd) {
            return RiskState.SUBSTANDARD;
        }
        if (dpd >= watchDpd) {
            return RiskState.WATCH;
        }
        return RiskState.NORMAL;
    }
}
