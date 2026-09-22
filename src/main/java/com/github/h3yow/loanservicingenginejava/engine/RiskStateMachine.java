package com.github.h3yow.loanservicingenginejava.engine;

import com.github.h3yow.loanservicingenginejava.domain.RiskState;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 风险状态迁移白名单。
 *
 * <p>合法迁移：</p>
 * <ul>
 *   <li>NORMAL/WATCH/OVERDUE 之间可双向流转（还款后可恢复），也可恶化至 NPL、核销；</li>
 *   <li>NPL 只能核销（WRITTEN_OFF），不可自动恢复；</li>
 *   <li>WRITTEN_OFF 为终态，任何迁出均非法。</li>
 * </ul>
 */
@Component
public class RiskStateMachine {

    private static final Map<RiskState, Set<RiskState>> ALLOWED = Map.of(
            RiskState.NORMAL, Set.of(RiskState.WATCH, RiskState.OVERDUE, RiskState.NPL, RiskState.WRITTEN_OFF),
            RiskState.WATCH, Set.of(RiskState.NORMAL, RiskState.OVERDUE, RiskState.NPL, RiskState.WRITTEN_OFF),
            RiskState.OVERDUE, Set.of(RiskState.NORMAL, RiskState.WATCH, RiskState.NPL, RiskState.WRITTEN_OFF),
            RiskState.NPL, Set.of(RiskState.WRITTEN_OFF),
            RiskState.WRITTEN_OFF, Set.of()
    );

    public boolean canTransition(RiskState from, RiskState to) {
        return from != to && ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }
}
