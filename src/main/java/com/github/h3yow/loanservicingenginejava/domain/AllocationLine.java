package com.github.h3yow.loanservicingenginejava.domain;

import java.math.BigDecimal;

/**
 * 单笔还款的一条分摊明细。
 */
public final class AllocationLine {
    public final int installmentSeq;
    public final AllocationBucket bucket;
    public final BigDecimal amount;

    public AllocationLine(int installmentSeq, AllocationBucket bucket, BigDecimal amount) {
        this.installmentSeq = installmentSeq;
        this.bucket = bucket;
        this.amount = amount;
    }
}
