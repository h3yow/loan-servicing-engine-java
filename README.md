# 本地贷款服务与还款入账引擎（Loan Servicing Engine）

纯本地、无外部服务依赖、无需真实凭据的贷款服务：用**仅追加事件账本 + 确定性重放**
模拟贷款账户、还款计划、罚息与风险状态。同一组输入在任何入口（HTTP / 单测 / 重放接口）
得到的结论完全一致且可复现。

- 技术栈：Java 21、Spring Boot 3.4、内存存储（`ConcurrentHashMap`）、JUnit 5。
- 金额一律 `BigDecimal`，四舍五入到分（`HALF_UP`）。

---

## 1. 核心模型

| 概念 | 说明 |
| --- | --- |
| `LoanAccount` | 聚合根，只持有按序号追加的事件，不就地保存余额 |
| `AccountEvent` | 事件：`Opened / PaymentReceived / FeeCharged / RiskTransition / WrittenOff`，含业务生效时间与单调 `seq` |
| `AccountProjector` | 投影器：按 `(effectiveAt, seq)` 排序事件，重放任意时点的账户状态 |
| `Installment` | 还款计划一期：到期日、应还本金、应还利息、已还各项、是否结清 |
| `PaymentReceipt` | 还款回执：幂等键、生效时间、分摊明细、减免利息 |
| `RiskRuleBook` | 风险分类规则版本（v1/v2），发布即不可变 |
| `AccountStore` | 内存账户表 + 幂等表，**每个账户一把锁** |

事件只追加、不修改。补录/回溯入账 = 向历史时间点插入事件后全量重放，
因此罚息、逾期状态、余额全部随生效日期重新计算，**不存在增量累计导致的重复计提**。

---

## 2. 还款分摊顺序

每笔还款严格按以下顺序分摊（同类内部按到期日/计收先后，先欠先还）：

```
FEE（费用） → PENALTY（已计提罚息） → INTEREST（应还利息） → PRINCIPAL（应还本金）
```

- **部分还款**：允许，按上述顺序逐项冲抵，返回明细分摊行。
- **普通还款**：只允许偿还“到期日 ≤ 还款生效日”的期；未到期期的本金/利息不可提前占用。
  还款额超过当前应缴 → 拒绝（`PAYMENT_EXCEEDS_DUE`）；当前无应缴 → 拒绝
  （`ACCOUNT_NOT_PAYABLE`，`nothing due`）。
- **提前结清**（请求 `earlySettle=true`）：金额必须**恰好等于结清报价**
  （费用 + 罚息 + 全部剩余本金 + 已到期期利息）。到期日晚于结清日的期只收本金，
  其计划利息被**减免**（回执与快照中的 `waivedInterest`），减免期标记为结清。
- 还款计划采用**等本按月**：每期本金 = 本金 / 期数（末期吸收尾差）；
  每期利息 = 期初剩余本金 ×（年利率 / 12），末期同样吸收尾差，
  保证各期本金之和 = 合同本金、各期利息之和守恒。

### 错误原因（可区分）

| ErrorCode | 触发情形 |
| --- | --- |
| `ACCOUNT_NOT_FOUND` | 账户不存在 |
| `ACCOUNT_NOT_PAYABLE` | 已核销/已结清仍尝试入账，或当前无应缴 |
| `IDEMPOTENCY_CONFLICT` | 同一幂等键对应了不同的账户/金额/生效时间 |
| `PAYMENT_EXCEEDS_DUE` | 超额还款，或提前结清金额不等于结清报价 |
| `ILLEGAL_TRANSITION` | 非法风险迁移、核销后再迁移 |
| `RISK_RULE_VERSION_NOT_FOUND` | 指定了不存在的规则版本 |
| `BACKDATE_NOT_SUPPORTED` | 显式生效时间指向未来 |
| `INVALID_REQUEST` | 参数缺失/非法 |

---

## 3. 计息、罚息与宽限规则

配置项（`application.properties` 前缀 `loan.engine`，可按测试覆盖）：

| 配置 | 默认 | 含义 |
| --- | --- | --- |
| `grace-days` | `3` | 宽限期天数 |
| `day-count-basis` | `ACT/365` | 日计息口径，可选 `ACT/365` / `ACT/360` |
| `penalty-annual-rate` | `0.18` | 罚息年利率 |
| `penalty-basis` | `DUE` | 罚息基数：`DUE`=逾期期未付本息；`OUTSTANDING`=账户全部剩余本金 |
| `business-cutoff` | `00:00` | 业务日切时间（`HH:mm`） |

