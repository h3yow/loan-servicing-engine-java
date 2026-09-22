package com.github.h3yow.loanservicingenginejava.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地账本账户：聚合根。
 * 状态不做就地修改，而是保存仅追加事件，每次查询/入账后由重放投影得到；
 * 账户级锁由存储层负责。
 */
public class LoanAccount {

    private final String accountId;
    private final List<AccountEvent> events = new ArrayList<>();
    private long lastSeq;

    public LoanAccount(String accountId) {
        this.accountId = accountId;
    }

    public String getAccountId() {
        return accountId;
    }

    public List<AccountEvent> events() {
        return List.copyOf(events);
    }

    /** 顺序追加事件，赋予单调递增序号。 */
    public void append(AccountEvent event) {
        if (event.seq() != nextSeq()) {
            throw new IllegalArgumentException("event seq mismatch, expected " + nextSeq());
        }
        events.add(event);
        lastSeq = event.seq();
    }

    /** 回溯入账：事件按生效时间参与重放，序号保持插入顺序用于稳定排序。 */
    public void insert(AccountEvent event) {
        if (event.seq() != nextSeq()) {
            throw new IllegalArgumentException("event seq mismatch, expected " + nextSeq());
        }
        events.add(event);
        lastSeq = event.seq();
    }

    public long nextSeq() {
        return lastSeq + 1;
    }
}
