package com.github.h3yow.loanservicingenginejava.domain;

/**
 * 日计息口径：年天数基准。
 */
public enum DayCountConvention {
    ACT_360(360),
    ACT_365_FIXED(365);

    private final int dayBase;

    DayCountConvention(int dayBase) {
        this.dayBase = dayBase;
    }

    public int dayBase() {
        return dayBase;
    }
}
