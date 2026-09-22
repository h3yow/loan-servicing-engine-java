package com.github.h3yow.loanservicingenginejava.risk;

import com.github.h3yow.loanservicingenginejava.error.ErrorCode;
import com.github.h3yow.loanservicingenginejava.error.LoanEngineException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 规则版本注册表：内存实现。
 * 同一时刻按生效时间解析唯一有效版本；历史重放时按事件时间解析，
 * 因此规则升级不会改写历史判定。
 * <p>
 * 内置两版（阈值单位为“超过宽限期后的逾期天数 DPD”）：
 * <ul>
 *   <li>v1（2026-01-01 起）：关注 1 / 次级 31 / 可疑 61 / 不良 91；</li>
 *   <li>v2（2026-07-01 起）：关注 8 / 次级 31 / 可疑 91 / 不良 121
 *       ——升级仅影响升级生效之后的判定。</li>
 * </ul>
 */
@Component
public class RiskRuleRegistry {

    private final Map<String, RiskRuleBook> byVersion = new ConcurrentHashMap<>();
    private final List<RiskRuleBook> books = new CopyOnWriteArrayList<>();

    public RiskRuleRegistry() {
        register(new RiskRuleBook("v1",
                LocalDateTime.of(2026, 1, 1, 0, 0), 1, 31, 61, 91));
        register(new RiskRuleBook("v2",
                LocalDateTime.of(2026, 7, 1, 0, 0), 8, 31, 91, 121));
    }

    public void register(RiskRuleBook book) {
        if (byVersion.putIfAbsent(book.version(), book) != null) {
            throw new IllegalStateException("rule version already registered: " + book.version());
        }
        books.add(book);
        books.sort(Comparator.comparing(RiskRuleBook::effectiveFrom));
    }

    public RiskRuleBook require(String version) {
        RiskRuleBook book = byVersion.get(version);
        if (book == null) {
            throw new LoanEngineException(ErrorCode.RISK_RULE_VERSION_NOT_FOUND,
                    "unknown risk rule version: " + version);
        }
        return book;
    }

    /** 解析 at 时刻已生效的最新版本；早于任何版本时回退到最早版本，保证可判定。 */
    public RiskRuleBook effectiveAt(LocalDateTime at) {
        RiskRuleBook resolved = null;
        for (RiskRuleBook book : books) {
            if (!at.isBefore(book.effectiveFrom())) {
                resolved = book;
            }
        }
        if (resolved == null) {
            resolved = books.get(0);
        }
        return resolved;
    }

    public List<RiskRuleBook> all() {
        return List.copyOf(books);
    }
}
