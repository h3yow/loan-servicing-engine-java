package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;

/**
 * 贷款合同要素。
 *
 * @param principal       放款本金
 * @param annualRate      年利率（如 0.12 表示 12%）
 * @param termMonths      期数

 * @param dayCountBasis   日计息口径：ACT/365 或 ACT/360
 */
public record Terms(BigDecimal principal,
                    BigDecimal annualRate,
                    int termMonths,
                    String dayCountBasis) {
}
