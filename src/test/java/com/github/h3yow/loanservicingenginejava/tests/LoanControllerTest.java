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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 各 HTTP 入口一致性：开户、还款（重复/冲突/超额）、风险迁移（非法）、查询。
 * 错误原因须以可区分的 reason 码返回。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoanControllerTest {

    private static final Logger log = LoggerFactory.getLogger(LoanControllerTest.class);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private String openBody(String method) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("principal", "12000");
        m.put("annualRate", "0.12");
        m.put("installmentCount", 12);
        m.put("originationDate", "2025-01-01");
        m.put("firstDueDate", "2025-02-01");
        m.put("method", method);
        m.put("feeRate", "0");
        return toJson(m);
    }

    private String paymentBody(String key, String amount, String effectiveDate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idempotencyKey", key);
        m.put("amount", amount);
        m.put("effectiveDate", effectiveDate);
        return toJson(m);
    }

    private String toJson(Map<String, Object> m) {
        try {
            return om.writeValueAsString(m);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void openPayQueryAndErrorCodes() throws Exception {
        String id = "web-1";
        mvc.perform(post("/api/loans/accounts/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content(openBody("EQUAL_INSTALLMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        log.info("[入口] 开户成功");

        mvc.perform(post("/api/loans/accounts/" + id + "/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("wkey", "1063", "2025-02-01")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));
        log.info("[入口] 首次还款成功");

        mvc.perform(post("/api/loans/accounts/" + id + "/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("wkey", "1063", "2025-02-01")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));
        log.info("[入口] 重复还款幂等返回 duplicate=true 不重复扣款");

        mvc.perform(post("/api/loans/accounts/" + id + "/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("wkey", "999", "2025-02-01")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value(RejectReason.IDEMPOTENCY_KEY_CONFLICT.name()));
        log.info("[入口] 同键不同金额 => 422 reason={}", RejectReason.IDEMPOTENCY_KEY_CONFLICT);

        mvc.perform(post("/api/loans/accounts/" + id + "/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("over", "9999999", "2025-02-01")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value(RejectReason.OVERPAYMENT.name()));
        log.info("[入口] 超额 => 422 reason={}", RejectReason.OVERPAYMENT);

        mvc.perform(post("/api/loans/accounts/" + id + "/risk-transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"target":"NPL","effectiveDate":"2025-06-01","reason":"t"}
                                """))
                .andExpect(status().isOk());
        mvc.perform(post("/api/loans/accounts/" + id + "/risk-transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"target":"NORMAL","effectiveDate":"2025-06-02","reason":"t"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value(RejectReason.ILLEGAL_RISK_TRANSITION.name()));
        log.info("[入口] NPL->NORMAL => 422 reason={}", RejectReason.ILLEGAL_RISK_TRANSITION);

        mvc.perform(get("/api/loans/accounts/" + id).param("asOf", "2025-06-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingTotal").exists())
                .andExpect(jsonPath("$.outstandingPenalty").exists())
                .andExpect(jsonPath("$.riskState").value("NPL"));
        log.info("[入口] 查询视图含剩余/罚息/风险状态");

        mvc.perform(get("/api/loans/accounts/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value(RejectReason.ACCOUNT_NOT_FOUND.name()));
        log.info("[入口] 未知账户 => 404 reason={}", RejectReason.ACCOUNT_NOT_FOUND);
    }

    @Test
    void reversalEndpointIdempotentAndTraceable() throws Exception {
        String id = "web-rev";
        mvc.perform(post("/api/loans/accounts/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content(openBody("EQUAL_INSTALLMENT")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/loans/accounts/" + id + "/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("webrev-pay", "1063", "2025-02-01")))
                .andExpect(status().isOk());
        // 流水序号为全局递增，从账户视图取本笔还款序号
        Integer seqValue = om.readTree(mvc.perform(get("/api/loans/accounts/" + id))
                        .andReturn().getResponse().getContentAsString())
                .get("payments").get(0).get("sequence").asInt();
        long seq = seqValue.longValue();
        String body = """
                {"paymentSequence":%d,"idempotencyKey":"webrev-key","amount":"300",
                 "effectiveDate":"2025-02-05","reason":"银行退回"}
                """.formatted(seq);

        mvc.perform(post("/api/loans/accounts/" + id + "/reversals")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.targetPaymentSequence").value((int) seq))
                .andExpect(jsonPath("$.cumulativeReversed").value(300));
        log.info("[入口] 首次冲正成功: 目标seq={} 累计冲正300", seq);

        mvc.perform(post("/api/loans/accounts/" + id + "/reversals")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.cumulativeReversed").value(300));
        log.info("[入口] 重复冲正幂等返回 duplicate=true 不重复冲账");

        // 超额冲正 => 可区分错误码
        String over = """
                {"paymentSequence":%d,"idempotencyKey":"webrev-over","amount":"800",
                 "effectiveDate":"2025-02-06","reason":"x"}
                """.formatted(seq);
        mvc.perform(post("/api/loans/accounts/" + id + "/reversals")
                        .contentType(MediaType.APPLICATION_JSON).content(over))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value(RejectReason.REVERSAL_EXCEEDS_PAYMENT.name()));
        log.info("[入口] 超额冲正(300+800>1063) => 422 reason={}",
                RejectReason.REVERSAL_EXCEEDS_PAYMENT);
    }
}
