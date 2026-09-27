package com.github.h3yow.loanservicingenginejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.loanservicingenginejava.domain.RejectReason;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 冲正 HTTP 入口：发起冲正、幂等重复、超额冲正、目标不存在、关账后拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReversalControllerTest {

    private static final Logger log = LoggerFactory.getLogger(ReversalControllerTest.class);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private String json(Map<String, Object> m) {
        try {
            return om.writeValueAsString(m);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String reversalBody(String key, String amount, String date, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idempotencyKey", key);
        m.put("amount", amount);
        m.put("effectiveDate", date);
        m.put("reason", reason);
        return json(m);
    }

    @Test
    void reversalEndpointsAndErrorCodes() throws Exception {
        String id = "web-rev-1";
        Map<String, Object> open = new LinkedHashMap<>();
        open.put("principal", "12000");
        open.put("annualRate", "0.12");
        open.put("installmentCount", 12);
        open.put("originationDate", "2025-01-01");
        open.put("firstDueDate", "2025-02-01");
        open.put("method", "EQUAL_INSTALLMENT");
        open.put("feeRate", "0");
        mvc.perform(post("/api/loans/accounts/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content(json(open)))
                .andExpect(status().isOk());

        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("idempotencyKey", id + "-wpay");
        pay.put("amount", "1000");
        pay.put("effectiveDate", "2025-02-01");
        mvc.perform(post("/api/loans/accounts/" + id + "/payments")
                        .contentType(MediaType.APPLICATION_JSON).content(json(pay)))
                .andExpect(status().isOk());

        // 部分冲正成功
        mvc.perform(post("/api/loans/accounts/" + id + "/payments/1/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalBody(id + "-wrev", "400", "2025-02-05", "银行退回")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.paymentSequence").value(1))
                .andExpect(jsonPath("$.reversedAmount").value(400))
                .andExpect(jsonPath("$.remainingReversible").value(600));
        log.info("[入口] 部分冲正成功: 400/1000");

        // 同键同内容重复提交 => duplicate=true
        mvc.perform(post("/api/loans/accounts/" + id + "/payments/1/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalBody(id + "-wrev", "400", "2025-02-05", "银行退回")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));
        log.info("[入口] 冲正重复提交 duplicate=true 不重复冲账");

        // 再冲 700，累计 1100 > 1000 => 422 REVERSAL_EXCEEDS_PAYMENT
        mvc.perform(post("/api/loans/accounts/" + id + "/payments/1/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalBody(id + "-wrev2", "700", "2025-02-06", "x")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value(RejectReason.REVERSAL_EXCEEDS_PAYMENT.name()));
        log.info("[入口] 超额冲正 => 422 reason={}", RejectReason.REVERSAL_EXCEEDS_PAYMENT);

        // 冲不存在的还款 => 422 REVERSAL_TARGET_NOT_FOUND
        mvc.perform(post("/api/loans/accounts/" + id + "/payments/999/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalBody(id + "-wrev3", "10", "2025-02-06", "x")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value(RejectReason.REVERSAL_TARGET_NOT_FOUND.name()));
        log.info("[入口] 目标还款不存在 => 422 reason={}", RejectReason.REVERSAL_TARGET_NOT_FOUND);

        // 关账后资金操作拒绝
        mvc.perform(post("/api/loans/accounts/" + id + "/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"年终结清关账"}
                                """))
                .andExpect(status().isOk());
        mvc.perform(post("/api/loans/accounts/" + id + "/payments/1/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalBody(id + "-wrev4", "10", "2025-02-07", "x")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value(RejectReason.ACCOUNT_NOT_POSTABLE.name()));
        log.info("[入口] CLOSED 后冲正 => 422 reason={}", RejectReason.ACCOUNT_NOT_POSTABLE);
    }
}
