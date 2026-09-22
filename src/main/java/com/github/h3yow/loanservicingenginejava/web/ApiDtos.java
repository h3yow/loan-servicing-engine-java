package com.github.h3yow.loanservicingenginejava.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 各入口的请求/响应 DTO 集合。 */
public final class ApiDtos {

    private ApiDtos() {
    }

    public record OpenAccountRequest(String accountId,
                                     BigDecimal principal,
                                     BigDecimal annualRate,
                                     Integer termMonths,
                                     LocalDate firstDueDate,
                                     String dayCountBasis,
                                     /** 开户/放款时间（可补录历史账户）；为空取当前时间。 */
                                     LocalDateTime openedAt) {
    }

    public record PaymentRequest(String accountId,
                                 String idempotencyKey,
                                 BigDecimal amount,
                                 /** 补录/回溯入账的业务生效时间；为空表示按当前时间与日切规则计算。 */
                                 LocalDateTime effectiveAt,
                                 /** true 表示提前结清，金额须等于结清报价（到期日之后的利息被免除）。 */
                                 boolean earlySettle) {
    }

    public record FeeRequest(String accountId,
                             String feeId,
                             BigDecimal amount,
                             LocalDateTime effectiveAt) {
    }

    public record RiskTransitionRequest(String toState,
                                        String ruleVersion,
                                        String reason,
                                        LocalDateTime effectiveAt) {
    }

    public record RiskClassifyRequest(String ruleVersion,
                                      String reason,
                                      LocalDateTime effectiveAt) {
    }

    public record WriteOffRequest(String reason,
                                  LocalDateTime effectiveAt) {
    }
}
