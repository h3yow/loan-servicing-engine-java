# 本地贷款服务与还款入账流程（Loan Servicing Engine）

纯本地、无外部依赖的贷款账本服务（Spring Boot + 内存账本）。支持还款计划生成、
还款入账（费用/利息/本金分摊）、逾期判定、罚息累计、带版本的风险状态流转。
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
        = 已收还款总额 + 剩余应还(计划未还本金/利息/费用 + 应计未付罚息)
```

每笔还款入账后服务都会执行该守恒校验与逐期非负/无超额冲抵校验，失败立即抛出
`IllegalStateException`（属于程序不变量破坏，而非业务拒绝）。

---

## 2.5 还款冲正（Payment Reversal）

用于"还款记错金额/记错账户、款项被银行退回"等场景：**撤掉已入账还款的部分或全部金额，
让账按事实重新算对**，而不是人工再补一笔反向流水。

### 语义与生效日期口径

- 冲正**不删除原还款**，而是新增一条不可变的**冲正事实**（冲哪笔还款 `paymentSequence`、
  冲正金额、原因、生效日、自身序号、幂等键）；每笔冲正都可追溯。
- 冲正金额被视作"**从未收到**"：重放时从目标还款的**有效金额**中扣减
  （`有效金额 = 原金额 − 截至 asOf 已生效冲正合计`），
  - **整笔冲正**：有效金额为 0，该笔还款在重放中整体跳过（不分摊、不产生本金减基数事件）；
  - **部分冲正**：按扣减后的金额重新分摊，系统把全部剩余有效流水按生效日**整体重述重算**——
    各期分摊、剩余应还、罚息、逾期天数、风险状态都随之重新得出。
- **生效日期口径（关键）**：冲正有独立的 `effectiveDate`（不得早于被冲还款的生效日，
  同样受"不得晚于提交日"与日切规则约束）。
  - 用 `asOf < 冲正生效日` 重放（查历史视图）时，该冲正**不纳入**，历史口径与冲正前完全一致；
  - 用 `asOf ≥ 冲正生效日` 重放时，冲正生效，账本按"这笔钱从未收到"重述：
    被冲掉的本金重新计为未还，其在逾期期间的**罚息也会重新计出**，逾期天数重新判定。
  - 因此冲正前的历史结论可回溯复现，冲正后的当前口径自动算对，二者都只取决于事实集合。
- 同一笔还款的**累计冲正金额不得超过原还款金额**（否则 `REVERSAL_EXCEEDS_PAYMENT`）；
  可以分多次部分冲正，每次都独立幂等、独立留痕。
- **状态联动**：冲正后剩余为 0 则账户 `SETTLED`，否则 `ACTIVE`——即冲正可以让账户
  **从已结清退回未结清**，退回后可继续正常还款；若冲正暴露出达到逾期条件的欠款，
  逾期天数与风险分类按冲正生效日的规则版本自动重估（迁移仍受状态机白名单约束，
  推导不出合法迁移时保持现状并告警人工处理，冲正本身不回滚）。

### 冲正的幂等与冲突区分

冲正复用与还款完全一致的幂等机制（同一套幂等键登记，登记项带 `kind` 区分 PAYMENT/REVERSAL）：

- **同键 + 同账户 + 同目标还款 + 同金额**的重复提交：返回第一次结果
  （`duplicate=true`，回首次冲正生效日口径），**绝不二次冲账**；
- **同键但内容对不上**：`IDEMPOTENCY_KEY_CONFLICT`，包括金额不符、目标还款序号不符、
  幂等键在还款与冲正之间串用；
- **标识串到别的账户/别的还款**：目标序号在该账户不存在 → `PAYMENT_NOT_FOUND`
  （跨账户的键另有 `IDEMPOTENCY_KEY_CONFLICT`）；
- 金额 ≤ 0：`REVERSAL_AMOUNT_INVALID`；冲正生效日早于被冲还款：`REVERSAL_DATE_INVALID`。

### 不可入账状态下的资金操作

- **还款**仅允许 `status=ACTIVE` 且风险状态未核销的账户；`SETTLED`/`CLOSED`/`WRITTEN_OFF`
  一律 `ACCOUNT_NOT_POSTABLE`。
- **冲正**允许 `ACTIVE`/`SETTLED`（结清账户需要能被冲正退回），但 `CLOSED`（已关账）与
  `WRITTEN_OFF`（已核销）等终态一律 `ACCOUNT_NOT_POSTABLE`，**拒绝时不写任何资金事实**。

### 与分摊/计息/风险版本能力的兼容范围

- 冲正完全建立在既有"计划 + 流水事实 → 重放"的模型上：分摊顺序（罚息→费用→利息→本金）、
  罚息分段计息与非增量纯重算、逾期/宽限/工作日规则**均无需特判**，对"有效金额"自动成立。
- 守恒恒等式升级为：
  `计划总额 + 应计罚息毛额 = 有效已收(还款总额 − 已生效冲正) + 剩余应还`，
  每笔冲正后同样强校验，失败抛 `IllegalStateException`。
- 风险规则版本选取口径不变：按重估日（冲正生效日）取当时版本，规则升级不改写历史判定；
  `NPL`/`WRITTEN_OFF` 不会因冲正被自动降级。

---

## 3. 幂等与并发语义（Idempotency & Concurrency）

### 幂等

- 每笔还款必须带 `idempotencyKey`。
- **同键 + 同账户 + 同金额**的重复提交：返回既有结果（响应中 `duplicate=true`），
  **绝不重复扣款**，也不产生第二条流水；返回口径锚定到原流水生效日，可复现。
- **同键但账户/金额不一致**：拒绝为 `IDEMPOTENCY_KEY_CONFLICT`（可与"重复提交"区分）。
- 金额 ≤ 0：`PAYMENT_AMOUNT_INVALID`；超过剩余应还：`OVERPAYMENT`；
  账户非 `ACTIVE`（已结清/已关账）：`ACCOUNT_NOT_POSTABLE`。

### 并发

- 内存账本为每个账户维护一把锁、每个幂等键维护一把锁。
- 复合操作固定按 **"先幂等键锁、后账户锁"** 的全局唯一顺序取锁，避免死锁。
- 同一账户的还款、冲正与风险状态变更因此被串行化：并发下不丢更新、不超额入账/超额冲正、
  余额/罚息/状态不不一致，**同一笔钱不会被冲两次**。
- 同键并发恰好一笔真实操作（还款扣款或冲正），其余命中幂等返回。
- 对同一笔还款的并发部分冲正受"累计 ≤ 原金额"约束：在账户锁内判定，
  超限者拒绝为 `REVERSAL_EXCEEDS_PAYMENT`，最终每个目标的累计冲正恒不超过其原金额。

---

## 4. 风险状态流转与规则版本（Risk State & Versioning）

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

## 5. HTTP 入口

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| POST | `/api/loans/accounts/{accountId}` | 开户并生成还款计划 |
| POST | `/api/loans/accounts/{accountId}/payments` | 还款入账（幂等） |
| POST | `/api/loans/accounts/{accountId}/reversals` | 还款冲正（幂等：`paymentSequence/idempotencyKey/amount/effectiveDate/reason`） |
| POST | `/api/loans/accounts/{accountId}/risk-transitions` | 手动风险迁移（白名单校验） |
| POST | `/api/loans/accounts/{accountId}/risk-reevaluation?` | 按日期自动重估风险分类 |
| GET  | `/api/loans/accounts/{accountId}?asOf=yyyy-MM-dd` | 账户视图（剩余本金/利息/费用/罚息、流水、风险历史） |

业务拒绝返回 `422`（账户不存在为 `404`），响应体形如：

```json
{ "reason": "OVERPAYMENT", "message": "还款金额 9999 超过剩余应还 ..." }
```

可区分的 `reason`：`ACCOUNT_NOT_FOUND`、`ACCOUNT_NOT_POSTABLE`、
`IDEMPOTENCY_KEY_CONFLICT`、`PAYMENT_AMOUNT_INVALID`、`OVERPAYMENT`、
`ILLEGAL_RISK_TRANSITION`、`RULE_VERSION_UNKNOWN`、
`REVERSAL_AMOUNT_INVALID`、`PAYMENT_NOT_FOUND`、`REVERSAL_EXCEEDS_PAYMENT`、
`REVERSAL_DATE_INVALID`、`VALIDATION_ERROR`。

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

# 对序号为 2 的还款部分冲正 200（paymentSequence 取还款响应/账户视图中的流水 sequence）
curl -s -X POST localhost:8080/api/loans/accounts/demo/reversals \
  -H 'Content-Type: application/json' \
  -d '{"paymentSequence":2,"idempotencyKey":"rv1","amount":"200",
       "effectiveDate":"2025-03-01","reason":"银行退回"}'

curl -s "localhost:8080/api/loans/accounts/demo?asOf=2025-06-01"
```

