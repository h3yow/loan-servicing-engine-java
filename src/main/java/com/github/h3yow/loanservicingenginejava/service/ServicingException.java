package com.github.h3yow.loanservicingenginejava.service;

import com.github.h3yow.loanservicingenginejava.domain.RejectReason;

/**
 * 业务异常：携带可区分的拒绝原因。
 */
public class ServicingException extends RuntimeException {

    private final RejectReason reason;

    public ServicingException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
