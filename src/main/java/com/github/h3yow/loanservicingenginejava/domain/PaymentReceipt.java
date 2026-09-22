package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 还款入账回执（由重放确定性计算得出，重复提交返回同一回执）。 */
public record PaymentReceipt(String idempotencyKey,
                             String accountId,
                             BigDecimal amount,
                             LocalDateTime effectiveAt,
                             List<AllocationLine> allocations,
                             BigDecimal allocated,
                             boolean earlySettlement,
                             BigDecimal waivedInterest) {
}
