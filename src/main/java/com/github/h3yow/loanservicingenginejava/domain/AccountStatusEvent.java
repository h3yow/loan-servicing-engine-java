package com.github.h3yow.loanservicingenginejava.domain;

import java.time.LocalDate;

/**
 * 资金事实驱动的账户状态变更事件（不可变）。
 *
 * <p>仅记录 {@code ACTIVE} 与 {@code SETTLED} 之间随资金入账/冲正发生的迁移；
 * {@code CLOSED} 是无日期的关账终态标记，不进入此时间线。</p>
 *
 * <p>用途：历史时点视图（asOf 早于某些已登记冲正的生效日）需要重建当时状态。
 * 仅靠"时点剩余应还"无法区分"还款结清后又被生效日更晚的冲正退回"的情形，
 * 因此结清/退回的事实必须像风险迁移历史一样按生效日留痕。</p>
 */
public final class AccountStatusEvent {
    public final LocalDate effectiveDate;
    public final AccountStatus from;
    public final AccountStatus to;
    public final String reason;

    public AccountStatusEvent(LocalDate effectiveDate, AccountStatus from, AccountStatus to,
                              String reason) {
        this.effectiveDate = effectiveDate;
        this.from = from;
        this.to = to;
        this.reason = reason;
    }

    public AccountStatusEvent copy() {
        return new AccountStatusEvent(effectiveDate, from, to, reason);
    }
}
