# cc-number-porting

号码归属与运营商服务关系管理服务——实现手机号码从原运营商携转到新运营商的
**申请、审核、切换、受控回退**全生命周期，保证并发下状态唯一可解释、
切换原子无双归属、全部状态变化留痕不可改。

## 开发环境

- JDK 21
- Maven Wrapper 3.9.9
- Spring Boot 4.1.1 / Spring Data JPA / H2（默认内存库，可平滑替换为其他关系库）

## 常用命令

```bash
./mvnw clean test         # 运行全部自动化测试（32 个，含并发与故障注入）
./mvnw spring-boot:run    # 启动服务
```

## 核心业务规则

### 1. 携转申请

- 申请内容：号码、原运营商、新运营商、授权码、期望切换窗口（窗口起点必须早于终点）。
- **一个号码同一时间只能有一笔活动申请**（待审核 / 待切换 / 回退窗口内的已切换都占用名额）。
- 申请必须携带原运营商签发的授权码；授权码与号码、原运营商不匹配，或已过期/已使用，申请被拒绝。
- **申请号（requestId）幂等**：同一 requestId 的重复提交（含并发）返回同一笔申请；
  同 requestId 但内容不一致返回 409 `IDEMPOTENCY_CONFLICT`。

### 2. 授权码

- 由号码当前归属运营商签发，有**明确有效期**（到期时间）。
- 授权码**只能成功使用一次**：切换成功时在同一事务内标记已使用。
- 授权码到期或被运营商主动失效（吊销）后不可再用；引用该码的待审核/待切换申请
  会被关闭为 `AUTH_CODE_EXPIRED` 终态并释放活动名额。
- 已完成切换的申请，其授权码事后失效不影响回退权利。

### 3. 审核与切换

状态机：

```
PENDING_REVIEW ──审核通过──▶ APPROVED ──执行切换──▶ SWITCHED ──受控回退──▶ ROLLED_BACK
      │                         │                       │
      │                         │               (回退窗口关闭，受理新申请时)
      │                         │                       ▼
      │                         │                    COMPLETED
      ├──取消──▶ CANCELLED ◀───取消───────────────────┘
      └──授权码失效──▶ AUTH_CODE_EXPIRED ◀──授权码失效──┘
```

- 审核通过进入 `APPROVED`（待切换）；切换只允许在期望切换窗口内执行。
- **切换是一个原子事务**，同一行锁保护下四步同时成功或同时回滚：
  1. 关闭原运营商 ACTIVE 服务关系；
  2. 建立新运营商 ACTIVE 服务关系；
  3. 更新号码归属；
  4. 消费授权码（一次性）。
- 任何一步失败整体回滚：**不会出现双归属（两条 ACTIVE 关系）或无归属号码**。
- 切换成功后生成回退截止时间（默认切换后 48 小时，配置项
  `number-porting.rollback-window`）。

### 4. 并发与唯一结果

- 所有写操作在单数据库事务内按固定顺序加悲观行锁：**号码行 → 申请行 → 授权码行**，
  使同一号码的状态迁移串行化，并避免多号码交叉死锁。
- 数据库层另有两道唯一约束兜底：`port_order.request_id` 唯一（幂等）、
  `phone_number.active_order_id` 唯一（唯一活动名额）。
- 因此：
  - 并发提交同 requestId：只产生一笔申请，全部调用方拿到同一结果；
  - 并发提交不同 requestId / 并发执行切换：**最多一笔成功**，其余得到可解释的业务错误；
  - 取消与切换并发、授权码失效与切换并发：最终状态唯一且可解释——
    要么 `SWITCHED`（归属已切、授权码已消费），要么 `CANCELLED` /
    `AUTH_CODE_EXPIRED`（归属不动、名额释放），二者必居其一。
- 与授权码失效竞争而失败的切换，会先在独立事务中把申请落为 `AUTH_CODE_EXPIRED`，
  再向调用方返回错误，确保"申请已关闭"这一最终状态不会因报错回滚而丢失。

### 5. 受控回退

