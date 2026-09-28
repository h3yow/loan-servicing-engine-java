# 本地贷款服务与还款入账流程（Loan Servicing Engine）

纯本地、无外部依赖的贷款账本服务（Spring Boot + 内存账本）。支持还款计划生成、
还款入账（费用/利息/本金分摊）、**还款冲正（整笔/部分、按生效日回溯重算）**、
逾期判定、罚息累计、带版本的风险状态流转。
所有派生口径（各期已还、罚息、余额、分摊明细）都由

> **计划（事实） + 还款流水（事实）**

按生效日期**重放**得到，因此回溯/补录后自动重新计算，且重放相同输入必然得到相同结果。

---

## 1. 分摊顺序（Payment Allocation Order）

一笔还款**按期号升序（先到期先冲抵）逐期处理**；每期内部按以下固定顺序冲抵：

1. **罚息 PENALTY**
2. **费用 FEE**
3. **利息 INTEREST**
4. **本金 PRINCIPAL**

- 允许**部分还款**：金额在某个桶内用尽可能冲抵，剩余顺序继续。
- 允许**提前结清**：未来到期期次也可被冲抵（其罚息恒为 0，仅冲抵计划费用/利息/本金），
  一次性还清全部剩余后账户置为 `SETTLED`。
- **超额还款被拒绝**（`OVERPAYMENT`），不产生溢缴款；因此每笔还款都能被完整、唯一地分摊。
- 金额按 2 位小数（HALF_UP）处理；计划末期吸收尾差，保证计划本金之和恒等于放款本金。

分摊明细不是独立事实：它是"计划 + 流水"的派生物。补录早期还款后，
**历史还款的分摊也会在重放时按生效日自动重新计算**，从而提交顺序不影响最终结论。

---

## 2. 计息、罚息与宽限规则（Interest / Penalty / Grace）

### 还款计划

- 期利率 = 年利率 / 12（按月还款，到期日 = 首期到期日按月递增）。
- 等额本息：每期还款额 = `P·r·(1+r)^n / ((1+r)^n − 1)`；零利率退化为 `P/n`。
- 等额本金：每期本金 `P/n`，当期利息 = 期初剩余本金 × 期利率。
- 费用：一次性费率（按本金）均摊到各期，末期吸收尾差。

### 逾期判定

- 到期日 `D`，宽限期 `G` 天：宽限截止日为 `D + G`，**截止日当天仍不算逾期**，
  从 `D + G + 1` 起算，逾期天数 = `asOf − (D + G)`。
- 某期计划欠款（本金+利息+费用，不含罚息）已全部清偿后，不再计逾期。
- 可选**工作日顺延**：截止日落在周六/周日时顺延至下一工作日再判定（见配置项）。

### 罚息累计

- 每期罚息**起息日** = `到期日 + 宽限期 + 1`。
- 计息**基数** = 该期当时未还的计划本金；按**日单利**累计：
  `基数 × 年罚息利率 × 天数 / 年基准天数`。
- 日计息口径（年基准天数）可选：`ACT_365_FIXED`（365，默认）或 `ACT_360`（360）。
- **还款日口径**：还款日 `D` 还本后，`D` 当天仍按还本前基数计息，新基数自 `D+1` 起生效；
  宽限期内（起息日前）还本直接调低初始基数。
- **非增量、纯重算**：罚息每次都从计划与流水整体重放重算（非逐日落库累加），
  故补录/回溯后罚息与逾期状态按生效日重算，**绝不重复累计**。
  实现以"还款生效日"为分段点逐段计息，并用独立的"逐日基准"对照测试验证分段公式
  （覆盖起息前还本、起息当天、asOf 当天还本、asOf 之后还本、多段等边界）。

### 业务日切

- 当请求未显式给出 `effectiveDate` 时，按 `cutoff-hour` 归属业务日：
  提交时刻小时 `>= cutoff-hour` 归属当日，否则归属前一自然日。
- 显式 `effectiveDate` 不得晚于提交日期（否则 `VALIDATION_ERROR`），以支持补录/回溯而非未来入账。

### 守恒恒等式（任意时刻成立）

```
计划总额(本金+利息+费用) + 应计罚息毛额
        = 净已收(还款总额 − 已生效冲正总额) + 剩余应还(计划未还本金/利息/费用 + 应计未付罚息)
```

