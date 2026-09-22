package com.github.h3yow.loanservicingenginejava.service;

import com.github.h3yow.loanservicingenginejava.config.EngineProperties;
import com.github.h3yow.loanservicingenginejava.domain.AccountEvent;
import com.github.h3yow.loanservicingenginejava.domain.AccountEvent.FeeCharged;
import com.github.h3yow.loanservicingenginejava.domain.AccountEvent.Opened;
import com.github.h3yow.loanservicingenginejava.domain.AccountEvent.PaymentReceived;
import com.github.h3yow.loanservicingenginejava.domain.AccountEvent.RiskTransition;
import com.github.h3yow.loanservicingenginejava.domain.AccountEvent.WrittenOff;
import com.github.h3yow.loanservicingenginejava.domain.AccountSnapshot;
import com.github.h3yow.loanservicingenginejava.domain.AccountStatus;
import com.github.h3yow.loanservicingenginejava.domain.LoanAccount;
import com.github.h3yow.loanservicingenginejava.domain.PaymentReceipt;
import com.github.h3yow.loanservicingenginejava.domain.RiskState;
import com.github.h3yow.loanservicingenginejava.domain.Terms;
import com.github.h3yow.loanservicingenginejava.engine.AccountProjector;
import com.github.h3yow.loanservicingenginejava.engine.BusinessCalendar;
import com.github.h3yow.loanservicingenginejava.error.ErrorCode;
import com.github.h3yow.loanservicingenginejava.error.LoanEngineException;
import com.github.h3yow.loanservicingenginejava.repo.AccountStore;
import com.github.h3yow.loanservicingenginejava.repo.IdempotencyRecord;
import com.github.h3yow.loanservicingenginejava.risk.RiskRuleBook;
import com.github.h3yow.loanservicingenginejava.risk.RiskRuleRegistry;
import com.github.h3yow.loanservicingenginejava.risk.RiskTransitions;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.FeeRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.OpenAccountRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.PaymentRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.RiskClassifyRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.RiskTransitionRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.WriteOffRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 贷款服务应用层：开户、还款入账、费用计收、风险迁移与查询。
 * 所有写操作都在账户锁内完成；入账结果完全由事件重放得到，保证可复现。
 */
@Service
public class LoanService {

    private static final Logger log = LoggerFactory.getLogger(LoanService.class);

    private final AccountStore store;
    private final AccountProjector projector;
    private final BusinessCalendar businessCalendar;
    private final RiskRuleRegistry riskRuleRegistry;
    private final EngineProperties properties;
    private final Clock clock;

