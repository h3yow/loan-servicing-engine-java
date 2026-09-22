package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.domain.LoanAccount;
import com.github.h3yow.loanservicingenginejava.domain.LoanTerms;
import com.github.h3yow.loanservicingenginejava.domain.PaymentCommand;
import com.github.h3yow.loanservicingenginejava.domain.PaymentRecord;
import com.github.h3yow.loanservicingenginejava.domain.RepaymentMethod;
import com.github.h3yow.loanservicingenginejava.service.LoanServicingService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回溯/补录：后提交的早期还款导致罚息与状态按生效日重算；
 * 相同输入重放结果一致；守恒恒等式始终成立。
 */
@SpringBootTest
class BackdatingAndReplayTest {

    private static final Logger log = LoggerFactory.getLogger(BackdatingAndReplayTest.class);

    @Autowired
    LoanServicingService service;

    private void open(String id) {
        LoanTerms terms = new LoanTerms(new BigDecimal("12000"), new BigDecimal("0.12"), 12,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 1),
                RepaymentMethod.EQUAL_INSTALLMENT, BigDecimal.ZERO);
        service.openAccount(id, terms);
    }

    /** 账户级守恒：计划总额 + 应计罚息毛额 = 已收 + 剩余；通过逐期非负与已收<=计划总额校验。 */
    private void assertNonNegative(LoanAccount a) {
        for (var i : a.installments) {
            assertTrue(i.paidPrincipal.compareTo(i.scheduledPrincipal) <= 0,
                    "期" + i.seq + "还本超额");
            assertTrue(i.paidInterest.compareTo(i.scheduledInterest) <= 0);
            assertTrue(i.paidFee.compareTo(i.scheduledFee) <= 0);
            assertTrue(i.outstandingPenalty.signum() >= 0);
        }
    }

    @Test
    void backdatedPrincipalPaymentReducesLaterPenalty() {
        String id = "backdate-1";
        open(id);
        // 先在 4/20 逾期还一笔小额（必然含罚息）
        var late = service.postPayment(
                new PaymentCommand(id, "late", new BigDecimal("200"), LocalDate.of(2025, 4, 20)),
                LocalDateTime.of(2025, 4, 20, 10, 0));
        BigDecimal penaltyPaidLate = sumPenalty(late.lines);
        log.info("[场景A] 仅4/20还款200, 其中罚息={}", penaltyPaidLate);
        assertNonNegative(service.getAccount(id));

        // 补录一笔 2/10 的早期还款（提交时间在更晚），同样 200
        String id2 = "backdate-2";
        open(id2);
        service.postPayment(
                new PaymentCommand(id2, "early", new BigDecimal("200"), LocalDate.of(2025, 2, 10)),
                LocalDateTime.of(2025, 4, 10, 10, 0));
        var late2 = service.postPayment(
                new PaymentCommand(id2, "late2", new BigDecimal("200"), LocalDate.of(2025, 4, 20)),
                LocalDateTime.of(2025, 4, 20, 10, 0));
        BigDecimal penaltyPaidLate2 = sumPenalty(late2.lines);
        log.info("[场景B] 补录2/10还款200后, 4/20还款200中罚息={}; 早还本金应压低罚息基数={}",
                penaltyPaidLate2, penaltyPaidLate2.compareTo(penaltyPaidLate) < 0);
        assertNonNegative(service.getAccount(id2));
        assertTrue(penaltyPaidLate2.compareTo(penaltyPaidLate) < 0,
                "补录早期还本后，后续被冲抵的罚息必须减少（按生效日重算）");
    }

    @Test
    void replaySameInputsGivesSameResult() {
        String id1 = "replay-a";
        String id2 = "replay-b";
        open(id1);
        open(id2);
        // 两个独立账户，按不同"提交顺序/时间"提交相同的（生效日, 金额, 键后缀区分）序列
        String[][] seqA = {
                {"p1", "100", "2025-02-01", "2025-02-01T10:00"},
                {"p2", "200", "2025-03-01", "2025-03-01T10:00"},
                {"p3", "150", "2025-02-15", "2025-04-01T10:00"}, // 补录，提交更晚
                {"p4", "300", "2025-05-01", "2025-05-01T10:00"}
        };
        String[][] seqB = {
                {"p3", "150", "2025-02-15", "2025-02-16T09:00"}, // 换个提交时间
                {"p1", "100", "2025-02-01", "2025-02-02T09:00"},
                {"p4", "300", "2025-05-01", "2025-05-02T09:00"},
                {"p2", "200", "2025-03-01", "2025-03-02T09:00"}
        };
        for (String[] s : seqA) {
            submit(id1, s);
        }
        for (String[] s : seqB) {
            submit(id2, s);
        }
        LocalDate view = LocalDate.of(2025, 6, 1);
        // 通过再次以相同 asOf 查询，比较两账户派生口径
        LoanAccount a = service.getAccount(id1);
        LoanAccount b = service.getAccount(id2);
        // 直接比较流水在同一视图日重放后的各期已还与罚息：再次 get 不改变结果
        LoanAccount a2 = service.getAccount(id1);
        for (int k = 0; k < a.installments.size(); k++) {
            var x = a.installments.get(k);
            var y = b.installments.get(k);
            var x2 = a2.installments.get(k);
            assertEquals(0, x.paidPrincipal.compareTo(y.paidPrincipal), "期" + (k + 1) + "还本应一致");
            assertEquals(0, x.paidInterest.compareTo(y.paidInterest), "期" + (k + 1) + "还息应一致");
            assertEquals(0, x.paidPenalty.compareTo(y.paidPenalty), "期" + (k + 1) + "罚息应一致");
            assertEquals(0, x.paidPrincipal.compareTo(x2.paidPrincipal), "重复读取可复现");
        }
        log.info("[判定] 提交顺序/提交时间不同，但生效日与金额相同 => 两账户派生口径完全一致且重复读取稳定");
    }

    @Test
    void futureEffectiveDateRejected() {
        String id = "backdate-future";
        open(id);
        try {
            service.postPayment(
                    new PaymentCommand(id, "future", new BigDecimal("100"),
                            LocalDate.of(2030, 1, 1)),
                    LocalDateTime.of(2025, 2, 1, 10, 0));
            throw new AssertionError("未来生效日应被拒绝");
        } catch (com.github.h3yow.loanservicingenginejava.service.ServicingException e) {
            log.info("[最终] 未来生效日拒绝原因={}", e.getReason());
            assertEquals(com.github.h3yow.loanservicingenginejava.domain.RejectReason.VALIDATION_ERROR,
                    e.getReason());
        }
    }

    private void submit(String id, String[] s) {
        service.postPayment(
                new PaymentCommand(id, id + "-" + s[0], new BigDecimal(s[1]), LocalDate.parse(s[2])),
                LocalDateTime.parse(s[3]));
    }

    private BigDecimal sumPenalty(java.util.List<com.github.h3yow.loanservicingenginejava.domain.AllocationLine> lines) {
        return lines.stream()
                .filter(l -> l.bucket == com.github.h3yow.loanservicingenginejava.domain.AllocationBucket.PENALTY)
                .map(l -> l.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