---

## 6. 配置项（application.properties）

```properties
loan.servicing.grace-days=3                      # 宽限期天数
loan.servicing.day-count=ACT_365_FIXED           # ACT_365_FIXED / ACT_360
loan.servicing.cutoff-hour=0                     # 业务日切小时
loan.servicing.annual-penalty-rate=0.18          # 年罚息利率
loan.servicing.extend-overdue-to-business-day=false  # 宽限截止日落周末是否顺延
```

---

## 7. 本地验证方法

前置：JDK 21+、Maven 3.9+（无需数据库、消息队列或任何真实凭据）。

```bash
# 运行全部单测（54 个，覆盖正常与边界场景）
mvn clean test

# 仅运行某一类
mvn test -Dtest=PaymentServiceTest
mvn test -Dtest=ReversalServiceTest
mvn test -Dtest=ReversalConcurrencyTest
mvn test -Dtest=ConcurrencyServiceTest
mvn test -Dtest=BackdatingAndReplayTest

# 启动服务
mvn spring-boot:run
```

### 测试覆盖矩阵

| 场景 | 所在测试类 |
| ---- | ---------- |
| 计划生成 / 本金守恒 / 尾差 / 零利率 | `ScheduleGeneratorTest` |
| 宽限期临界、零宽限、周末顺延、部分还款逾期 | `OverdueEvaluatorTest` |
| 起息临界、ACT/360、分段计息对照逐日基准、重放不重复累计、补录重算 | `PenaltyCalculatorTest` |
| 规则版本选取、升级不改历史、阈值、终态、迁移白名单 | `RiskRuleTest` |
| 重复还款、键冲突、超额、非法金额、部分还款、提前结清、不可入账、分摊顺序 | `PaymentServiceTest` |
| 整笔/部分冲正、累计冲正、结清退回、冲正回溯重述、罚息重计、幂等/串键/超额/串户/终态拒绝 | `ReversalServiceTest` |
| 冲正并发：同一笔不被多冲、冲正与还款并发守恒、同键恰好一笔 | `ReversalConcurrencyTest` |
| 并发：不同键不丢更新、同键恰好一笔扣款、并发不超额 | `ConcurrencyServiceTest` |
| 回溯补录重算罚息、提交顺序不影响结果、相同输入可复现、未来日期拒绝 | `BackdatingAndReplayTest` |
| 非法迁移、核销终态、版本升级、宽限临界、带版本历史 | `RiskServiceTest` |
| 逾期升级与重复重估稳定性 | `LifecycleServiceTest` |
| 各 HTTP 入口与可区分错误码 | `LoanControllerTest` |

单测日志统一打印**输入、关键判定依据（逾期天数/适用规则版本/各桶分摊）与最终状态**，
关键用例使用中文日志标签（`[输入]` / `[判定]` / `[最终]`），便于复盘。

---

## 8. 代码结构

```
domain/      枚举与数据载体（账户、计划期次、还款指令/流水/结果、冲正指令/流水/结果、
             风险流转、幂等登记(含PAYMENT/REVERSAL类型)、账户视图）
engine/      ScheduleGenerator / PaymentAllocator / PenaltyCalculator（重放感知冲正有效金额）/
             OverdueEvaluator / RiskRuleBook / RiskRuleVersion / RiskStateMachine
store/       LoanAccountRepository（内存账本 + 账户锁/幂等键锁 + 还款/冲正两套流水）
service/     LoanServicingService（门面：重放驱动的入账、冲正、风险、查询、守恒校验）
web/         LoanController（HTTP 入口，含 /reversals）/ GlobalExceptionHandler（错误码映射）
config/      ServicingProperties（宽限/计息/日切/罚息可配置）
```
