package com.github.h3yow.loanservicingenginejava.risk;

import com.github.h3yow.loanservicingenginejava.domain.RiskState;

/**
 * 风险状态迁移矩阵（严重度：NORMAL(0) &lt; WATCH(1) &lt; SUBSTANDARD(2)
 * &lt; DOUBTFUL(3) &lt; NPL(4) &lt; WRITTEN_OFF(5)）。
 * <ul>
 *   <li>恶化（下调分类）只允许逐级，不允许跨级跳变；</li>
 *   <li>好转（上调分类）允许跨级恢复；</li>
 *   <li>核销只能从 NPL 进入；一旦 WRITTEN_OFF 不可再迁移；</li>
 *   <li>结清时由系统将分类置为 NORMAL（结清事件，不经过人工矩阵）。</li>
 * </ul>
 */
public final class RiskTransitions {

    private RiskTransitions() {
    }

    public static boolean isLegal(RiskState from, RiskState to) {
        if (from == to) {
            return false;
        }
        if (from == RiskState.WRITTEN_OFF) {
            return false;
        }
        if (to == RiskState.WRITTEN_OFF) {
            return from == RiskState.NPL;
        }
        int f = severity(from);
        int t = severity(to);
        if (t > f) {
            // 恶化只允许逐级
            return t - f == 1;
        }
        // 好转允许跨级
        return true;
    }

    public static int severity(RiskState state) {
        return switch (state) {
            case NORMAL -> 0;
            case WATCH -> 1;
            case SUBSTANDARD -> 2;
            case DOUBTFUL -> 3;
            case NPL -> 4;
            case WRITTEN_OFF -> 5;
        };
    }
}
