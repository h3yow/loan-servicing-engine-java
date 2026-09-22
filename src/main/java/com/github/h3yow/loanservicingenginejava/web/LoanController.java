package com.github.h3yow.loanservicingenginejava.web;

import com.github.h3yow.loanservicingenginejava.domain.AccountSnapshot;
import com.github.h3yow.loanservicingenginejava.domain.PaymentReceipt;
import com.github.h3yow.loanservicingenginejava.service.LoanService;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.FeeRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.OpenAccountRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.PaymentRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.RiskClassifyRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.RiskTransitionRequest;
import com.github.h3yow.loanservicingenginejava.web.ApiDtos.WriteOffRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/** 本地贷款服务 HTTP 入口（无外部依赖、无真实凭据）。 */
@RestController
@RequestMapping("/api/accounts")
public class LoanController {

    private final LoanService loanService;

    public LoanController(LoanService loanService) {
        this.loanService = loanService;
    }

    @PostMapping
    public AccountSnapshot open(@RequestBody OpenAccountRequest request) {
        return loanService.openAccount(request);
    }

    @PostMapping("/{accountId}/payments")
    public PaymentReceipt pay(@PathVariable String accountId,
                              @RequestBody PaymentRequest request) {
        // 路径变量与请求体以路径变量为准，保证入口一致
        PaymentRequest effective = new PaymentRequest(accountId, request.idempotencyKey(),
                request.amount(), request.effectiveAt(), request.earlySettle());
        return loanService.submitPayment(effective);
    }

    @PostMapping("/{accountId}/fees")
    public AccountSnapshot fee(@PathVariable String accountId,
                               @RequestBody FeeRequest request) {
        return loanService.chargeFee(new FeeRequest(accountId, request.feeId(),
                request.amount(), request.effectiveAt()));
    }

    @PostMapping("/{accountId}/risk/transitions")
    public AccountSnapshot transition(@PathVariable String accountId,
                                      @RequestBody RiskTransitionRequest request) {
        return loanService.transitionRisk(accountId, request);
    }

    @PostMapping("/{accountId}/risk/classifications")
    public AccountSnapshot classify(@PathVariable String accountId,
                                    @RequestBody RiskClassifyRequest request) {
        return loanService.classifyRisk(accountId, request);
    }

    @PostMapping("/{accountId}/write-off")
    public AccountSnapshot writeOff(@PathVariable String accountId,
                                    @RequestBody WriteOffRequest request) {
        return loanService.writeOff(accountId, request);
    }

    @GetMapping("/{accountId}")
    public AccountSnapshot get(@PathVariable String accountId) {
        return loanService.getSnapshot(accountId);
    }

    @GetMapping("/{accountId}/replay")
    public AccountSnapshot replay(@PathVariable String accountId,
                                  @RequestParam("asOf") LocalDateTime asOf) {
        return loanService.replayAsOf(accountId, asOf);
    }
}
