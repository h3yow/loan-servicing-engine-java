package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.domain.AccountSnapshot;
import com.github.h3yow.loanservicingenginejava.domain.PaymentReceipt;
import com.github.h3yow.loanservicingenginejava.error.ErrorCode;
import com.github.h3yow.loanservicingenginejava.error.LoanEngineException;
import com.github.h3yow.loanservicingenginejava.tests.TestFixtures.Engine;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.OpenAccountRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.PaymentRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 幂等：重复提交不重复扣款；同键不同请求被拒绝且原因可区分。 */
class IdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyTest.class);

    private Engine account(String id) {
        Engine engine = TestFixtures.engine();
        engine.service().openAccount(new OpenAccountRequest(id,
                new BigDecimal("12000.00"), new BigDecimal("0.12"), 12,
                LocalDate.of(2026, 1, 31), "ACT/365",
                LocalDateTime.of(2026, 1, 1, 10, 0)));
        return engine;
    }

    @Test
    void duplicate_submission_doesNotDoubleCharge() {
        Engine engine = account("A-IDEM-1");
        PaymentRequest req = new PaymentRequest("A-IDEM-1", "DUP-KEY",
                new BigDecimal("1120.00"), LocalDateTime.of(2026, 2, 2, 10, 0), false);

        PaymentReceipt first = engine.service().submitPayment(req);
        PaymentReceipt second = engine.service().submitPayment(req);
        PaymentReceipt third = engine.service().submitPayment(req);

        log.info("[输入] 同一请求提交 3 次 key=DUP-KEY amount=1120.00");
        log.info("[判定依据] 三次回执 allocated: {}, {}, {}",
                first.allocated(), second.allocated(), third.allocated());
        AccountSnapshot snap = engine.service().getSnapshot("A-IDEM-1");
        log.info("[最终状态] 剩余本金={} 剩余利息={} 还款笔数={}",
                snap.balance().outstandingPrincipal(),
                snap.balance().outstandingInterest(), snap.payments().size());

        assertEquals(first.allocated(), second.allocated());
        assertEquals(first.allocated(), third.allocated());
        assertEquals(1, snap.payments().size(), "只允许有一笔入账事件");
        assertEquals(new BigDecimal("11000.00"), snap.balance().outstandingPrincipal());
    }

    @Test
    void sameKey_differentAmount_isConflict() {
        Engine engine = account("A-IDEM-2");
        LocalDateTime at = LocalDateTime.of(2026, 2, 2, 10, 0);
        engine.service().submitPayment(new PaymentRequest("A-IDEM-2", "X-KEY",
                new BigDecimal("1120.00"), at, false));
        LoanEngineException ex = assertThrows(LoanEngineException.class,
                () -> engine.service().submitPayment(new PaymentRequest("A-IDEM-2", "X-KEY",
                        new BigDecimal("500.00"), at, false)));
        log.info("[输入] 同键 X-KEY 不同金额 1120.00 -> 500.00");
        log.info("[拒绝原因] errorCode={} message={}", ex.getErrorCode(), ex.getMessage());
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, ex.getErrorCode());
    }

    @Test
    void sameKey_differentAccount_isConflict() {
        Engine engine = account("A-IDEM-3");
        account("A-IDEM-4");
        LocalDateTime at = LocalDateTime.of(2026, 2, 2, 10, 0);
        engine.service().submitPayment(new PaymentRequest("A-IDEM-3", "CROSS-KEY",
                new BigDecimal("1120.00"), at, false));
        LoanEngineException ex = assertThrows(LoanEngineException.class,
                () -> engine.service().submitPayment(new PaymentRequest("A-IDEM-4", "CROSS-KEY",
                        new BigDecimal("1120.00"), at, false)));
        log.info("[输入] 同键 CROSS-KEY 跨账户 A-IDEM-3 -> A-IDEM-4");
        log.info("[拒绝原因] errorCode={} message={}", ex.getErrorCode(), ex.getMessage());
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, ex.getErrorCode());
    }

    @Test
    void failedValidation_doesNotConsumeKey() {
        Engine engine = account("A-IDEM-5");
        // 超额还款失败
        assertThrows(LoanEngineException.class,
                () -> engine.service().submitPayment(new PaymentRequest("A-IDEM-5", "RETRY-KEY",
                        new BigDecimal("9999.99"), LocalDateTime.of(2026, 2, 2, 10, 0), false)));
        log.info("[输入] RETRY-KEY 首次提交超额 9999.99（应被拒绝并释放占位）");
        // 用同一键提交合法金额应当成功，而不是被幂等冲突拦截
        PaymentReceipt receipt = engine.service().submitPayment(new PaymentRequest(
                "A-IDEM-5", "RETRY-KEY", new BigDecimal("1120.00"),
                LocalDateTime.of(2026, 2, 2, 11, 0), false));
        log.info("[判定依据] 修正后同键入账成功 allocated={}", receipt.allocated());
        assertEquals(new BigDecimal("1120.00"), receipt.allocated());
    }

    @Test
    void conflictReason_differsFromOtherErrors() {
        Engine engine = account("A-IDEM-7");
        engine.service().submitPayment(new PaymentRequest("A-IDEM-7", "Z-KEY",
                new BigDecimal("1120.00"), LocalDateTime.of(2026, 2, 2, 10, 0), false));
        // 同键不同金额 → 幂等冲突
        LoanEngineException conflict = assertThrows(LoanEngineException.class,
                () -> engine.service().submitPayment(new PaymentRequest("A-IDEM-7", "Z-KEY",
                        new BigDecimal("10.00"), LocalDateTime.of(2026, 2, 2, 10, 0), false)));
        // 另一键超额 → 超额错误，两者错误码必须可区分
        LoanEngineException overpay = assertThrows(LoanEngineException.class,
                () -> engine.service().submitPayment(new PaymentRequest("A-IDEM-7", "OVER-KEY",
                        new BigDecimal("9999.99"), LocalDateTime.of(2026, 3, 5, 10, 0), false)));
        log.info("[判定依据] 幂等冲突={} vs 超额={}",
                conflict.getErrorCode(), overpay.getErrorCode());
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, conflict.getErrorCode());
        assertEquals(ErrorCode.PAYMENT_EXCEEDS_DUE, overpay.getErrorCode());
        assertNotEquals(conflict.getErrorCode(), overpay.getErrorCode());
    }
}
