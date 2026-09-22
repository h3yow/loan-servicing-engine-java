package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.domain.AccountSnapshot;
import com.github.h3yow.loanservicingenginejava.domain.AccountStatus;
import com.github.h3yow.loanservicingenginejava.domain.ComponentType;
import com.github.h3yow.loanservicingenginejava.domain.PaymentReceipt;
import com.github.h3yow.loanservicingenginejava.error.ErrorCode;
import com.github.h3yow.loanservicingenginejava.error.LoanEngineException;
import com.github.h3yow.loanservicingenginejava.tests.TestFixtures.Engine;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.FeeRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.OpenAccountRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.PaymentRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.WriteOffRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 还款入账：正常、部分还款、提前结清、超额拒绝、账户不可入账。 */
class RepaymentTest {

    private static final Logger log = LoggerFactory.getLogger(RepaymentTest.class);

    private Engine newEngineWithAccount(String accountId) {
        Engine engine = TestFixtures.engine();
        engine.service().openAccount(new OpenAccountRequest(accountId,
                new BigDecimal("12000.00"), new BigDecimal("0.12"), 12,
                LocalDate.of(2026, 1, 31), "ACT/365",
                LocalDateTime.of(2026, 1, 1, 10, 0)));
        return engine;
    }

    @Test
    void firstInstallment_partialThenFull_feeInterestPrincipalOrder() {
        Engine engine = newEngineWithAccount("A-REP-1");
        // 先计收一笔 50.00 费用，制造 FEE 分摊
        engine.service().chargeFee(new FeeRequest("A-REP-1", "FEE-1",
                new BigDecimal("50.00"), LocalDateTime.of(2026, 1, 31, 9, 0)));

        // 到期日 2026-01-31，第一期应还本金 1000.00，利息 120.00
        // 部分还款 200.00：FEE(50) -> PENALTY(0, 宽限期内) -> INTEREST(120) -> PRINCIPAL(30)
        PaymentReceipt partial = engine.service().submitPayment(new PaymentRequest(
                "A-REP-1", "KEY-P1", new BigDecimal("200.00"),
                LocalDateTime.of(2026, 2, 2, 10, 0), false));
        log.info("[输入] KEY-P1 amount=200.00 还款日=2026-02-02(宽限期内, 无罚息)");
        log.info("[判定依据] 分摊={}", partial.allocations());
        assertEquals(new BigDecimal("50.00"), amountOf(partial, ComponentType.FEE));
        assertEquals(new BigDecimal("120.00"), amountOf(partial, ComponentType.INTEREST));
        assertEquals(new BigDecimal("30.00"), amountOf(partial, ComponentType.PRINCIPAL));
        assertEquals(new BigDecimal("200.00"), partial.allocated());

        // 补足第一期剩余本金 970.00（利息已在宽限期内还清，无罚息）
        PaymentReceipt rest = engine.service().submitPayment(new PaymentRequest(
                "A-REP-1", "KEY-P2", new BigDecimal("970.00"),
                LocalDateTime.of(2026, 2, 3, 10, 0), false));
        log.info("[输入] KEY-P2 amount=970.00 还款日=2026-02-03(宽限期最后一日)");
        log.info("[判定依据] 分摊={}", rest.allocations());
        assertEquals(new BigDecimal("970.00"), amountOf(rest, ComponentType.PRINCIPAL));

        AccountSnapshot snap = engine.service().getSnapshot("A-REP-1");
        log.info("[最终状态] status={} 第一期 settled={} 剩余本金={} 剩余利息={} 总待还={}",
                snap.status(), snap.installments().get(0).settled(),
                snap.balance().outstandingPrincipal(),
                snap.balance().outstandingInterest(), snap.balance().totalDue());
        assertTrue(snap.installments().get(0).settled());
        // 剩余本金 11000，利息 1080（11 期 × 120 中第 2 期尚未还，但 2 月只过了 3 天不到期）
        assertEquals(new BigDecimal("11000.00"), snap.balance().outstandingPrincipal());
    }

    @Test
    void overpayment_beforeDue_isRejected() {
        Engine engine = newEngineWithAccount("A-REP-2");
        // 2026-01-15 第一期尚未到期（到期日 1/31），应缴为 0
        LoanEngineException ex = assertThrows(LoanEngineException.class,
                () -> engine.service().submitPayment(new PaymentRequest(
                        "A-REP-2", "KEY-OVER", new BigDecimal("500.00"),
                        LocalDateTime.of(2026, 1, 15, 10, 0), false)));
        log.info("[输入] KEY-OVER amount=500.00 还款日=2026-01-15(无到期期)");
        log.info("[拒绝原因] errorCode={} message={}", ex.getErrorCode(), ex.getMessage());
        // 无应缴时被视为账户不可入账（nothing due）
        assertEquals(ErrorCode.ACCOUNT_NOT_PAYABLE, ex.getErrorCode());
    }