规则：

- **起罚日 = 到期日 + 宽限期 + 1**。
- 计息区间为 **[起罚日, 下一事件日/评估日)**，即**起罚日当天计提 1 天，
  还款生效日当天不计息**。在宽限期内（含宽限最后一日）还款无罚息。
- 逾期天数（DPD）= `评估日 − 到期日 − 宽限期`（未结清期取最大值，≤0 取 0）。
- 罚息按时间轴**分段**计算：`罚息 = 逾期基数 × 罚息年利率 / 计息分母 × 天数`。
  部分还款只改变还款生效日**之后**的逾期基数；已计未付的罚息是独立资金池，
  在下一笔还款中按分摊顺序第二位被冲抵。
- **回溯/补录**：插入历史还款事件后整段重放。已偿清期不再产生罚息，
  因此不会重复累计；任何时点罚息都由“事件 + 评估时点”重新推导，不保存累加值。
- **业务日切**：未显式给出 `effectiveAt` 时，提交时间晚于（含）日切点则顺延到下一自然日；
  显式 `effectiveAt` 表示补录/回溯，只允许指向过去。

---

## 4. 幂等与并发语义

### 幂等

- 每笔还款必须携带 `idempotencyKey`。键在全局幂等表中**原子占位**
  （`putIfAbsent`），登记首次请求指纹：`(accountId, amount, effectiveAt)`。
- **完全重复提交**（指纹一致）：不再次扣款，在账户锁内重放并返回**同一回执**。
- **同键冲突**（指纹任一不同）：拒绝，错误码 `IDEMPOTENCY_CONFLICT`，
  响应中带首次登记的账户/金额/生效时间，便于客户端识别。
- 业务校验失败（超额、不可入账等）会**回滚幂等占位**，客户端修正后可用同一键重试；
  一旦入账成功，该键永久绑定该笔扣款。
- 费用计收以 `feeId` 幂等。

### 并发

- 每个 `LoanAccount` 实例一把内在锁。所有“读—校验—写事件—投影”
  （还款、费用、风险迁移、核销）都在 `AccountStore.withAccountLock(...)` 内原子完成。
- 幂等占位在账户锁之外先做，保证不同键的请求互斥判定、同键请求收敛到同一笔事件；
  重复提交即使在首个提交落事件的瞬间到达，也会在账户锁内等到事件可见后返回同一回执。
- 投影器为纯函数式重放，只读事件、无共享可变状态。
- `ConcurrencyTest` 覆盖：多线程不同键小额并发、还款与风险迁移交错、
  同键高并发（保证恰好一笔入账）。

---

## 5. 风险状态与规则版本

状态（严重度递增）：

```
NORMAL(0) → WATCH(1) → SUBSTANDARD(2) → DOUBTFUL(3) → NPL(4) → WRITTEN_OFF(5)
```

内置规则版本（按 DPD 阈值分类，阈值指**超过宽限期后的天数**）：

| 版本 | 生效时间 | WATCH | SUBSTANDARD | DOUBTFUL | NPL |
| --- | --- | --- | --- | --- | --- |
| `v1` | 2026-01-01 | 1 | 31 | 61 | 91 |
| `v2` | 2026-07-01 | 8 | 31 | 91 | 121 |

迁移矩阵（`RiskTransitions`，人工迁移）：

- **恶化（下调分类）只允许逐级**：`NORMAL→DOUBTFUL` 等跨级跳变 → `ILLEGAL_TRANSITION`。
- **好转（上调分类）允许跨级恢复**，如 `SUBSTANDARD→NORMAL`。
- **核销只能从 `NPL` 进入矩阵语义**；核销是独立业务动作（`/write-off`），
  一旦 `WRITTEN_OFF` 即终态，任何迁移/分类/还款都被拒绝。
- **系统分类**（`/risk/classifications`）按当时 DPD 与规则版本**直接落目标态**
  （规则结果，允许跨级），事件中记录判定所用版本；
  不传版本时取事件时点已生效的最新版本，传历史时刻则用当时版本。
- **版本兼容范围**：版本一经注册不可变、不可删除；新版本只影响其生效之后的新判定。
  历史 `RiskTransition` 事件永久保存当时版本号，规则升级后重放历史，
  历史行数量、版本与结论都不变（快照另提供 `suggestedRiskState` 表示
  “按当前规则重新评估会得到的建议分类”，与已发生的人工历史分离）。
