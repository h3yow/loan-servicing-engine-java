package com.github.h3yow.loanservicingenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 冲正 HTTP 入口的顺序稳定性：每个场景用随机账户与随机幂等键独立建立，
 * 不依赖任何全局序号/其它用例留下的状态。单独跑、整批跑、换顺序跑结论一致。
 *
 * <p>覆盖：开户→还款→冲正→"冲正生效日前/当天"查询口径、幂等重复、流水按账户序号定位。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReversalControllerOrderTest {

    private static final Logger log = LoggerFactory.getLogger(ReversalControllerOrderTest.class);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private String json(Map<String, Object> m) throws Exception {
        return om.writeValueAsString(m);
    }

    private JsonNode body(org.springframework.test.web.servlet.MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString());
    }

    /** 一个完全自包含的场景，可在任意执行位置、任意顺序下运行。 */
    private void runOneScenario(String tag) throws Exception {
        String id = "web-order-" + tag + "-" + UUID.randomUUID();
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

        String payKey = id + "-pay";
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("idempotencyKey", payKey);
        pay.put("amount", "1000");
        pay.put("effectiveDate", "2025-02-01");
        mvc.perform(post("/api/loans/accounts/" + id + "/payments")
                        .contentType(MediaType.APPLICATION_JSON).content(json(pay)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));

        // 生效日前查询：还款仍全额有效
        JsonNode before = body(mvc.perform(get("/api/loans/accounts/" + id)
                        .param("asOf", "2025-02-04"))
                .andExpect(status().isOk()).andReturn());
        BigDecimal scheduledBefore = new BigDecimal(before.get("remainingPrincipal").asText())
                .add(new BigDecimal(before.get("remainingInterest").asText()))
                .add(new BigDecimal(before.get("remainingFee").asText()));

        // 对"该账户第 1 笔还款"部分冲正 400，2025-02-05 生效
        String revKey = id + "-rev";
        Map<String, Object> rev = new LinkedHashMap<>();
        rev.put("idempotencyKey", revKey);
        rev.put("amount", "400");
        rev.put("effectiveDate", "2025-02-05");
        rev.put("reason", "银行退回");
        mvc.perform(post("/api/loans/accounts/" + id + "/payments/1/reversals")
                        .contentType(MediaType.APPLICATION_JSON).content(json(rev)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.paymentSequence").value(1))
                .andExpect(jsonPath("$.reversedAmount").value(400))
                .andExpect(jsonPath("$.remainingReversible").value(600));

        // 幂等重复
        mvc.perform(post("/api/loans/accounts/" + id + "/payments/1/reversals")
                        .contentType(MediaType.APPLICATION_JSON).content(json(rev)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        // 生效日前：历史不变（计划未还口径）
        JsonNode pre = body(mvc.perform(get("/api/loans/accounts/" + id)
                        .param("asOf", "2025-02-04"))
                .andExpect(status().isOk()).andReturn());
        BigDecimal scheduledPre = new BigDecimal(pre.get("remainingPrincipal").asText())
                .add(new BigDecimal(pre.get("remainingInterest").asText()))
                .add(new BigDecimal(pre.get("remainingFee").asText()));
        assertEquals(0, scheduledBefore.compareTo(scheduledPre),
                tag + ": 冲正生效日前计划未还必须与冲正前一致");

        // 生效日当天：净额 600，计划未还回升 400（罚息按日另计，不在此断言内）
        JsonNode on = body(mvc.perform(get("/api/loans/accounts/" + id)
                        .param("asOf", "2025-02-05"))
                .andExpect(status().isOk()).andReturn());
        BigDecimal scheduledOn = new BigDecimal(on.get("remainingPrincipal").asText())
                .add(new BigDecimal(on.get("remainingInterest").asText()))
                .add(new BigDecimal(on.get("remainingFee").asText()));
        assertEquals(0, scheduledBefore.add(new BigDecimal("400")).compareTo(scheduledOn),
                tag + ": 生效日当天计划未还应回升400");

        log.info("[输入] {} 还款1000/冲正400(2/5生效); [判定] 生效日前计划未还={} 当天计划未还={} 当天未付罚息={}; [最终] 计划口径差=400 且幂等不重复",
                tag, scheduledBefore, scheduledOn, on.get("outstandingPenalty").asText());
    }

    @Test
    void reversalScenarioIsSelfContainedAcrossBatchRun() throws Exception {
        runOneScenario("a");
    }

    @Test
    void reversalScenarioIsStableWhenRunAgain() throws Exception {
        runOneScenario("b");
    }

    @Test
    void reversalScenarioIsStableWhenRunAThirdTime() throws Exception {
        runOneScenario("c");
    }
}