"已生效"以守恒校验时点（落账后=全部已登记事实的最大生效日；历史查询=asOf）为准，
因此该恒等式在冲正生效日之前的历史时点同样成立（那时未来冲正不计入）。

每笔还款入账或冲正后服务都会执行该守恒校验与逐期非负/无超额冲抵校验，失败立即抛出
`IllegalStateException`（属于程序不变量破坏，而非业务拒绝）。

---

## 3. 还款冲正（Payment Reversal）

适用场景：还款记错金额、记错账户，或款项后来被银行退回，需要把**已经入账的还款撤掉**，
而不是人工再补一笔反向流水去凑。

### 语义与生效日期口径（bitemporal：事实不删、历史不改）

- 冲正**不是一笔新的资金流水**，而是对原入账事实的**更正**：
  任一时点 `asOf` 的重放输入为
  > **计划（事实） + 还款流水（事实） − 生效日不晚于 asOf 的冲正流水（事实）**
- **冲正有自己的生效日 `effectiveDate`，自生效日当天（含）起才生效**：
  - 按 **`asOf < 冲正生效日`** 查询：该冲正完全不参与，分摊、各期已还、剩余应还、罚息、
    逾期天数、账户状态与风险状态都与**冲正之前完全一致**（含被冲还款的原始分摊明细）；
  - 按 **`asOf >= 冲正生效日`** 查询（生效日当天即适用）：被冲金额**不再算作已收**，
    系统按"原还款金额 − 截至 asOf 已生效的累计冲正金额"得到有效还款净额，
    其生效日仍为原还款生效日，剩余有效流水整体重放，重新得出各期分摊、剩余应还、
    罚息（含早期还本被撤后罚息基数回升）、逾期天数与风险分类——
    对该时点而言与"这笔还款的被冲部分从未在生效日后仍然有效"等价。
- 多次部分冲正各有各的生效日，形成时间阶梯：任一时点只反映当时已生效的冲正；
  罚息等日切口径在生效日与次日之间只允许按日自然计息的差异。
- 冲正指令的 `effectiveDate` 不得早于原还款生效日、不得晚于提交日；
  它**不会**把冲正当成一笔反向现金流插入该日（现金流净额的变化发生在冲正生效日的口径上，
  被更正的仍是原还款）。
- 支持**整笔冲正**与**按金额部分冲正**；可对同一笔还款多次部分冲正，但
  **累计冲正金额不得超过原还款金额**（边界恰好相等允许，超过即拒绝）。
- 整笔冲净后：原还款事实仍保留在账本可查（金额不变），其在生效日后的视图里分摊明细为空、
  净额为 0；在生效日之前的历史视图里仍保留原始分摊。
- **时点重放不落盘**：查询路径在计划/流水副本上重放，不修改账本事实；
  落账路径（入账/冲正成功后）以"全部已登记事实的最大生效日"为门控日重放并回写当前分摊。

### 冲正后的账户行为

- 若冲正让账户从 `SETTLED` 退回未结清，自冲正生效日起状态回到 `ACTIVE`，此后可继续正常
  还款；补回款项后仍可重新结清。`ACTIVE/SETTLED` 之间每次随资金事实发生的迁移都会落一条
  带生效日的**账户状态事件**（`AccountStatusEvent`），因此历史时点视图里的 `status` 同样
  按 asOf 重建（例如"2/1 结清、2/10 冲正退回"的账户在 2/9 的视图里仍是 `SETTLED`）。
  `CLOSED` 是无日期的关账终态标记，不进入该时间线。
- 若冲正暴露出新的欠款并达到逾期条件（均以冲正生效日时点重放结果为准）：
  - 逾期天数、罚息（含早期还本消失导致的基数回升）随重放重算；
  - 服务的"from 分类"取**冲正生效日时点**的风险状态（晚于该日的迁移当时尚未发生），
    并按**冲正生效日适用的规则版本**自动重估，落一条
    `AUTO_AFTER_REVERSAL(n d)` 风险历史；若推导的迁移不在状态机白名单内
    （例如已 `NPL` 不可自动恢复），保留当时分类并打告警日志，留待显式重估/人工处理。

### 可追溯性

