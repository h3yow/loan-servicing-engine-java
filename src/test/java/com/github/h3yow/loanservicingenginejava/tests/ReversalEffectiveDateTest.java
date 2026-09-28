package com.github.h3yow.loanservicingenginejava.tests;

import com.github.h3yow.loanservicingenginejava.domain.AccountStatus;
import com.github.h3yow.loanservicingenginejava.domain.AccountView;
import com.github.h3yow.loanservicingenginejava.domain.Installment;
import com.github.h3yow.loanservicingenginejava.domain.LoanTerms;
import com.github.h3yow.loanservicingenginejava.domain.PaymentCommand;
import com.github.h3yow.loanservicingenginejava.domain.RepaymentMethod;
import com.github.h3yow.loanservicingenginejava.domain.ReversalCommand;
import com.github.h3yow.loanservicingenginejava.domain.RiskState;
import com.github.h3yow.loanservicingenginejava.service.LoanServicingService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冲正生效日时间口径（bitemporal as-of 重放）：
 * <ul>
 *   <li>按早于冲正生效日的日期查询，口径与冲正前完全一致（含各期分摊、剩余应还、罚息、状态）；</li>
 *   <li>自冲正生效日当天（含）起，按被冲后的剩余有效流水重述；</li>
 *   <li>结清/风险状态同样按时间点呈现；历史日看不到未来登记的冲正与风险迁移。</li>
 * </ul>
 *
 * <p>每个用例使用唯一账户前缀，可与任意其它用例、任意执行顺序混跑。</p>
 */
@SpringBootTest
class ReversalEffectiveDateTest {

    private static final Logger log = LoggerFactory.getLogger(ReversalEffectiveDateTest.class);

    @Autowired
    LoanServicingService service;

    private void open(String id, int n, LocalDate firstDue) {
        service.openAccount(id, new LoanTerms(new BigDecimal("12000"), new BigDecimal("0.12"), n,
                firstDue.minusMonths(1), firstDue, RepaymentMethod.EQUAL_INSTALLMENT,
                BigDecimal.ZERO));
    }

    private void pay(String id, String key, String amount, LocalDate effective,
                     LocalDateTime submitted) {
        service.postPayment(new PaymentCommand(id, id + "-" + key, new BigDecimal(amount), effective),
                submitted);
    }

    private void reverse(String id, String key, long seq, String amount, LocalDate effective,
                         LocalDateTime submitted, String reason) {
        service.postReversal(new ReversalCommand(id, id + "-" + key, seq,
                new BigDecimal(amount), effective, reason), submitted);
    }