    public LoanService(AccountStore store,
                       AccountProjector projector,
                       BusinessCalendar businessCalendar,
                       RiskRuleRegistry riskRuleRegistry,
                       EngineProperties properties,
                       Clock clock) {
        this.store = store;
        this.projector = projector;
        this.businessCalendar = businessCalendar;
        this.riskRuleRegistry = riskRuleRegistry;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 开户
    // ------------------------------------------------------------------

    public AccountSnapshot openAccount(OpenAccountRequest request) {
        requireText(request.accountId(), "accountId");
        requirePositive(request.principal(), "principal");
        requirePositive(request.annualRate(), "annualRate");
        if (request.termMonths() == null || request.termMonths() <= 0) {
            throw new LoanEngineException(ErrorCode.INVALID_REQUEST, "termMonths must be positive");
        }
        String basis = request.dayCountBasis() != null
                ? request.dayCountBasis() : properties.getDayCountBasis();
        if (!"ACT/365".equals(basis) && !"ACT/360".equals(basis)) {
            throw new LoanEngineException(ErrorCode.INVALID_REQUEST,
                    "unsupported dayCountBasis: " + basis);
        }
        Terms terms = new Terms(money(request.principal()), request.annualRate(),
                request.termMonths(), basis);

        LoanAccount account = store.create(request.accountId());
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime openedAt = request.openedAt() != null ? request.openedAt() : now;
        if (openedAt.isAfter(now)) {
            throw new LoanEngineException(ErrorCode.INVALID_REQUEST,
                    "openedAt cannot be in the future");
        }
        synchronized (account) {
            account.append(new Opened(account.nextSeq(), openedAt, request.accountId(),
                    terms, request.firstDueDate()));
        }
        log.info("[open] account={} principal={} rate={} terms={} firstDue={} openedAt={}",
                request.accountId(), terms.principal(), terms.annualRate(),
                terms.termMonths(), request.firstDueDate(), openedAt);
        return projector.project(account, now);
    }

    // ------------------------------------------------------------------
    // 还款入账（幂等 + 账户锁）
    // ------------------------------------------------------------------

    public PaymentReceipt submitPayment(PaymentRequest request) {
        requireText(request.accountId(), "accountId");
        requireText(request.idempotencyKey(), "idempotencyKey");
        requirePositive(request.amount(), "amount");

        String key = request.idempotencyKey();
        LocalDateTime submittedAt = LocalDateTime.now(clock);
        // 未显式指定生效时间时应用业务日切（晚于日切点的提交顺延到次日）
        LocalDateTime effectiveAt = request.effectiveAt() != null
                ? request.effectiveAt()
                : businessCalendar.effectiveTime(submittedAt);
        // 显式补录/回溯只允许指向过去；日切产生的“次日”属于正常顺延
        if (request.effectiveAt() != null && request.effectiveAt().isAfter(submittedAt)) {
            throw new LoanEngineException(ErrorCode.BACKDATE_NOT_SUPPORTED,
                    "explicit effectiveAt cannot be in the future: " + effectiveAt);
        }

        // 1) 幂等表原子占位，区分“重复提交”与“同键冲突”
        IdempotencyRecord placeholder = new IdempotencyRecord(key, request.accountId(),
                money(request.amount()), effectiveAt, submittedAt);
        IdempotencyRecord existing = store.putIdempotencyIfAbsent(key, placeholder);
        if (existing != null) {
            return handleDuplicate(existing, request, effectiveAt, submittedAt);
        }

        try {
            return store.withAccountLock(request.accountId(), account -> {
                AccountSnapshot current = projector.project(account, submittedAt);
                if (current.status() != AccountStatus.ACTIVE
                        && current.status() != AccountStatus.SETTLED) {
                    throw new LoanEngineException(ErrorCode.ACCOUNT_NOT_PAYABLE,
                            "account is not payable, status=" + current.status());
                }
                if (current.status() == AccountStatus.SETTLED && !request.earlySettle()) {
                    throw new LoanEngineException(ErrorCode.ACCOUNT_NOT_PAYABLE,
                            "account already settled, no payment due");
                }

                BigDecimal required = projector.requiredAmount(
                        account, effectiveAt, request.earlySettle());
                BigDecimal amount = money(request.amount());
                if (required.signum() == 0) {
                    throw new LoanEngineException(ErrorCode.ACCOUNT_NOT_PAYABLE,
                            "nothing due at " + effectiveAt);
                }
                if (request.earlySettle()) {
                    if (amount.compareTo(required) != 0) {
                        throw new LoanEngineException(ErrorCode.PAYMENT_EXCEEDS_DUE,
                                "early settlement requires exactly " + required
                                        + ", got " + amount);
                    }
                } else if (amount.compareTo(required) > 0) {
                    throw new LoanEngineException(ErrorCode.PAYMENT_EXCEEDS_DUE,
                            "payment " + amount + " exceeds required " + required);
                }

                PaymentReceived event = new PaymentReceived(account.nextSeq(), effectiveAt,
                        key, amount, submittedAt, request.earlySettle());
                AccountProjector.QuoteResult quote =
                        projector.quotePayment(account, effectiveAt, event);
                account.append(event);
                log.info("[payment] account={} key={} amount={} effective={} early={} "
                                + "allocations={} statusAfter={}",
                        request.accountId(), key, amount, effectiveAt,
                        request.earlySettle(), quote.receipt().allocations(),
                        quote.snapshotAfter().status());
                return quote.receipt();
            });
        } catch (RuntimeException ex) {
            // 业务校验失败：释放幂等占位，允许客户端修正后用同一键重试
            store.releaseIdempotency(key, placeholder);
            throw ex;
        }
    }

    private PaymentReceipt handleDuplicate(IdempotencyRecord existing, PaymentRequest request,
                                           LocalDateTime effectiveAt, LocalDateTime submittedAt) {
        BigDecimal amount = money(request.amount());
        boolean sameAccount = existing.accountId().equals(request.accountId());
        boolean sameAmount = existing.amount().compareTo(amount) == 0;
        boolean sameEffective = ChronoUnit.SECONDS
                .between(existing.effectiveAt(), effectiveAt) == 0;
        if (!sameAccount || !sameAmount || !sameEffective) {
            throw new LoanEngineException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "idempotency key '" + request.idempotencyKey()
                            + "' already used with a different request"
                            + " (existingAccount=" + existing.accountId()
                            + ", existingAmount=" + existing.amount()
                            + ", existingEffectiveAt=" + existing.effectiveAt() + ")");
        }
        // 完全重复提交：在账户锁内等待首个提交落事件后，重放返回同一回执（不重复扣款）
        return store.withAccountLock(request.accountId(), account -> {
            PaymentReceipt receipt = null;
            for (int attempt = 0; attempt < 50; attempt++) {
                receipt = projector.project(account, submittedAt).payments().stream()
                        .filter(r -> r.idempotencyKey().equals(request.idempotencyKey()))
                        .findFirst().orElse(null);
                if (receipt != null) {
                    break;
                }
                try {
                    // 首个提交仍在账户锁内完成校验/入账：此处持锁意味着它已结束，
                    // 该等待仅用于极端调度下内存可见性兜底
                    Thread.sleep(2);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LoanEngineException(ErrorCode.IDEMPOTENCY_CONFLICT,
                            "interrupted while awaiting original payment: "
                                    + request.idempotencyKey());
                }
            }
            if (receipt == null) {
                throw new LoanEngineException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "idempotency key reserved but payment event missing: "
                                + request.idempotencyKey());
            }
            log.info("[payment-duplicate] account={} key={} replay receipt, no double charge",
                    request.accountId(), request.idempotencyKey());
            return receipt;
        });
    }

    // ------------------------------------------------------------------
    // 费用
    // ------------------------------------------------------------------

    public AccountSnapshot chargeFee(FeeRequest request) {
        requireText(request.accountId(), "accountId");
        requireText(request.feeId(), "feeId");
        requirePositive(request.amount(), "amount");
        LocalDateTime effectiveAt = request.effectiveAt() != null
                ? request.effectiveAt() : LocalDateTime.now(clock);
        return store.withAccountLock(request.accountId(), account -> {
            boolean dup = account.events().stream()
                    .anyMatch(e -> e instanceof FeeCharged f && f.feeId().equals(request.feeId()));
            if (dup) {
                throw new LoanEngineException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "fee already charged: " + request.feeId());
            }
            account.append(new FeeCharged(account.nextSeq(), effectiveAt,
                    request.feeId(), money(request.amount())));
            log.info("[fee] account={} feeId={} amount={} effective={}",
                    request.accountId(), request.feeId(), request.amount(), effectiveAt);
            return projector.project(account, LocalDateTime.now(clock));
        });
    }

    // ------------------------------------------------------------------
    // 风险状态迁移（带规则版本）
    // ------------------------------------------------------------------

    public AccountSnapshot transitionRisk(String accountId, RiskTransitionRequest request) {
        requireText(accountId, "accountId");
        RiskState to;
        try {
            to = RiskState.valueOf(request.toState());
        } catch (IllegalArgumentException ex) {
            throw new LoanEngineException(ErrorCode.INVALID_REQUEST,
                    "unknown risk state: " + request.toState());
        }
        LocalDateTime effectiveAt = request.effectiveAt() != null
                ? request.effectiveAt() : LocalDateTime.now(clock);
        String version = request.ruleVersion() != null
                ? request.ruleVersion()
                : riskRuleRegistry.effectiveAt(effectiveAt).version();
        riskRuleRegistry.require(version);
        LocalDateTime now = LocalDateTime.now(clock);

        return store.withAccountLock(accountId, account -> {
            AccountSnapshot current = projector.project(account, now);
            RiskState from = current.riskState();
            if (current.status() == AccountStatus.WRITTEN_OFF) {
                throw new LoanEngineException(ErrorCode.ILLEGAL_TRANSITION,
                        "written-off account cannot transition");
            }
            if (!RiskTransitions.isLegal(from, to)) {
                throw new LoanEngineException(ErrorCode.ILLEGAL_TRANSITION,
                        "illegal risk transition " + from + " -> " + to
                                + " (deterioration must be one level; write-off only from NPL)");
            }
            account.append(new RiskTransition(account.nextSeq(), effectiveAt,
                    from, to, version, request.reason()));
            log.info("[risk] account={} transition {} -> {} rule={} reason={}",
                    accountId, from, to, version, request.reason());
            return projector.project(account, now);
        });
    }

    /** 系统分类入口：按 DPD 与指定/当前规则版本给出目标态并做合法迁移。 */
    public AccountSnapshot classifyRisk(String accountId, RiskClassifyRequest request) {
        LocalDateTime effectiveAt = request.effectiveAt() != null
                ? request.effectiveAt() : LocalDateTime.now(clock);
        RiskRuleBook book = request.ruleVersion() != null
                ? riskRuleRegistry.require(request.ruleVersion())
                : riskRuleRegistry.effectiveAt(effectiveAt);
        String accountIdFinal = accountId;
        return store.withAccountLock(accountIdFinal, account -> {
            AccountSnapshot current = projector.project(account, effectiveAt);
            int dpd = dpdOf(current, effectiveAt);
            RiskState target = book.classifyByDpd(dpd);
            RiskState from = current.riskState();
            if (from == target) {
                log.info("[risk-classify] account={} unchanged {} dpd={} rule={}",
                        accountIdFinal, from, dpd, book.version());
                return current;
            }
            if (from == RiskState.WRITTEN_OFF || current.status() == AccountStatus.WRITTEN_OFF) {
                throw new LoanEngineException(ErrorCode.ILLEGAL_TRANSITION,
                        "written-off account cannot be classified");
            }
            // 系统分类由规则直接确定目标态：允许跨级（与人工“逐级下调”约束区分），
            // 但不允许从核销态返回，也不允许经分类进入核销态。
            if (target == RiskState.WRITTEN_OFF) {
                throw new LoanEngineException(ErrorCode.ILLEGAL_TRANSITION,
                        "classification cannot write off an account");
            }
            account.append(new RiskTransition(account.nextSeq(), effectiveAt,
                    from, target, book.version(),
                    request.reason() != null ? request.reason()
                            : ("auto-classify dpd=" + dpd)));
            log.info("[risk-classify] account={} {} -> {} dpd={} rule={}",
                    accountIdFinal, from, target, dpd, book.version());
            return projector.project(account, LocalDateTime.now(clock));
        });
    }

    private int dpdOf(AccountSnapshot snapshot, LocalDateTime at) {
        int grace = properties.getGraceDays();
        int max = 0;
        for (var ins : snapshot.installments()) {
            if (ins.settled()) {
                continue;
            }
            long d = ChronoUnit.DAYS.between(ins.dueDate(), at.toLocalDate()) - grace;
            if (d > max) {
                max = (int) d;
            }
        }
        return max;
    }

    // ------------------------------------------------------------------
    // 核销
    // ------------------------------------------------------------------

    public AccountSnapshot writeOff(String accountId, WriteOffRequest request) {
        LocalDateTime effectiveAt = request.effectiveAt() != null
                ? request.effectiveAt() : LocalDateTime.now(clock);
        return store.withAccountLock(accountId, account -> {
            AccountSnapshot current = projector.project(account, effectiveAt);
            if (current.status() == AccountStatus.WRITTEN_OFF) {
                throw new LoanEngineException(ErrorCode.ILLEGAL_TRANSITION,
                        "account already written off");
            }
            account.append(new WrittenOff(account.nextSeq(), effectiveAt, request.reason()));
            log.info("[write-off] account={} reason={}", accountId, request.reason());
            return projector.project(account, LocalDateTime.now(clock));
        });
    }

    // ------------------------------------------------------------------
    // 查询 / 重放
    // ------------------------------------------------------------------

    public AccountSnapshot getSnapshot(String accountId) {
        return projector.project(store.require(accountId), LocalDateTime.now(clock));
    }

    public AccountSnapshot replayAsOf(String accountId, LocalDateTime asOf) {
        return projector.project(store.require(accountId), asOf);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new LoanEngineException(ErrorCode.INVALID_REQUEST, field + " is required");
        }
    }

    private static void requirePositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new LoanEngineException(ErrorCode.INVALID_REQUEST,
                    field + " must be positive");
        }
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
