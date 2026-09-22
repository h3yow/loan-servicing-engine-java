package com.github.h3yow.loanservicingenginejava.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 幂等键登记：记录首次提交的请求指纹，用于识别同键冲突。 */
public record IdempotencyRecord(String key,
                                String accountId,
                                BigDecimal amount,
                                LocalDateTime effectiveAt,
                                LocalDateTime firstSeenAt) {
}
