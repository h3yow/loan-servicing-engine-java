package com.github.h3yow.loanservicingenginejava.engine;

import com.github.h3yow.loanservicingenginejava.config.EngineProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 业务日切：根据配置的截止时间（loan.engine.business-cutoff，HH:mm）
 * 把提交时间换算为业务生效时间。
 * <p>
 * 提交时间晚于（含）日切点时顺延到下一自然日同一时刻；
 * 早于日切点时按提交时间。默认 00:00 表示不做顺延。
 */
@Component
public class BusinessCalendar {

    private final EngineProperties properties;

    public BusinessCalendar(EngineProperties properties) {
        this.properties = properties;
    }

    public LocalDateTime effectiveTime(LocalDateTime submittedAt) {
        LocalTime cutoff = LocalTime.parse(properties.getBusinessCutoff());
        if (cutoff.equals(LocalTime.MIDNIGHT)) {
            return submittedAt;
        }
        if (!submittedAt.toLocalTime().isBefore(cutoff)) {
            return submittedAt.toLocalDate().plusDays(1).atTime(submittedAt.toLocalTime());
        }
        return submittedAt;
    }
}