每笔冲正都是不可变事实（`ReversalRecord`），记录：
**冲正幂等键、被冲还款序号、冲正金额、冲正生效日、登记日、冲正原因**；
账户视图 `GET /accounts/{id}` 的 `reversals` 字段可查全部冲正流水。

### 拒绝原因（可区分，便于调用方处理）

| reason | 触发条件 |
| ------ | -------- |
| `REVERSAL_TARGET_NOT_FOUND` | 被冲还款序号不存在，或用别的账户去冲（标识串户/串还款） |
| `REVERSAL_AMOUNT_INVALID` | 冲正金额 ≤ 0 |
| `REVERSAL_EXCEEDS_PAYMENT` | 对同一笔还款累计冲正超过原额（含整笔冲净后再冲） |
| `IDEMPOTENCY_KEY_CONFLICT` | 同键但操作类型/账户/目标还款/金额对不上 |
| `ACCOUNT_NOT_POSTABLE` | 账户处于不可入账终态（`CLOSED`、`WRITTEN_OFF`） |
| `VALIDATION_ERROR` | 缺键/缺原因/生效日早于原还款日或晚于提交日等 |

### 与既有能力的兼容范围

- 冲正完全复用现有"重放驱动"的分摊（罚息→费用→利息→本金）、按日分段的罚息累计、
  逾期判定与守恒校验；`PenaltyCalculator` / `PaymentAllocator` / `OverdueEvaluator` 无口径改动，
  只在服务层重放入口增加了"冲正按生效日门控"这一时间维度。
- 风险规则版本能力保持不变：冲正触发的自动分类按生效日选取当时生效版本，历史判定不被改写；
  账户视图中的 `riskState/ruleVersion` 也按 asOf 沿风险历史时间线重建。
- 资金驱动的 `ACTIVE/SETTLED` 迁移新增了带日期的 `AccountStatusEvent` 时间线，
  使结清/退回在历史视图中可回溯；`CLOSED` 语义不变。
- **流水序号按账户独立分配**：某账户第 n 条资金事实（还款或冲正）序号即为 n，
  因此 `/payments/1/reversals` 永远指向该账户第一笔还款，与其它账户活动无关。
- 既有还款、回溯补录、风险迁移接口的请求/响应结构均未改变；幂等登记表保留
  `kind`（`PAYMENT`/`REVERSAL`）与 `targetSequence` 字段。

---

## 4. 幂等与并发语义（Idempotency & Concurrency）

### 幂等

- 每笔还款与每笔冲正都必须带 `idempotencyKey`，语义一致：
  **同键 + 同操作类型 + 同账户 +（同金额 / 同目标还款及金额）**的重复提交返回既有结果
  （响应中 `duplicate=true`），**绝不重复扣款/重复冲账**，也不产生第二条流水；
  重复提交返回的余额为账户当前（全部已登记事实落账后）口径，流水本身仍可按任意 asOf
  重放追溯其当时分摊，结果只取决于事实。
- 冲正幂等指纹为"账户 + 目标还款序号 + 冲正金额"（并校验操作类型）：
  同键不同金额、键串到别的账户、键串到别的还款、或键在还款与冲正两类操作间串用，
  一律拒绝为 `IDEMPOTENCY_KEY_CONFLICT`，与"正常重复提交"明确区分。
- 金额 ≤ 0：还款为 `PAYMENT_AMOUNT_INVALID`、冲正为 `REVERSAL_AMOUNT_INVALID`；
  还款超过剩余应还：`OVERPAYMENT`；冲正累计超过原还款：`REVERSAL_EXCEEDS_PAYMENT`；
  账户非可入账状态：`ACCOUNT_NOT_POSTABLE`。

### 并发

- 内存账本为每个账户维护一把锁、每个幂等键维护一把锁。
- 复合操作固定按 **"先幂等键锁、后账户锁"** 的全局唯一顺序取锁，避免死锁。
- 同一账户的还款、冲正与风险状态变更因此被串行化：并发下不丢更新、不超额入账、
  不超额冲正、同一笔钱不会被冲两次，分摊/罚息/余额/状态保持一致；
  每次资金操作后都在账户锁内执行落账重放与守恒校验，锁释放时账本与派生口径必然一致。
