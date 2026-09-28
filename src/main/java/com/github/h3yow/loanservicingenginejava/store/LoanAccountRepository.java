package com.github.h3yow.loanservicingenginejava.store;

import com.github.h3yow.loanservicingenginejava.domain.AccountStatusEvent;
import com.github.h3yow.loanservicingenginejava.domain.IdempotencyRecord;
import com.github.h3yow.loanservicingenginejava.domain.LoanAccount;
import com.github.h3yow.loanservicingenginejava.domain.PaymentRecord;
import com.github.h3yow.loanservicingenginejava.domain.ReversalRecord;
import com.github.h3yow.loanservicingenginejava.domain.RiskTransitionRecord;
import com.github.h3yow.loanservicingenginejava.domain.RejectReason;
import com.github.h3yow.loanservicingenginejava.service.ServicingException;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 本地内存账本：账户、还款流水、冲正流水、风险流转历史与幂等登记表。
 *
 * <p>并发语义：每个账户一把锁、每个幂等键一把锁；复合操作始终
 * "先取键锁、再取账户锁"（单操作内唯一的锁获取顺序），避免死锁；
 * 同一账户的还款、冲正与状态变更串行化，杜绝余额/状态不一致。</p>
 */
@Repository
public class LoanAccountRepository {

    private final Map<String, LoanAccount> accounts = new ConcurrentHashMap<>();
    private final Map<String, List<PaymentRecord>> payments = new ConcurrentHashMap<>();
    private final Map<String, List<ReversalRecord>> reversals = new ConcurrentHashMap<>();
    private final Map<String, List<RiskTransitionRecord>> riskHistory = new ConcurrentHashMap<>();
    private final Map<String, List<AccountStatusEvent>> statusHistory = new ConcurrentHashMap<>();
    private final Map<String, IdempotencyRecord> idempotency = new ConcurrentHashMap<>();
    private final Map<String, Object> accountLocks = new ConcurrentHashMap<>();
    private final Map<String, Object> keyLocks = new ConcurrentHashMap<>();

    public void saveAccount(LoanAccount account) {
        accounts.put(account.accountId, account);
        payments.putIfAbsent(account.accountId, new ArrayList<>());
        reversals.putIfAbsent(account.accountId, new ArrayList<>());
        riskHistory.putIfAbsent(account.accountId, new ArrayList<>());
        statusHistory.putIfAbsent(account.accountId, new ArrayList<>());
    }

    public LoanAccount requireAccount(String accountId) {
        LoanAccount account = accounts.get(accountId);
        if (account == null) {
            throw new ServicingException(RejectReason.ACCOUNT_NOT_FOUND,
                    "账户不存在: " + accountId);
        }
        return account;
    }

    public Optional<LoanAccount> findAccount(String accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    public List<PaymentRecord> paymentsOf(String accountId) {
        return payments.getOrDefault(accountId, List.of());
    }

    public List<ReversalRecord> reversalsOf(String accountId) {
        return reversals.getOrDefault(accountId, List.of());
    }

    public Optional<PaymentRecord> payment(String accountId, long sequence) {
        return paymentsOf(accountId).stream().filter(p -> p.sequence == sequence).findFirst();
    }

    public List<RiskTransitionRecord> riskHistoryOf(String accountId) {
        return riskHistory.getOrDefault(accountId, List.of());
    }

    public List<AccountStatusEvent> statusHistoryOf(String accountId) {
        return statusHistory.getOrDefault(accountId, List.of());
    }

    public Optional<IdempotencyRecord> idempotency(String key) {
        return Optional.ofNullable(idempotency.get(key));
    }

    /**
     * 复合操作：先键锁后账户锁，全程持有。账户不存在抛 ACCOUNT_NOT_FOUND。
     */
    public <T> T withAccountAndKey(String accountId, String idempotencyKey,
                                   Function<LoanAccount, T> action) {
        Object keyLock = keyLocks.computeIfAbsent(idempotencyKey, k -> new Object());
        synchronized (keyLock) {
            return withAccount(accountId, action);
        }
    }

    public <T> T withAccount(String accountId, Function<LoanAccount, T> action) {
        LoanAccount account = requireAccount(accountId);
        Object lock = accountLocks.computeIfAbsent(accountId, k -> new Object());
        synchronized (lock) {
            return action.apply(account);
        }
    }

    /**
     * 追加还款流水（须在账户锁内调用）。
     */
    public void addPayment(String accountId, PaymentRecord record) {
        payments.get(accountId).add(record);
    }

    /**
     * 追加冲正流水（须在账户锁内调用）。
     */
    public void addReversal(String accountId, ReversalRecord record) {
        reversals.get(accountId).add(record);
    }

    /**
     * 追加风险流转记录（须在账户锁内调用）。
     */
    public void addRiskTransition(String accountId, RiskTransitionRecord record) {
        riskHistory.get(accountId).add(record);
    }

    /**
     * 追加资金驱动的账户状态事件（须在账户锁内调用）。
     */
    public void addStatusEvent(String accountId, AccountStatusEvent event) {
        statusHistory.get(accountId).add(event);
    }

    /**
     * 登记幂等键（须在键锁内调用）。
     */
    public IdempotencyRecord putIdempotency(String key, String accountId,
                                            BigDecimal amount, long seq) {
        IdempotencyRecord record = new IdempotencyRecord(accountId, amount, seq);
        idempotency.put(key, record);
        return record;
    }

    /**
     * 登记冲正幂等键（须在键锁内调用）。
     */
    public IdempotencyRecord putReversalIdempotency(String key, String accountId,
                                                    BigDecimal amount, long reversalSeq,
                                                    long targetPaymentSeq) {
        IdempotencyRecord record = new IdempotencyRecord(accountId, amount, reversalSeq,
                "REVERSAL", targetPaymentSeq);
        idempotency.put(key, record);
        return record;
    }

    /**
     * 下一流水序号（还款/冲正共用同一账户序列，便于追溯排序）。
     *
     * <p>序号<b>按账户独立分配</b>：某账户的第 <i>n</i> 条资金事实（还款或冲正）
     * 序号即为 <i>n</i>。因此外部对"某账户第 1 笔还款"的引用（如
     * {@code /payments/1/reversals}）只取决于该账户自身事实，与其它账户的活动、
     * 用例执行顺序无关。须在账户锁内调用。</p>
     */
    public long nextSequence(String accountId) {
        return (long) payments.getOrDefault(accountId, List.of()).size()
                + reversals.getOrDefault(accountId, List.of()).size() + 1;
    }
}
