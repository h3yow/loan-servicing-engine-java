package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;

/** 单笔还款对某一部分的分摊明细。 */
public record AllocationLine(ComponentType component,
                             int periodNo,
                             BigDecimal amount) {
}