    @Test
    void overpayment_afterDue_isRejectedWithDistinctReason() {
        Engine engine = newEngineWithAccount("A-REP-3");
        // 第一期应缴 1120.00，多还 1 分钱
        LoanEngineException ex = assertThrows(LoanEngineException.class,
                () -> engine.service().submitPayment(new PaymentRequest(
                        "A-REP-3", "KEY-OVER2", new BigDecimal("1120.01"),
                        LocalDateTime.of(2026, 2, 1, 10, 0), false)));
        log.info("[输入] KEY-OVER2 amount=1120.01 应缴=1120.00");
        log.info("[拒绝原因] errorCode={} message={}", ex.getErrorCode(), ex.getMessage());
        assertEquals(ErrorCode.PAYMENT_EXCEEDS_DUE, ex.getErrorCode());
    }

    @Test
    void earlySettlement_waivesFutureInterest() {
        Engine engine = newEngineWithAccount("A-REP-4");
        LocalDateTime payAt = LocalDateTime.of(2026, 1, 31, 10, 0);
        // 等本方式下各期利息随剩余本金递减（120,110,...,10），合计 780；
        // 结清报价 = 全部本金 12000 + 第 1 期利息 120 = 12120，
        // 其余 11 期利息 660（110+100+...+10）被减免
        PaymentReceipt receipt = engine.service().submitPayment(new PaymentRequest(
                "A-REP-4", "KEY-EARLY", new BigDecimal("12120.00"), payAt, true));
        log.info("[输入] KEY-EARLY amount=12120.00 提前结清日=2026-01-31");
        log.info("[判定依据] 分摊={} 减免利息={}", receipt.allocations(), receipt.waivedInterest());
        assertEquals(new BigDecimal("660.00"), receipt.waivedInterest());
        assertTrue(receipt.earlySettlement());

        AccountSnapshot snap = engine.service().getSnapshot("A-REP-4");
        log.info("[最终状态] status={} 总待还={} 累计减免={}",
                snap.status(), snap.balance().totalDue(), snap.totalWaivedInterest());
        assertEquals(AccountStatus.SETTLED, snap.status());
        assertEquals(BigDecimal.ZERO.setScale(2), snap.balance().totalDue());
    }

    @Test
    void earlySettlement_shortPayment_rejected() {
        Engine engine = newEngineWithAccount("A-REP-5");
        LoanEngineException ex = assertThrows(LoanEngineException.class,
                () -> engine.service().submitPayment(new PaymentRequest(
                        "A-REP-5", "KEY-EARLY-S", new BigDecimal("12000.00"),
                        LocalDateTime.of(2026, 1, 31, 10, 0), true)));
        log.info("[输入] KEY-EARLY-S amount=12000.00 提前结清应缴=12120.00");
        log.info("[拒绝原因] errorCode={} message={}", ex.getErrorCode(), ex.getMessage());
        assertEquals(ErrorCode.PAYMENT_EXCEEDS_DUE, ex.getErrorCode());
    }

    @Test
    void writtenOffAccount_isNotPayable() {
        Engine engine = newEngineWithAccount("A-REP-6");
        engine.service().writeOff("A-REP-6", new WriteOffRequest(
                "fraud", LocalDateTime.of(2026, 2, 1, 10, 0)));
        LoanEngineException ex = assertThrows(LoanEngineException.class,
                () -> engine.service().submitPayment(new PaymentRequest(
                        "A-REP-6", "KEY-WO", new BigDecimal("100.00"),
                        LocalDateTime.of(2026, 2, 2, 10, 0), false)));
        log.info("[输入] 核销账户还款 KEY-WO amount=100.00");
        log.info("[拒绝原因] errorCode={} message={}", ex.getErrorCode(), ex.getMessage());
        assertEquals(ErrorCode.ACCOUNT_NOT_PAYABLE, ex.getErrorCode());
    }

    private static BigDecimal amountOf(PaymentReceipt r, ComponentType type) {
        return r.allocations().stream()
                .filter(a -> a.component() == type)
                .map(a -> a.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
