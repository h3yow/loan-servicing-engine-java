package com.github.h3yow.loanservicingenginejava.repo;

import com.github.h3yow.loanservicingenginejava.domain.LoanAccount;
import com.github.h3yow.loanservicingenginejava.error.ErrorCode;
import com.github.h3yow.loanservicingenginejava.error.LoanEngineException;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 本地内存账本：账户表 + 幂等表。
 * 每个账户一把锁，所有“读-改-写”（还款入账、风险变更、回溯插入）
 * 在同一把账户锁内完成，杜绝同一账户并发下的余额/状态不一致。
 */
@Repository
public class AccountStore {

    private final Map<String, LoanAccount> accounts = new ConcurrentHashMap<>();
    private final Map<String, IdempotencyRecord> idempotency = new ConcurrentHashMap<>();

    public LoanAccount create(String accountId) {
        LoanAccount created = new LoanAccount(accountId);
        if (accounts.putIfAbsent(accountId, created) != null) {
            throw new LoanEngineException(ErrorCode.INVALID_REQUEST,
                    "account already exists: " + accountId);
        }
        return created;
    }

    public LoanAccount require(String accountId) {
        LoanAccount account = accounts.get(accountId);
        if (account == null) {
            throw new LoanEngineException(ErrorCode.ACCOUNT_NOT_FOUND,
                    "account not found: " + accountId);
        }
        return account;
    }

    /** 在账户锁内执行原子操作并返回结果。 */
    public <T> T withAccountLock(String accountId, Function<LoanAccount, T> action) {
        LoanAccount account = require(accountId);
        synchronized (account) {
            return action.apply(account);
        }
    }

    public IdempotencyRecord getIdempotency(String key) {
        return idempotency.get(key);
    }

    /** 原子登记幂等键：返回 null 表示占位成功，否则返回既有记录。 */
    public IdempotencyRecord putIdempotencyIfAbsent(String key, IdempotencyRecord record) {
        return idempotency.putIfAbsent(key, record);
    }

    /** 业务校验失败时回滚占位，避免失败请求占用幂等键。 */
    public void releaseIdempotency(String key, IdempotencyRecord expected) {
        idempotency.remove(key, expected);
    }

    public List<String> allAccountIds() {
        return new ArrayList<>(accounts.keySet());
    }
}
