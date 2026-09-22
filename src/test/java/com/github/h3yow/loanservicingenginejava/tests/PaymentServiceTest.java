package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.domain.AccountStatus;
import com.github.h3yow.loanservicingenginejava.domain.AllocationBucket;
import com.github.h3yow.loanservicingenginejava.domain.LoanAccount;
import com.github.h3yow.loanservicingenginejava.domain.LoanTerms;
import com.github.h3yow.loanservicingenginejava.domain.PaymentCommand;
import com.github.h3yow.loanservicingenginejava.domain.PaymentOutcome;
import com.github.h3yow.loanservicingenginejava.domain.RejectReason;
import com.github.h3yow.loanservicingenginejava.domain.RepaymentMethod;
import com.github.h3yow.loanservicingenginejava.service.LoanServicingService;
import com.github.h3yow.loanservicingenginejava.service.ServicingException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 还款入账：幂等重复/键冲突、超额拒绝、不可入账拒绝、
 * 分摊顺序（罚息->费用->利息->本金）、部分还款、提前结清。
 */
@SpringBootTest
class PaymentServiceTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceTest.class);

    @Autowired
    LoanServicingService service;

    private LoanTerms terms(String id, int n, BigDecimal feeRate) {
        return new LoanTerms(new BigDecimal("12000"), new BigDecimal("0.12"), n,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 1),
                RepaymentMethod.EQUAL_INSTALLMENT, feeRate);
    }

    private PaymentCommand pay(String id, String key, String amount, LocalDate date) {
        return new PaymentCommand(id, key, new BigDecimal(amount), date);
    }

    @Test
    void duplicateIdempotencyKeyDoesNotDoubleCharge() {
        String id = "pay-dup";
        service.openAccount(id, terms(id, 12, BigDecimal.ZERO));
        LocalDate d = LocalDate.of(2025, 2, 1);
        PaymentOutcome first = service.postPayment(pay(id, "dup-key", "1063", d),
                LocalDateTime.of(2025, 2, 1, 10, 0));
        PaymentOutcome second = service.postPayment(pay(id, "dup-key", "1063", d),
                LocalDateTime.of(2025, 2, 1, 11, 0));
        log.info("[输入] 同键同金额提交两次; [判定] first.duplicate={} second.duplicate={}; [最终] 流水笔数={} 剩余一致={}",
                first.duplicate, second.duplicate, service.getPayments(id).size(),
                first.remainingTotal.compareTo(second.remainingTotal) == 0);
        assertFalse(first.duplicate);
        assertTrue(second.duplicate);
        assertEquals(1, service.getPayments(id).size(), "重复提交不得产生第二条流水");
        assertEquals(0, first.remainingTotal.compareTo(second.remainingTotal));
        assertEquals(second.lines.size(), first.lines.size());
    }

    @Test
    void idempotencyKeyConflictRejected() {
        String id = "pay-conflict";
        service.openAccount(id, terms(id, 12, BigDecimal.ZERO));
        service.postPayment(pay(id, "conflict-key", "100", LocalDate.of(2025, 2, 1)),
                LocalDateTime.of(2025, 2, 1, 10, 0));
        ServicingException ex = assertThrows(ServicingException.class, () ->
                service.postPayment(pay(id, "conflict-key", "200", LocalDate.of(2025, 2, 1)),
                        LocalDateTime.of(2025, 2, 1, 12, 0)));
        log.info("[输入] 同键不同金额; [最终] 拒绝原因={}", ex.getReason());
        assertEquals(RejectReason.IDEMPOTENCY_KEY_CONFLICT, ex.getReason());
        assertEquals(1, service.getPayments(id).size());
    }

    @Test
    void overpaymentRejected() {
        String id = "pay-over";
        service.openAccount(id, terms(id, 12, BigDecimal.ZERO));
        ServicingException ex = assertThrows(ServicingException.class, () ->
                service.postPayment(pay(id, "over-key", "999999", LocalDate.of(2025, 2, 1)),
                        LocalDateTime.of(2025, 2, 1, 10, 0)));
        log.info("[输入] 超额还款999999; [最终] 拒绝原因={}", ex.getReason());
        assertEquals(RejectReason.OVERPAYMENT, ex.getReason());
        assertEquals(0, service.getPayments(id).size());
    }

    @Test
    void invalidAmountRejected() {
        String id = "pay-invalid";
        service.openAccount(id, terms(id, 12, BigDecimal.ZERO));
        ServicingException ex = assertThrows(ServicingException.class, () ->
                service.postPayment(pay(id, "zero-key", "0", LocalDate.of(2025, 2, 1)),
                        LocalDateTime.of(2025, 2, 1, 10, 0)));
        log.info("[最终] 零金额拒绝原因={}", ex.getReason());
        assertEquals(RejectReason.PAYMENT_AMOUNT_INVALID, ex.getReason());
    }

    @Test
    void partialPaymentSettlesWhenPayingExactDue() {
        String id = "pay-partial";
        LoanAccount opened = service.openAccount(id, terms(id, 12, BigDecimal.ZERO));
        // 首期应还总额
        InstallmentView first = InstallmentView.of(opened, 1);
        BigDecimal firstDue = first.principal().add(first.interest());
        log.info("[输入] 首期应还 本金={} 利息={} 合计={}", first.principal(), first.interest(), firstDue);

        PaymentOutcome partial = service.postPayment(pay(id, "p-partial", "500",
                LocalDate.of(2025, 2, 1)), LocalDateTime.of(2025, 2, 1, 10, 0));
        log.info("[判定] 部分还款500: 分摊{}条 状态仍ACTIVE={}", partial.lines.size(),
                partial.accountStatus == AccountStatus.ACTIVE);
        assertEquals(AccountStatus.ACTIVE, partial.accountStatus);
        BigDecimal remainingAfterPartial = partial.remainingTotal;

        PaymentOutcome rest = service.postPayment(pay(id, "p-rest",
                firstDue.subtract(new BigDecimal("500")).toPlainString(),
                LocalDate.of(2025, 2, 1)), LocalDateTime.of(2025, 2, 1, 10, 30));
        log.info("[判定] 补足差额后首期应结清; 剩余较部分还款后下降={}",
                rest.remainingTotal.compareTo(remainingAfterPartial) < 0);
        assertTrue(rest.remainingTotal.compareTo(remainingAfterPartial) < 0);
    }

    @Test
    void earlySettlementInOnePayment() {
        String id = "pay-early";
        service.openAccount(id, terms(id, 12, BigDecimal.ZERO));
        // 首期正常小额，之后一次性结清剩余（无罚金场景：首期当天之后不久，但可能产生后续期罚息=0）
        service.postPayment(pay(id, "e1", "1063", LocalDate.of(2025, 2, 1)),
                LocalDateTime.of(2025, 2, 1, 10, 0));
        LoanAccount account = service.getAccount(id);
        BigDecimal outstanding = outstandingScheduled(account);
        log.info("[输入] 提前结清: 剩余计划款={}", outstanding);
        PaymentOutcome settle = service.postPayment(pay(id, "e2", outstanding.toPlainString(),
                LocalDate.of(2025, 2, 2)), LocalDateTime.of(2025, 2, 2, 10, 0));
        log.info("[最终] 账户状态={} 剩余={}", settle.accountStatus, settle.remainingTotal);
        assertEquals(AccountStatus.SETTLED, settle.accountStatus);
        assertEquals(0, settle.remainingTotal.signum());
    }

    @Test
    void postToSettledAccountRejected() {
        String id = "pay-settled";
        service.openAccount(id, terms(id, 1, BigDecimal.ZERO));
        LoanAccount a = service.getAccount(id);
        BigDecimal due = outstandingScheduled(a);
        service.postPayment(pay(id, "s1", due.toPlainString(), LocalDate.of(2025, 2, 1)),
                LocalDateTime.of(2025, 2, 1, 10, 0));
        ServicingException ex = assertThrows(ServicingException.class, () ->
                service.postPayment(pay(id, "s2", "1", LocalDate.of(2025, 2, 2)),
                        LocalDateTime.of(2025, 2, 2, 10, 0)));
        log.info("[最终] 已结清账户再入账拒绝原因={}", ex.getReason());
        assertEquals(RejectReason.ACCOUNT_NOT_POSTABLE, ex.getReason());
    }

    @Test
    void allocationOrderPenaltyFirstThenFeeInterestPrincipal() {
        String id = "pay-order";
        // 含费用：3 期、3% 一次性费率
        service.openAccount(id, terms(id, 3, new BigDecimal("0.03")));
        // 逾期到第 2 期到期后 20 天再还一笔小额，金额不足以覆盖全部欠款
        LocalDate payDate = LocalDate.of(2025, 3, 24);
        PaymentOutcome outcome = service.postPayment(pay(id, "o1", "50", payDate),
                LocalDateTime.of(payDate, LocalDateTime.of(2025, 3, 24, 10, 0).toLocalTime()));
        AllocationBucket firstBucket = outcome.lines.get(0).bucket;
        log.info("[输入] 逾期小额还款50; [判定] 第一条分摊桶={} 明细={}",
                firstBucket, outcome.lines);
        // 第一期已逾期必然产生罚息，故第一笔分摊必须是罚息
        assertEquals(AllocationBucket.PENALTY, firstBucket,
                "分摊顺序必须是罚息优先");
        // 桶顺序单调：PENALTY -> FEE -> INTEREST -> PRINCIPAL
        List<AllocationBucket> order = outcome.lines.stream().map(l -> l.bucket).toList();
        int rankPenalty = -1;
        for (AllocationBucket b : order) {
            int rank = switch (b) {
                case PENALTY -> 0;
                case FEE -> 1;
                case INTEREST -> 2;
                case PRINCIPAL -> 3;
            };
            assertTrue(rank >= rankPenalty, "分摊桶必须按顺序出现: " + order);
            rankPenalty = rank;
        }
    }

    private BigDecimal outstandingScheduled(LoanAccount a) {
        BigDecimal sum = BigDecimal.ZERO;
        for (var i : a.installments) {
            sum = sum.add(i.scheduledPrincipal.subtract(i.paidPrincipal).max(BigDecimal.ZERO))
                    .add(i.scheduledInterest.subtract(i.paidInterest).max(BigDecimal.ZERO))
                    .add(i.scheduledFee.subtract(i.paidFee).max(BigDecimal.ZERO));
        }
        return sum;
    }

    private record InstallmentView(BigDecimal principal, BigDecimal interest) {
        static InstallmentView of(LoanAccount a, int seq) {
            var i = a.installments.get(seq - 1);
            return new InstallmentView(i.scheduledPrincipal, i.scheduledInterest);
        }
    }
}