- 账户结清时系统建议分类回到 `NORMAL`（不产生人工迁移事件）。

---

## 6. 守恒与确定性

未核销账户任意时点恒成立：

```
totalDue = outstandingPrincipal + outstandingInterest + outstandingFee + accruedPenalty
```

投影器在每次重放后强校验：

- 上述组件之和等于总待还；
- 任一期已还本金/利息不得超过其应收；已还本金合计不得超过合同本金；
- 罚息池不得为负。

违反即抛错（内部一致性保护）。确定性：全部计算只依赖事件序列与“评估时点”，
不依赖时钟、随机数或执行顺序；同一序列重放 N 次结果逐字段相等
（含 `PaymentReceipt` 全部分摊明细）。

---

## 7. HTTP 接口

基址：`http://localhost:8080/api/accounts`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/` | 开户（可传 `openedAt` 补录历史账户、`firstDueDate`、`dayCountBasis`） |
| POST | `/{id}/payments` | 还款入账（`idempotencyKey` 必填，`effectiveAt` 可回溯，`earlySettle` 提前结清） |
| POST | `/{id}/fees` | 计收费用（`feeId` 幂等） |
| POST | `/{id}/risk/transitions` | 人工风险迁移（`toState/ruleVersion/reason`） |
| POST | `/{id}/risk/classifications` | 按 DPD 系统分类（可指定 `ruleVersion/effectiveAt`） |
| POST | `/{id}/write-off` | 核销（终态） |
| GET | `/{id}` | 当前快照（固定时钟下的“现在”） |
| GET | `/{id}/replay?asOf=...` | 重放任意时点快照 |

业务错误返回 HTTP 422（账户不存在为 404），响应体：
`{"errorCode":"PAYMENT_EXCEEDS_DUE","message":"payment 9999.99 exceeds required 1110.55"}`。

### curl 速览

```bash
curl -X POST localhost:8080/api/accounts -H 'Content-Type: application/json' -d '{
  "accountId":"A1","principal":12000.00,"annualRate":0.12,"termMonths":12,
  "firstDueDate":"2026-01-31","dayCountBasis":"ACT/365","openedAt":"2026-01-01T10:00:00"}'

curl -X POST localhost:8080/api/accounts/A1/payments -H 'Content-Type: application/json' -d '{
  "idempotencyKey":"K1","amount":1120.00,"effectiveAt":"2026-02-03T10:00:00"}'

curl 'localhost:8080/api/accounts/A1/replay?asOf=2026-03-01T00:00:00'
```

---

## 8. 本地验证方法

```bash
# 编译
mvn -DskipTests compile

# 全量测试（28 个用例，含 Spring 上下文加载）
mvn test

# 只跑某一类场景
mvn -Dtest=OverduePenaltyTest test
mvn -Dtest=ConcurrencyTest test
mvn -Dtest=RiskVersionTest test

# 启动后用 curl 调接口（见上节）
mvn spring-boot:run
```

测试时钟固定在 `2026-09-22T10:00:00Z`（`TestFixtures`），所有日期结论可复现；
测试日志统一打印**输入、关键判定依据（起罚日、DPD、阈值版本、分摊明细）与最终状态**。

### 测试覆盖矩阵

| 测试类 | 覆盖场景 |
| --- | --- |
| `RepaymentTest` | 正常还款、部分还款（费→息→本顺序）、提前结清与利息减免、超额拒绝、无应缴拒绝、核销后不可入账 |
| `IdempotencyTest` | 重复提交不重复扣款、同键不同金额/跨账户冲突、失败不占键、错误码可区分 |
| `OverduePenaltyTest` | 宽限期临界（起罚日当天不计）、按日累计、入账当日停止、回溯补录重算不重复、ACT/360 vs ACT/365、部分还款后基数下降 |
| `ConcurrencyTest` | 不同键并发还款守恒、还款与风险迁移交错、同键高并发恰好一笔 |
| `RiskVersionTest` | v1/v2 阈值差异、升级不改写历史、逐级恶化/跨级恢复、非法跨级/终态/未知版本拒绝 |
| `ConservationDeterminismTest` | 多时点守恒（含费用、罚息、回溯）、同一输入多次重放逐字段一致 |