- 同键并发（无论还款还是冲正）恰好一笔真实落账，其余命中幂等返回。
- 累计可冲金额在账户锁内按"已登记冲正之和"即时判定，故多笔不同键冲正并发时
  超出原额的请求必然拿到 `REVERSAL_EXCEEDS_PAYMENT`，不会出现超额冲正。

### 不可入账（终态）账户的资金操作

- 账户一旦进入不允许继续入账的终态，**所有新的资金类操作（还款与冲正）都按现有语义拒绝**
  为 `ACCOUNT_NOT_POSTABLE`，不会悄悄把钱写进账：
  - `CLOSED`（已关账，可通过 `POST /accounts/{id}/close` 置位）；
  - 风险状态 `WRITTEN_OFF`（已核销终态）。
- 例外：`SETTLED`（已结清）不是终态——它拒绝新还款，但**允许冲正**
  （冲正暴露欠款后退回 `ACTIVE`，以便继续还款）。

---

## 5. 风险状态流转与规则版本（Risk State & Versioning）

风险状态：`NORMAL → WATCH → OVERDUE → NPL → WRITTEN_OFF`。

- 分类规则带**版本号 + 生效日期**，按判定日选取当时生效的版本：
  - `V1`（2024-01-01 起）：逾期 ≥1 `WATCH`、≥30 `OVERDUE`、≥90 `NPL`；
  - `V2`（2026-01-01 起）：逾期 ≥1 `WATCH`、≥15 `OVERDUE`、≥60 `NPL`。
- **规则升级不改写历史判定**：对历史日期重估仍取该日生效的旧版本（如逾期 20 天在
  2025 年为 `WATCH`/V1，在 2026 年为 `OVERDUE`/V2，两者并存且历史版本可回溯复算）。
- 每次状态迁移都落一条带 `effectiveDate / from / to / ruleVersion / reason` 的历史。
- 迁移白名单（`RiskStateMachine`）：
  - `NORMAL/WATCH/OVERDUE` 可相互流转（还款后可恢复），也可恶化至 `NPL`、核销；
  - `NPL` 只能 → `WRITTEN_OFF`，不可自动/手动恢复；
  - `WRITTEN_OFF` 为终态，任何迁出均拒绝。
- 非法迁移明确拒绝为 `ILLEGAL_RISK_TRANSITION`；自动分类若推导出白名单外的迁移
  （例如已 `NPL` 却被算成更轻状态）同样拒绝并提示人工处理。
- `WRITTEN_OFF` 不会被自动分类降级。

### 版本兼容范围

当前内置 `V1`、`V2` 两套阈值，仅在版本生效日之后改变判定；历史账户与历史日期
始终可用其当时版本复算。新增版本只需在 `RiskRuleBook` 中追加（带生效日期），
不影响任何历史结论。

---

## 6. HTTP 入口

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| POST | `/api/loans/accounts/{accountId}` | 开户并生成还款计划 |
| POST | `/api/loans/accounts/{accountId}/payments` | 还款入账（幂等） |
| POST | `/api/loans/accounts/{accountId}/payments/{paymentSequence}/reversals` | 对指定还款发起冲正（整笔/部分，幂等） |
| POST | `/api/loans/accounts/{accountId}/close` | 关账（置 `CLOSED` 终态，此后资金操作拒绝） |
| POST | `/api/loans/accounts/{accountId}/risk-transitions` | 手动风险迁移（白名单校验） |
| POST | `/api/loans/accounts/{accountId}/risk-reevaluation?` | 按日期自动重估风险分类 |
| GET  | `/api/loans/accounts/{accountId}?asOf=yyyy-MM-dd` | 账户视图（剩余本金/利息/费用/罚息、还款流水、**冲正流水**、风险历史） |

业务拒绝返回 `422`（账户不存在为 `404`），响应体形如：

```json
{ "reason": "OVERPAYMENT", "message": "还款金额 9999 超过剩余应还 ..." }
```

可区分的 `reason`：`ACCOUNT_NOT_FOUND`、`ACCOUNT_NOT_POSTABLE`、
`IDEMPOTENCY_KEY_CONFLICT`、`PAYMENT_AMOUNT_INVALID`、`OVERPAYMENT`、
`REVERSAL_TARGET_NOT_FOUND`、`REVERSAL_AMOUNT_INVALID`、`REVERSAL_EXCEEDS_PAYMENT`、
`ILLEGAL_RISK_TRANSITION`、`RULE_VERSION_UNKNOWN`、`VALIDATION_ERROR`。

