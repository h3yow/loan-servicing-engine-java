package com.github.h3yow.loanservicingenginejava.web;

import com.github.h3yow.loanservicingenginejava.domain.AccountView;
import com.github.h3yow.loanservicingenginejava.domain.LoanAccount;
import com.github.h3yow.loanservicingenginejava.domain.LoanTerms;
import com.github.h3yow.loanservicingenginejava.domain.PaymentCommand;
import com.github.h3yow.loanservicingenginejava.domain.PaymentOutcome;
import com.github.h3yow.loanservicingenginejava.domain.RepaymentMethod;
import com.github.h3yow.loanservicingenginejava.domain.ReversalCommand;
import com.github.h3yow.loanservicingenginejava.domain.ReversalOutcome;
import com.github.h3yow.loanservicingenginejava.domain.RiskState;
import com.github.h3yow.loanservicingenginejava.domain.RiskTransitionRecord;
import com.github.h3yow.loanservicingenginejava.service.LoanServicingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 本地贷款服务 HTTP 入口。
 */
@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private final LoanServicingService service;

    public LoanController(LoanServicingService service) {
        this.service = service;
    }

    public record OpenAccountRequest(BigDecimal principal, BigDecimal annualRate, int installmentCount,
                                     LocalDate originationDate, LocalDate firstDueDate,
                                     RepaymentMethod method, BigDecimal feeRate) {
    }

    public record PaymentRequest(String idempotencyKey, BigDecimal amount, LocalDate effectiveDate) {
    }

    public record ReversalRequest(Long paymentSequence, String idempotencyKey, BigDecimal amount,
                                  LocalDate effectiveDate, String reason) {
    }

    public record RiskTransitionRequest(RiskState target, LocalDate effectiveDate, String reason) {
    }

    @PostMapping("/accounts/{accountId}")
    public LoanAccount open(@PathVariable String accountId,
                            @RequestBody OpenAccountRequest req) {
        LoanTerms terms = new LoanTerms(req.principal(), req.annualRate(), req.installmentCount(),
                req.originationDate(), req.firstDueDate(), req.method(), req.feeRate());
        return service.openAccount(accountId, terms);
    }

    @PostMapping("/accounts/{accountId}/payments")
    public PaymentOutcome pay(@PathVariable String accountId,
                              @RequestBody PaymentRequest req) {
        return service.postPayment(
                new PaymentCommand(accountId, req.idempotencyKey(), req.amount(), req.effectiveDate()),
                LocalDateTime.now());
    }

    @PostMapping("/accounts/{accountId}/reversals")
    public ReversalOutcome reverse(@PathVariable String accountId,
                                   @RequestBody ReversalRequest req) {
        return service.postReversal(
                new ReversalCommand(accountId, req.idempotencyKey(), req.paymentSequence(),
                        req.amount(), req.effectiveDate(), req.reason()),
                LocalDateTime.now());
    }

    @PostMapping("/accounts/{accountId}/risk-transitions")
    public RiskTransitionRecord transition(@PathVariable String accountId,
                                           @RequestBody RiskTransitionRequest req) {
        return service.transitionRisk(accountId, req.target(), req.effectiveDate(), req.reason());
    }

    @PostMapping("/accounts/{accountId}/risk-reevaluation")
    public RiskTransitionRecord reevaluate(@PathVariable String accountId,
                                           @RequestBody(required = false) RiskTransitionRequest req) {
        LocalDate asOf = req != null && req.effectiveDate() != null ? req.effectiveDate() : LocalDate.now();
        return service.reevaluateRisk(accountId, asOf);
    }

    @GetMapping("/accounts/{accountId}")
    public AccountView get(@PathVariable String accountId,
                           @org.springframework.web.bind.annotation.RequestParam(required = false)
                           LocalDate asOf) {
        return service.getAccountView(accountId, asOf);
    }
}
