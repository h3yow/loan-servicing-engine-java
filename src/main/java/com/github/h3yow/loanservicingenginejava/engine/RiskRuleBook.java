package com.github.h3yow.loanservicingenginejava.engine;

import com.github.h3yow.loanservicingenginejava.domain.LoanAccount;
import com.github.h3yow.loanservicingenginejava.domain.RiskState;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 风险规则簿：分类规则带版本与生效日期。
 *
 * <p>内置版本：</p>
 * <ul>
 *   <li>V1（2024-01-01 起）：逾期 ≥1 天 WATCH，≥30 天 OVERDUE，≥90 天 NPL；</li>
 *   <li>V2（2026-01-01 起）：逾期 ≥1 天 WATCH，≥15 天 OVERDUE，≥60 天 NPL。</li>
 * </ul>
 *
 * <p>按生效日期选取版本意味着规则升级只影响升级日之后的判定，历史判定保留旧版本。</p>
 */
@Component
public class RiskRuleBook {

    private final List<RiskRuleVersion> versions = List.of(
            new RiskRuleVersion("V1", LocalDate.of(2024, 1, 1), Map.of(
                    RiskState.WATCH, 1,
                    RiskState.OVERDUE, 30,
                    RiskState.NPL, 90
            )),
            new RiskRuleVersion("V2", LocalDate.of(2026, 1, 1), Map.of(
                    RiskState.WATCH, 1,
                    RiskState.OVERDUE, 15,
                    RiskState.NPL, 60
            ))
    );

    /**
     * 返回 asOf 当日生效的规则版本。
     */
    public Optional<RiskRuleVersion> versionAt(LocalDate asOf) {
        return versions.stream()
                .filter(v -> !asOf.isBefore(v.effectiveDate))
                .max(Comparator.comparing(v -> v.effectiveDate));
    }

    /**
     * 返回指定版本号的规则（历史版本可用于回溯复算）。
     */
    public Optional<RiskRuleVersion> byVersion(String version) {
        return versions.stream().filter(v -> v.version.equals(version)).findFirst();
    }

    /**
     * 按版本与逾期天数给出分类；核销/不良等终态不被自动分类降级。
     */
    public RiskState classify(RiskRuleVersion rule, int overdueDays, LoanAccount account) {
        if (account.riskState == RiskState.WRITTEN_OFF) {
            return RiskState.WRITTEN_OFF;
        }
        if (overdueDays >= rule.minOverdueDays.get(RiskState.NPL)) {
            return RiskState.NPL;
        }
        if (overdueDays >= rule.minOverdueDays.get(RiskState.OVERDUE)) {
            return RiskState.OVERDUE;
        }
        if (overdueDays >= rule.minOverdueDays.get(RiskState.WATCH)) {
            return RiskState.WATCH;
        }
        return RiskState.NORMAL;
    }

    public String latestVersion() {
        return versions.stream()
                .max(Comparator.comparing(v -> v.effectiveDate))
                .map(v -> v.version)
                .orElseThrow();
    }
}