### 快速试跑（curl）

```bash
curl -s -X POST localhost:8080/api/loans/accounts/demo \
  -H 'Content-Type: application/json' \
  -d '{"principal":"12000","annualRate":"0.12","installmentCount":12,
       "originationDate":"2025-01-01","firstDueDate":"2025-02-01",
       "method":"EQUAL_INSTALLMENT","feeRate":"0"}'

curl -s -X POST localhost:8080/api/loans/accounts/demo/payments \
  -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"k1","amount":"1063","effectiveDate":"2025-02-01"}'

# 对序号为 1 的还款部分冲正 63（款项部分退回）
curl -s -X POST localhost:8080/api/loans/accounts/demo/payments/1/reversals \
  -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"rv1","amount":"63","effectiveDate":"2025-02-05","reason":"银行退汇"}'

curl -s "localhost:8080/api/loans/accounts/demo?asOf=2025-06-01"
```

---

## 7. 配置项（application.properties）

```properties
loan.servicing.grace-days=3                      # 宽限期天数
loan.servicing.day-count=ACT_365_FIXED           # ACT_365_FIXED / ACT_360
loan.servicing.cutoff-hour=0                     # 业务日切小时
loan.servicing.annual-penalty-rate=0.18          # 年罚息利率
loan.servicing.extend-overdue-to-business-day=false  # 宽限截止日落周末是否顺延
```

---

## 8. 本地验证方法

前置：JDK 21+、Maven 3.9+（无需数据库、消息队列或任何真实凭据）。

```bash
# 运行全部单测（68 个，覆盖正常与边界场景；默认按 reversealphabetical 固定类序）
mvn clean test

# 顺序无关性验证：同一套用例在不同类执行顺序下都必须全绿
mvn test -Dsurefire.runOrder=alphabetical
mvn test -Dsurefire.runOrder=reversealphabetical
mvn test -Dsurefire.runOrder=random      # 可多跑几次

# 仅运行冲正相关
mvn test -Dtest=ReversalServiceTest
mvn test -Dtest=ReversalConcurrencyTest
mvn test -Dtest=ReversalEffectiveDateTest
mvn test -Dtest=ReversalControllerTest,ReversalControllerOrderTest
# 既有能力回归
mvn test -Dtest=PaymentServiceTest
mvn test -Dtest=ConcurrencyServiceTest
mvn test -Dtest=BackdatingAndReplayTest

# 启动服务
mvn spring-boot:run
```

### 冲正场景本地复现要点

- **生效日前历史不变 / 生效日边界**：2/1 入账、2/5 冲正；分别用
  `getAccountView("2025-02-04")` 与 `getAccountView("2025-02-05")` 查询——
  前一视图与冲正前逐期一致（含原流水分摊），后一视图起净额扣减、状态/风险按重述口径。
  见 `historyBeforeEffectiveDateIsIdenticalToBeforeReversal`、
  `effectiveDateBoundaryDayAppliesReversalFromThatDayInclusive`。
- **多次部分冲正阶梯**：3/1 冲 400、4/1 再冲 300（原 1000），2/28、3/1、3/15、4/1、5/1
  五个时点的有效已收依次为 1000/600/600/300/300。
- **整笔/部分冲正与回溯重算**：先入账、再以更晚的生效日冲正，用
  `getAccountView(asOf)` 对照"该笔在生效日后从未有效"的账户，各期 paid*/罚息逐期一致
  （见 `reversalReplaysBackdatedAllocationAndPenaltyByEffectiveDate`）。
- **冲正后补录更早还款**：冲正之后再补录生效日更早的还款，冲正仍只对其生效日及之后的
  口径生效（`earlierBackdatedPaymentPostedAfterReversalIsReplayedConsistently`）。
- **结清退回**：单期账户一次结清 → 部分冲正 → 生效日前视图仍 `SETTLED`、生效日起回
  `ACTIVE` → 足额补回（含冲正暴露的少量罚息）后重新结清。
- **逾期/风险暴露**：首期当天足额还第一期，多日后整笔冲正，生效日起风险自动迁移并落
  `AUTO_AFTER_REVERSAL` 历史；生效日前视图风险仍为 `NORMAL`。