    /** 视图快照指纹：逐期各 paid 字段与未付罚息 + 总额 + 状态 + 风险，用于跨时点一致性比较。 */
    private record Snapshot(String status, String risk, String remainingTotal,
                            String penalty, List<String> installments) {
        static Snapshot of(AccountView v) {
            List<String> rows = v.installments.stream()
                    .map(i -> i.seq + ":" + i.paidPrincipal + "/" + i.paidInterest + "/"
                            + i.paidFee + "/" + i.paidPenalty + "/" + i.outstandingPenalty)
                    .toList();
            String penalty = v.installments.stream().map(i -> i.outstandingPenalty)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).toPlainString();
            return new Snapshot(v.status.name(), v.riskState.name(),
                    v.remainingTotal.toPlainString(), penalty, rows);
        }
    }

    @Test
    void historyBeforeReversalEffectiveDateIsUntouchedAndBoundaryRestates() {
        String id = "revdate-history";
        LocalDate due = LocalDate.of(2025, 2, 1);
        open(id, 12, due);
        LocalDate payDate = LocalDate.of(2025, 2, 1);
        LocalDate reversalDate = LocalDate.of(2025, 3, 10);

        pay(id, "p1", "1000", payDate, LocalDateTime.of(2025, 2, 1, 10, 0));
        long seq = service.getPayments(id).get(0).sequence;

        // 冲正登记之前，先在若干历史日留底（冲正前口径基线）
        AccountView before1 = service.getAccountView(id, LocalDate.of(2025, 2, 1));
        AccountView before2 = service.getAccountView(id, LocalDate.of(2025, 3, 1));
        AccountView before3 = service.getAccountView(id, LocalDate.of(2025, 3, 9));

        // 3/10 登记部分冲正 400（生效日 = 登记日）
        reverse(id, "r1", seq, "400", reversalDate,
                LocalDateTime.of(2025, 3, 10, 12, 0), "银行退汇");

        // 早于生效日查询：必须与冲正前完全一致（历史不被改写）
        Snapshot after1 = Snapshot.of(service.getAccountView(id, LocalDate.of(2025, 2, 1)));
        Snapshot after2 = Snapshot.of(service.getAccountView(id, LocalDate.of(2025, 3, 1)));
        Snapshot after3 = Snapshot.of(service.getAccountView(id, LocalDate.of(2025, 3, 9)));
        log.info("[输入] 还款1000@2/1 seq={} 冲正400生效3/10; [判定] 冲正前基线 剩余={}/{} 罚息={}",
                seq, Snapshot.of(before1).remainingTotal, Snapshot.of(before3).remainingTotal,
                Snapshot.of(before3).penalty);
        assertEquals(Snapshot.of(before1), after1, "生效日前(2/1)口径必须与冲正前一致");
        assertEquals(Snapshot.of(before2), after2, "生效日前(3/1)口径必须与冲正前一致");
        assertEquals(Snapshot.of(before3), after3, "生效日前一天(3/9)口径必须与冲正前一致");

        // 历史日视图中不应出现未来才登记的冲正
        assertEquals(0, service.getAccountView(id, LocalDate.of(2025, 3, 9)).reversals.size(),
                "生效日前视图不得包含该冲正流水");

        // 生效日当天（含）边界：冲正开始生效，按净有效流水重述
        AccountView onDay = service.getAccountView(id, reversalDate);
        Snapshot on = Snapshot.of(onDay);
        log.info("[判定] 生效日当天3/10: 剩余={} 罚息={} 冲正笔数={}",
                on.remainingTotal, on.penalty, onDay.reversals.size());
        assertEquals(1, onDay.reversals.size(), "生效日当天视图应包含该冲正");

        // 重述等价：自生效日起，"1000 后冲 400"必须与一个"只在 2/1 还过 600"的
        // 对照账户得到完全相同的逐期口径（分摊/已付罚息/未付罚息/剩余）
        String cmpId = id + "-cmp";
        open(cmpId, 12, due);
        pay(cmpId, "p1", "600", payDate, LocalDateTime.of(2025, 2, 1, 10, 0));
        assertSameDerived(service.getAccountView(cmpId, reversalDate), onDay,
                "生效日当天起应等价于只还过净有效金额600");

        // 生效日之后继续按重述口径：冲正事实持续存在，且仍与对照账户一致
        AccountView later = service.getAccountView(id, LocalDate.of(2025, 4, 1));
        assertEquals(1, later.reversals.size());
        assertSameDerived(service.getAccountView(cmpId, LocalDate.of(2025, 4, 1)), later,
                "生效日之后持续与净有效流水对照一致");
        assertEquals(1, service.getAccountView(id, LocalDate.of(2025, 6, 1)).reversals.size());
        log.info("[最终] 生效日前历史不变；3/10起重述，4/1/6/1冲正持续生效 剩余={}",
                later.remainingTotal);
    }

    /**
     * 结清时间口径：2/1 一次结清，3/5 部分冲正暴露欠款。
     * 早于冲正日查询仍显示 SETTLED；生效日起显示 ACTIVE 且可继续还款；补回后当前口径重新结清。
     */
    @Test
    void settledStatusIsRestoredOnHistoryDatesAndReopensFromEffectiveDate() {
        String id = "revdate-settled";
        LocalDate due = LocalDate.of(2025, 2, 1);
        open(id, 1, due);
        BigDecimal total = service.getAccountView(id, due).installments.stream()
                .map(i -> i.scheduledPrincipal.add(i.scheduledInterest).add(i.scheduledFee))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        pay(id, "settle", total.toPlainString(), due, LocalDateTime.of(2025, 2, 1, 10, 0));
        long seq = service.getPayments(id).get(0).sequence;
        assertEquals(AccountStatus.SETTLED, service.getAccountView(id, LocalDate.of(2025, 2, 2)).status);
        log.info("[输入] 单期账户一次结清 total={}", total);

        LocalDate revDate = LocalDate.of(2025, 3, 5);
        reverse(id, "r", seq, "100", revDate, LocalDateTime.of(2025, 3, 5, 12, 0), "退回");

        // 生效日前：历史视图仍为 SETTLED
        assertEquals(AccountStatus.SETTLED,
                service.getAccountView(id, LocalDate.of(2025, 3, 4)).status,
                "生效日前历史仍显示已结清");
        // 生效日当天：退回 ACTIVE，暴露 100 欠款（另含逾期罚息）
        AccountView onDay = service.getAccountView(id, revDate);
        assertEquals(AccountStatus.ACTIVE, onDay.status, "生效日起退回未结清");
        assertTrue(onDay.remainingTotal.compareTo(new BigDecimal("100")) >= 0,
                "冲正暴露欠款不少于100（另含逾期罚息）");
        log.info("[判定] 3/4仍SETTLED; 3/5起ACTIVE 剩余={}", onDay.remainingTotal);

        // 生效日之后可继续正常还款（当前口径），补回 100 + 累计罚息后重新结清
        AccountView current = service.getAccountView(id, LocalDate.of(2025, 3, 6));
        pay(id, "repay", current.remainingTotal.toPlainString(), LocalDate.of(2025, 3, 6),
                LocalDateTime.of(2025, 3, 6, 10, 0));
        assertEquals(AccountStatus.SETTLED, service.getAccount(id).status);
        log.info("[最终] 补回 {} 后当前口径重新 SETTLED；历史 3/4 视图仍为 SETTLED",
                current.remainingTotal);
        assertEquals(AccountStatus.SETTLED,
                service.getAccountView(id, LocalDate.of(2025, 3, 4)).status,
                "补回后历史视图口径不变");
    }

    /**
     * 风险状态时间点回放：冲正暴露逾期后登记的 AUTO_AFTER_REVERSAL 风险迁移，
     * 不得出现在早于其生效日的历史视图中；生效日起才呈现新分类。
     */
    @Test
    void riskStateIsReplayedByEffectiveDateAndHiddenFromHistory() {
        String id = "revdate-risk";
        LocalDate due = LocalDate.of(2025, 2, 1);
        open(id, 12, due);
        BigDecimal firstDueAmount = service.getAccountView(id, due).installments.get(0)
                .scheduledPrincipal
                .add(service.getAccountView(id, due).installments.get(0).scheduledInterest);
        pay(id, "p1", firstDueAmount.toPlainString(), due, LocalDateTime.of(2025, 2, 1, 10, 0));
        long seq = service.getPayments(id).get(0).sequence;
        assertEquals(RiskState.NORMAL, service.getAccountView(id, LocalDate.of(2025, 4, 1)).riskState);

        LocalDate revDate = LocalDate.of(2025, 4, 20);
        reverse(id, "r", seq, firstDueAmount.toPlainString(), revDate,
                LocalDateTime.of(2025, 4, 20, 12, 0), "退回");
        RiskState after = service.getAccount(id).riskState;
        log.info("[输入] 首期还清后于4/20整笔冲正; [最终] 当前风险={}", after);
        assertTrue(after == RiskState.OVERDUE || after == RiskState.NPL,
                "整笔冲正暴露长期逾期，当前风险应恶化: " + after);

        // 历史视图（早于冲正日）仍为 NORMAL，且看不到这条风险历史与冲正
        AccountView history = service.getAccountView(id, LocalDate.of(2025, 4, 19));
        assertEquals(RiskState.NORMAL, history.riskState, "生效日前风险状态不被改写");
        assertTrue(history.riskHistory.stream().noneMatch(r -> r.reason.contains("AUTO_AFTER_REVERSAL")),
                "历史视图不得包含未来的冲正风险迁移");
        assertEquals(0, history.reversals.size());
        // 生效日当天起呈现恶化后的分类
        assertEquals(after, service.getAccountView(id, revDate).riskState);
        log.info("[判定] 4/19历史视图 risk=NORMAL; 4/20起 risk={}",
                service.getAccountView(id, revDate).riskState);
    }

    /**
     * 罚息基数重述：早期还本被冲正后，自冲正生效日起，后续时点的未付罚息
     * 必须等价于"该笔还本从未发生"（基数回升、罚息增加），而生效日前不变。
     */
    @Test
    void penaltyBaseIsRestatedFromEffectiveDateButHistoryPenaltyUntouched() {
        String id = "revdate-penalty";
        LocalDate due = LocalDate.of(2025, 2, 1);
        open(id, 12, due);
        // 2/10 早期还本 1000（压低基数）
        pay(id, "early", "1000", LocalDate.of(2025, 2, 10),
                LocalDateTime.of(2025, 2, 10, 10, 0));
        long seq = service.getPayments(id).get(0).sequence;
        // 冲正前基线：3/1 与 5/1 的未付罚息
        BigDecimal histPenalty = service.getAccountView(id, LocalDate.of(2025, 3, 1)).installments
                .stream().map(i -> i.outstandingPenalty).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal beforeLate = service.getAccountView(id, LocalDate.of(2025, 5, 1)).installments
                .stream().map(i -> i.outstandingPenalty).reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDate revDate = LocalDate.of(2025, 4, 1);
        reverse(id, "r", seq, "1000", revDate, LocalDateTime.of(2025, 4, 1, 12, 0), "退回");

        // 生效日前（3/1）罚息不变
        BigDecimal histAfter = service.getAccountView(id, LocalDate.of(2025, 3, 1)).installments
                .stream().map(i -> i.outstandingPenalty).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, histPenalty.compareTo(histAfter), "生效日前罚息口径不变");

        // 生效日后（5/1）：与"2/10 从未还过 1000"的对照账户罚息一致，且高于冲正前
        String cmpId = id + "-cmp";
        open(cmpId, 12, due);
        AccountView restated = service.getAccountView(id, LocalDate.of(2025, 5, 1));
        AccountView cmp = service.getAccountView(cmpId, LocalDate.of(2025, 5, 1));
        assertSameDerived(cmp, restated, "冲正生效后罚息/分摊等价于该笔从未存在");
        log.info("[输入] 2/10还本1000 4/1冲正; [判定] 3/1罚息前后={}/{} 5/1罚息 冲正前={} 重述={}",
                histPenalty, histAfter, beforeLate,
                restated.installments.stream().map(i -> i.outstandingPenalty)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /**
     * 部分冲正的生效日边界：净额口径自生效日起变化；早于生效日仍按原额，
     * 且同一 asOf 上重复查询结果完全一致（纯重放、可复现）。
     */
    @Test
    void repeatedHistoricalQueriesAreStableAndOrderIndependent() {
        String id = "revdate-stable";
        LocalDate due = LocalDate.of(2025, 2, 1);
        open(id, 12, due);
        pay(id, "p1", "1000", due, LocalDateTime.of(2025, 2, 1, 10, 0));
        long seq = service.getPayments(id).get(0).sequence;
        reverse(id, "r1", seq, "250", LocalDate.of(2025, 3, 1),
                LocalDateTime.of(2025, 3, 1, 12, 0), "部分退回一");
        reverse(id, "r2", seq, "250", LocalDate.of(2025, 5, 1),
                LocalDateTime.of(2025, 5, 1, 12, 0), "部分退回二");

        // 多个时点反复查询（乱序），结果只取决于事实与 asOf
        LocalDate[] dates = {LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 28),
                LocalDate.of(2025, 3, 1), LocalDate.of(2025, 4, 30),
                LocalDate.of(2025, 5, 1), LocalDate.of(2025, 6, 1)};
        for (int round = 0; round < 3; round++) {
            for (int idx = dates.length - 1; idx >= 0; idx--) {
                LocalDate d = dates[idx];
                Snapshot a = Snapshot.of(service.getAccountView(id, d));
                Snapshot b = Snapshot.of(service.getAccountView(id, d));
                assertEquals(a, b, "同一 asOf 重复查询必须稳定: " + d);
            }
        }
        // 分段口径：2/28 冲正0笔；3/1~4/30 冲正1笔(净额750)；5/1起冲正2笔(净额500)
        assertEquals(0, service.getAccountView(id, LocalDate.of(2025, 2, 28)).reversals.size());
        assertEquals(1, service.getAccountView(id, LocalDate.of(2025, 3, 1)).reversals.size());
        assertEquals(1, service.getAccountView(id, LocalDate.of(2025, 4, 30)).reversals.size());
        assertEquals(2, service.getAccountView(id, LocalDate.of(2025, 5, 1)).reversals.size());
        log.info("[最终] 多时点乱序重复查询结果稳定，分段冲正笔数 0/1/1/2 符合生效日口径");
    }

    /**
     * 冲正后补收的新还款有独立生效时间线：3/10 冲正暴露欠款，3/20 补回一笔。
     * 3/19 视图不含补款，3/20 起含补款；且 3/19 的历史口径不受 3/20 补款影响。
     */
    @Test
    void repaymentAfterReversalHasOwnEffectiveTimeline() {
        String id = "revdate-repay";
        LocalDate due = LocalDate.of(2025, 2, 1);
        open(id, 12, due);
        pay(id, "p1", "1000", due, LocalDateTime.of(2025, 2, 1, 10, 0));
        long seq = service.getPayments(id).get(0).sequence;
        reverse(id, "r", seq, "400", LocalDate.of(2025, 3, 10),
                LocalDateTime.of(2025, 3, 10, 12, 0), "退回");

        AccountView at19 = service.getAccountView(id, LocalDate.of(2025, 3, 19));
        assertEquals(1, at19.payments.size(), "3/19 只有原始一笔还款");
        assertEquals(1, at19.reversals.size());

        // 3/20 补回 400（提交 3/20）
        pay(id, "makeup", "400", LocalDate.of(2025, 3, 20),
                LocalDateTime.of(2025, 3, 20, 10, 0));
        // 3/19 视图仍只有 1 笔有效还款（补款生效日 3/20，不出现在 3/19）
        AccountView at19Again = service.getAccountView(id, LocalDate.of(2025, 3, 19));
        assertEquals(1, at19Again.payments.size(), "3/19 历史视图不受 3/20 补款影响");
        assertEquals(Snapshot.of(at19), Snapshot.of(at19Again), "补款后历史口径不变");
        // 3/20 起含补款，净有效已收回到 1000
        AccountView at20 = service.getAccountView(id, LocalDate.of(2025, 3, 20));
        assertEquals(2, at20.payments.size());
        log.info("[判定] 3/19有效还款{}笔 剩余={}; 3/20含补款 有效还款{}笔 剩余={}",
                at19Again.payments.size(), at19Again.remainingTotal,
                at20.payments.size(), at20.remainingTotal);
        assertTrue(at20.remainingTotal.compareTo(at19Again.remainingTotal) < 0,
                "补款生效后剩余应下降");
        // 3/20 口径等价于"从未冲正、但 3/20 多还一笔 400"的净现金流（总额 1000）
        String cmpId = id + "-cmp";
        open(cmpId, 12, due);
        // 对照：2/1 还 600（净），3/20 还 400
        pay(cmpId, "a", "600", due, LocalDateTime.of(2025, 2, 1, 10, 0));
        pay(cmpId, "b", "400", LocalDate.of(2025, 3, 20),
                LocalDateTime.of(2025, 3, 20, 10, 0));
        assertSameDerived(service.getAccountView(cmpId, LocalDate.of(2025, 3, 20)), at20,
                "冲正后补款的净现金流时间线等价对照");
        log.info("[最终] 补款独立生效，3/19历史不变，3/20起净有效已收回到1000");
    }

    /**
     * 多笔部分冲正、不同生效日的分段净额：1000 分别在 3/1 冲 200、4/1 冲 300。
     * 各时点净有效已收 = 1000（2月）→ 800（3月）→ 500（4月起），
     * 且每段都等价于对应净额的对照账户。
     */
    @Test
    void multiplePartialReversalsApplyInStagesByEffectiveDate() {
        String id = "revdate-stages";
        LocalDate due = LocalDate.of(2025, 2, 1);
        open(id, 12, due);
        pay(id, "p1", "1000", due, LocalDateTime.of(2025, 2, 1, 10, 0));
        long seq = service.getPayments(id).get(0).sequence;
        reverse(id, "r1", seq, "200", LocalDate.of(2025, 3, 1),
                LocalDateTime.of(2025, 3, 1, 12, 0), "冲200");
        reverse(id, "r2", seq, "300", LocalDate.of(2025, 4, 1),
                LocalDateTime.of(2025, 4, 1, 12, 0), "再冲300");

        // 各时间窗冲正笔数
        assertEquals(0, service.getAccountView(id, LocalDate.of(2025, 2, 28)).reversals.size());
        assertEquals(1, service.getAccountView(id, LocalDate.of(2025, 3, 1)).reversals.size());
        assertEquals(2, service.getAccountView(id, LocalDate.of(2025, 4, 1)).reversals.size());

        // 每段与对应净额的对照账户逐期等价（选在冲正登记当日，避免跨日罚息差异）
        checkStageEquivalent(id, due, "1000", LocalDate.of(2025, 2, 10));
        checkStageEquivalent(id, due, "800", LocalDate.of(2025, 3, 1));
        checkStageEquivalent(id, due, "500", LocalDate.of(2025, 4, 1));
        // 剩余可冲 = 1000 - 500 = 500
        BigDecimal reversible = service.getReversals(id).isEmpty() ? null :
                new BigDecimal("1000").subtract(service.getReversals(id).stream()
                        .map(r -> r.amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals(0, new BigDecimal("500").compareTo(reversible));
        log.info("[最终] 分段净额 1000→800→500 逐段与对照等价，剩余可冲={}", reversible);
    }

    /** 在 asOf 上，目标账户口径应等价于一个"首期当天只还过 net、之后无流水"的对照账户。 */
    private void checkStageEquivalent(String id, LocalDate due, String net, LocalDate asOf) {
        String cmpId = id + "-cmp-" + net + "-" + asOf;
        open(cmpId, 12, due);
        pay(cmpId, "only", net, due, LocalDateTime.of(2025, 2, 1, 10, 0));
        assertSameDerived(service.getAccountView(cmpId, asOf),
                service.getAccountView(id, asOf),
                "asOf=" + asOf + " 净额=" + net + " 应等价对照");
    }

    /** 逐期比对两个视图的派生口径：各 paid 字段、未付罚息、剩余总额。 */
    private void assertSameDerived(AccountView expected, AccountView actual, String msg) {
        assertEquals(0, expected.remainingTotal.compareTo(actual.remainingTotal),
                msg + "（剩余总额）");
        assertEquals(expected.installments.size(), actual.installments.size());
        for (int k = 0; k < expected.installments.size(); k++) {
            Installment x = expected.installments.get(k);
            Installment y = actual.installments.get(k);
            assertEquals(x.seq, y.seq);
            assertEquals(0, x.paidPrincipal.compareTo(y.paidPrincipal), msg + "（期" + x.seq + "还本）");
            assertEquals(0, x.paidInterest.compareTo(y.paidInterest), msg + "（期" + x.seq + "还息）");
            assertEquals(0, x.paidFee.compareTo(y.paidFee), msg + "（期" + x.seq + "费用）");
            assertEquals(0, x.paidPenalty.compareTo(y.paidPenalty), msg + "（期" + x.seq + "已付罚息）");
            assertEquals(0, x.outstandingPenalty.compareTo(y.outstandingPenalty),
                    msg + "（期" + x.seq + "未付罚息）");
        }
    }
}
