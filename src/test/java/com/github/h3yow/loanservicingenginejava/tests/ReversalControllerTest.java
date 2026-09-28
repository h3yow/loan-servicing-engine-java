package com.github.h3yow.loanservicingenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 冲正 HTTP 入口：发起冲正、幂等重复、超额冲正、目标不存在、关账后拒绝。
 *
 * <p>用例与执行顺序无关：被冲还款序号从还款响应的 {@code paymentSequence} 动态解析，
 * 不依赖任何全局序号初值；账户与键均用唯一前缀，避免与同上下文其它用例互相干扰。</p>
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
        // 唯一前缀：即便与其它接口用例共享 Spring 上下文/内存账本，也不发生键或账户碰撞
        String id = "web-rev-" + System.nanoTime();
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
        MvcResult payResult = mvc.perform(post("/api/loans/accounts/" + id + "/payments")
                        .contentType(MediaType.APPLICATION_JSON).content(json(pay)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentSequence").isNumber())
                .andReturn();
        // 动态取本账户这笔还款的全局序号：不写死 1，与其它用例的执行顺序无关
        JsonNode payJson = om.readTree(payResult.getResponse().getContentAsString());
        long paymentSeq = payJson.get("paymentSequence").asLong();
        log.info("[入口] 还款成功 paymentSequence={}", paymentSeq);
        String reversalUrl = "/api/loans/accounts/" + id + "/payments/" + paymentSeq + "/reversals";

        // 部分冲正成功
        mvc.perform(post(reversalUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalBody(id + "-wrev", "400", "2025-02-05", "银行退回")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.paymentSequence").value(paymentSeq))
                .andExpect(jsonPath("$.reversedAmount").value(400))
                .andExpect(jsonPath("$.remainingReversible").value(600));
        log.info("[入口] 部分冲正成功: 400/1000");

        // 同键同内容重复提交 => duplicate=true
        mvc.perform(post(reversalUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalBody(id + "-wrev", "400", "2025-02-05", "银行退回")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));
        log.info("[入口] 冲正重复提交 duplicate=true 不重复冲账");

        // 再冲 700，累计 1100 > 1000 => 422 REVERSAL_EXCEEDS_PAYMENT
        mvc.perform(post(reversalUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalBody(id + "-wrev2", "700", "2025-02-06", "x")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value(RejectReason.REVERSAL_EXCEEDS_PAYMENT.name()));
        log.info("[入口] 超额冲正 => 422 reason={}", RejectReason.REVERSAL_EXCEEDS_PAYMENT);

        // 冲不存在的还款 => 422 REVERSAL_TARGET_NOT_FOUND
        mvc.perform(post("/api/loans/accounts/" + id + "/payments/999999/reversals")
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
        mvc.perform(post(reversalUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalBody(id + "-wrev4", "10", "2025-02-07", "x")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value(RejectReason.ACCOUNT_NOT_POSTABLE.name()));
        log.info("[入口] CLOSED 后冲正 => 422 reason={}", RejectReason.ACCOUNT_NOT_POSTABLE);
    }

    /**
     * 生效日时间口径（HTTP 端到端）：同一账户，冲正生效日之前 GET 历史视图
     * 不含该冲正、剩余口径与冲正前完全一致；生效日当天（含）起重述（冲正笔数出现、
     * 剩余随被冲金额回升）。提交发生在真实当前日，冲正生效日取当前日附近。
     */
    @Test
    void reversalTakesEffectOnlyFromEffectiveDateOverHttp() throws Exception {
        String id = "web-rev-date-" + System.nanoTime();
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate firstDue = today.minusDays(40).withDayOfMonth(1);
        Map<String, Object> open = new LinkedHashMap<>();
        open.put("principal", "12000");
        open.put("annualRate", "0.12");
        open.put("installmentCount", 12);
        open.put("originationDate", firstDue.minusMonths(1).toString());
        open.put("firstDueDate", firstDue.toString());
        open.put("method", "EQUAL_INSTALLMENT");
        open.put("feeRate", "0");
        mvc.perform(post("/api/loans/accounts/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content(json(open)))
                .andExpect(status().isOk());

        // 还款生效日 = 首期到期日
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("idempotencyKey", id + "-pay");
        pay.put("amount", "1000");
        pay.put("effectiveDate", firstDue.toString());
        MvcResult payResult = mvc.perform(post("/api/loans/accounts/" + id + "/payments")
                        .contentType(MediaType.APPLICATION_JSON).content(json(pay)))
                .andExpect(status().isOk()).andReturn();
        long paymentSeq = om.readTree(payResult.getResponse().getContentAsString())
                .get("paymentSequence").asLong();

        // 冲正生效日 = 昨天；取冲正前"昨天"与"前天"两个历史基线
        String yesterday = today.minusDays(1).toString();
        String twoDaysAgo = today.minusDays(2).toString();
        MvcResult before = mvc.perform(get("/api/loans/accounts/" + id)
                        .param("asOf", yesterday))
                .andExpect(status().isOk()).andReturn();
        String beforeRemaining = om.readTree(before.getResponse().getContentAsString())
                .get("remainingTotal").asText();

        // 昨天登记冲正 400（回溯一个业务日）
        mvc.perform(post("/api/loans/accounts/" + id + "/payments/" + paymentSeq + "/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalBody(id + "-rev", "400", yesterday, "退汇")))
                .andExpect(status().isOk());

        // 早于生效日（前天）：不含冲正；昨天口径现在已重述
        mvc.perform(get("/api/loans/accounts/" + id).param("asOf", twoDaysAgo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversals.length()").value(0));
        // 生效日当天（昨天，含）：冲正出现并按净流水重述，剩余较冲正前回升（被冲400）
        mvc.perform(get("/api/loans/accounts/" + id).param("asOf", yesterday))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversals.length()").value(1));
        MvcResult after = mvc.perform(get("/api/loans/accounts/" + id).param("asOf", yesterday))
                .andExpect(status().isOk()).andReturn();
        BigDecimal beforeValue = new BigDecimal(beforeRemaining);
        BigDecimal afterRemaining = new BigDecimal(om.readTree(after.getResponse().getContentAsString())
                .get("remainingTotal").asText());
        // 重述后剩余应比冲正前高出接近 400（允许跨日罚息尾差，下限取 390）
        org.junit.jupiter.api.Assertions.assertTrue(
                afterRemaining.subtract(beforeValue).compareTo(new BigDecimal("390")) >= 0,
                "生效日起剩余应随被冲金额回升: before=" + beforeRemaining + " after=" + afterRemaining);
        // 今天：冲正持续生效
        mvc.perform(get("/api/loans/accounts/" + id).param("asOf", today.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversals.length()").value(1));
        log.info("[入口] 生效日时间口径: 生效日前剩余={} 不含冲正；生效日起含1笔冲正 剩余={}（回升≈400）",
                beforeRemaining, afterRemaining);
    }
}