- **并发**：16/20 线程混合还款与冲正后，净入账恒等于各期已收、同一笔不会被冲两次，
  且生效日前/后两个时点视图的有效已收严格相差已生效冲正额。
- **幂等/冲突/超额**：重复提交看 `duplicate=true`；同键改金额/换目标/串户/跨操作类型
  均为 `IDEMPOTENCY_KEY_CONFLICT`；累计超原为 `REVERSAL_EXCEEDS_PAYMENT`。
- **终态拒绝**：`/close` 或迁到 `WRITTEN_OFF` 后，还款与冲正都返回 `ACCOUNT_NOT_POSTABLE`。
- **HTTP 入口顺序稳定**：`ReversalControllerOrderTest` 每个场景使用随机账户/幂等键，
  不引用任何全局序号，单独跑、与其它接口用例同批跑、换顺序跑结论一致。

### 测试覆盖矩阵

| 场景 | 所在测试类 |
| ---- | ---------- |
| 计划生成 / 本金守恒 / 尾差 / 零利率 | `ScheduleGeneratorTest` |
| 宽限期临界、零宽限、周末顺延、部分还款逾期 | `OverdueEvaluatorTest` |
| 起息临界、ACT/360、分段计息对照逐日基准、重放不重复累计、补录重算 | `PenaltyCalculatorTest` |
| 规则版本选取、升级不改历史、阈值、终态、迁移白名单 | `RiskRuleTest` |
| 重复还款、键冲突、超额、非法金额、部分还款、提前结清、不可入账、分摊顺序 | `PaymentServiceTest` |
| 并发：不同键不丢更新、同键恰好一笔扣款、并发不超额 | `ConcurrencyServiceTest` |
| 回溯补录重算罚息、提交顺序不影响结果、相同输入可复现、未来日期拒绝 | `BackdatingAndReplayTest` |
| 非法迁移、核销终态、版本升级、宽限临界、带版本历史 | `RiskServiceTest` |
| 逾期升级与重复重估稳定性 | `LifecycleServiceTest` |
| 各 HTTP 入口与可区分错误码 | `LoanControllerTest` |
| **整笔/部分冲正、幂等与标识冲突（金额/目标/串户/跨类型）、重复与超额冲正拒绝、**<br>**回溯口径重算、结清退回继续还款、逾期风险暴露、终态资金操作拒绝、冲正追溯** | `ReversalServiceTest` |
| **冲正并发不超额/同一笔不被冲两次、同键恰好一笔、冲正与新还款混合并发守恒** | `ReversalConcurrencyTest` |
| **冲正 HTTP 入口、幂等/超额/目标不存在/关账错误码** | `ReversalControllerTest` |
| **生效日前历史与冲正前一致、生效日（含当天）边界重述、多次部分冲正阶梯、**<br>**风险只在生效日起变化、结清事件时间线、冲正后补录早还款、并发混合操作的时点守恒、随机换序稳定** | `ReversalEffectiveDateTest` |
| **HTTP 场景自包含（随机账户/键），整批/换序运行稳定** | `ReversalControllerOrderTest` |

单测日志统一打印**输入、关键判定依据（逾期天数/适用规则版本/各桶分摊/累计冲正额/
时点有效已收）与最终状态**，关键用例使用中文日志标签
（`[输入]` / `[判定]` / `[最终]`），失败时可直接从日志复盘。

---

## 9. 代码结构

```
domain/      枚举与数据载体（账户、计划期次、还款指令/流水/结果、冲正指令/流水/结果、
             风险流转、资金驱动的账户状态事件、幂等登记(kind区分还款/冲正)、账户视图）
engine/      ScheduleGenerator / PaymentAllocator / PenaltyCalculator /
             OverdueEvaluator / RiskRuleBook / RiskRuleVersion / RiskStateMachine
store/       LoanAccountRepository（内存账本 + 账户锁/幂等键锁；流水序号按账户独立分配）
service/     LoanServicingService（门面：时点重放(replayView,不落盘)/落账重放(materialize)、
             入账、冲正、风险、查询、守恒校验）
web/         LoanController（HTTP 入口）/ GlobalExceptionHandler（错误码映射）
config/      ServicingProperties（宽限/计息/日切/罚息可配置）
```