- 切换后在回退窗口（默认 48h）内可以回退；回退同样是**原子事务**：
  关闭新运营商关系、恢复原运营商关系（同一条记录复活，而非新建）、
  归属改回原运营商、释放活动名额。
- 超过回退窗口不能回退（409 `ROLLBACK_WINDOW_CLOSED`），**只能创建新的携转申请**；
  创建新申请时旧申请自动收尾为 `COMPLETED`。
- 已切换申请在窗口内只能回退、不能取消；待审核/待切换申请可取消。

### 6. 不可变事件与查询

- 每次状态变化都追加一条 `port_event`（创建/审核通过/取消/授权码失效/切换/回退/窗口关闭），
  记录前后状态、详情、发生时间。**事件只允许插入**：JPA 生命周期回调
  （`@PreUpdate`/`@PreRemove`）直接拒绝任何修改与删除。
- 查询接口：
  - 申请详情：`GET /api/port-orders/{id}`
  - 号码归属（含归属与 ACTIVE 服务关系一致性自检）：`GET /api/numbers/{number}/ownership`
  - 号码切换事件时间线：`GET /api/numbers/{number}/timeline`
  - 单申请事件时间线：`GET /api/port-orders/{id}/timeline`
  - 号码的全部申请：`GET /api/numbers/{number}/port-orders`

## API 一览

| 方法 & 路径 | 说明 |
| --- | --- |
| `POST /api/port-orders` | 提交携转申请（body 含 requestId，幂等） |
| `POST /api/port-orders/{id}/approval` | 审核通过 |
| `POST /api/port-orders/{id}/switch` | 在窗口内执行切换 |
| `POST /api/port-orders/{id}/cancellation?reason=` | 取消申请 |
| `POST /api/port-orders/{id}/rollback?reason=` | 受控回退 |
| `POST /api/port-orders/{id}/auth-code-expiry` | 授权码到期失效处理 |
| `GET  /api/port-orders/{id}` | 申请详情 |
| `GET  /api/numbers/{number}/ownership` | 号码归属 |
| `GET  /api/numbers/{number}/timeline` | 号码事件时间线 |
| `GET  /api/port-orders/{id}/timeline` | 申请事件时间线 |
| `POST /api/admin/carriers` | 维护运营商（联调用） |
| `POST /api/admin/numbers` | 号码入网，建立初始归属与服务关系（联调用） |
| `POST /api/admin/authorization-codes` | 原运营商签发授权码（联调用） |
| `POST /api/admin/authorization-codes/{code}/revocation` | 主动失效授权码 |

业务错误统一返回：HTTP 状态码 + 稳定错误码（如 `ACTIVE_ORDER_EXISTS`、
`AUTH_CODE_EXPIRED`、`ROLLBACK_WINDOW_CLOSED`）+ 可解释信息。

申请请求体示例：

```json
{
  "requestId": "REQ-20260927-0001",
  "phoneNumber": "13800000001",
  "fromCarrier": "CMCC",
  "toCarrier": "CUCC",
  "authCode": "AC-8f3c...",
  "windowStart": "2026-09-28T01:00:00Z",
  "windowEnd":   "2026-09-28T05:00:00Z"
}
```

## 自动化测试

| 测试类 | 覆盖内容 |
| --- | --- |
| `PortOrderLifecycleTest` | 申请→审核→切换→回退主流程、窗口校验、超窗回退与重新申请、取消 |
| `BusinessRulesTest` | requestId 幂等、唯一活动申请、授权码一次性/有效期/吊销、事件顺序与不可修改 |
| `ConcurrencyTest` | 并发同/异 requestId 提交、并发切换、取消↔切换、吊销↔切换、并发回退、混合竞争下无双重/无归属 |
| `SwitchAtomicityFailureTest` | 切换中途数据库故障注入：整笔回滚、归属与授权码不被半更新、恢复后可重试 |
| `PortingApiTest` | REST 端到端：接口状态码、错误码、幂等响应、时间线查询 |

测试使用可控时钟（`MutableClock`）精确驱动授权码到期、切换窗口与回退窗口。
