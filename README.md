# cc-number-porting

号码归属与运营商服务关系管理服务：实现手机号码从原运营商携转到新运营商的**申请、审核、原子切换与受控回退**，
并提供申请详情、号码归属查询和不可变的切换事件时间线。

## 开发环境

- JDK 21
- Maven Wrapper 3.9.9
- Spring Boot 4.1.1 / Spring Data JPA / H2（内置库，可替换为其他关系库）

## 常用命令

运行测试：

    ./mvnw clean test

启动服务：

    ./mvnw spring-boot:run

## 核心业务规则

### 1. 携转申请

申请包含：号码、原运营商（donor）、新运营商（recipient）、授权码、期望切换窗口 `[windowStart, windowEnd)`。

- **同一号码同一时刻最多一笔活动申请**。活动状态为 `PENDING_REVIEW`、`APPROVED`、`SWITCHED`（回退窗口内）；
  进入终态（`CANCELLED` / `REJECTED` / `EXPIRED` / `ROLLED_BACK` / 回退窗口结束）后释放名额。
  数据库通过 `porting_application(number, active_slot)` 部分唯一约束兜底，应用层用号码行悲观锁串行化。
- **申请号（applicationId）保证幂等**：相同申请号 + 相同载荷的重复/并发提交返回首笔结果，不重复消费授权码；
  申请号相同但载荷不一致返回 `IDEMPOTENCY_MISMATCH`（422）。
- **授权码只能成功使用一次且有明确有效期**：授权码绑定号码与签发运营商（号码当前归属运营商），
  状态为 `ISSUED / CONSUMED / REVOKED / EXPIRED`。提交时在悲观行锁内消费：已使用、已撤销、已过期
  （含到达有效期时刻的即时判定）均拒绝，整笔申请不产生。授权码被撤销或自然到期时，引用它的活动申请在
  同一事务内联动失效为 `EXPIRED`。
- 申请还会校验：原/新运营商不同、原运营商等于号码当前归属、切换窗口合法。

### 2. 审核与待切换

审核通过后申请进入 `APPROVED`（待切换）；审核拒绝为终态 `REJECTED`。
`PENDING_REVIEW / APPROVED` 状态可由用户取消（`CANCELLED`）。
若审核或切换发生在期望切换窗口结束之后，申请置为 `EXPIRED` 并释放活动名额。

### 3. 切换的原子性

执行切换在**单个数据库事务**内依次完成：

1. 锁定号码行与申请行（固定锁顺序：号码行 → 申请行 → 服务关系行）；
2. 关闭号码在原运营商的 ACTIVE 服务关系；
3. 在新运营商建立新的 ACTIVE 服务关系；
4. 原子更新号码归属为新运营商；
5. 提交前做不变量校验：**恰好一条 ACTIVE 服务关系，且其运营商与号码归属一致**。

任一步失败（含下游开通故障）整体回滚：**不会留下双归属，也不会留下无归属号码**；
失败以独立事务补记 `SWITCH_FAILED` 事件，申请回到可重试的 `APPROVED` 状态。

### 4. 并发语义

取消、切换、授权码失效、重复提交并发时：

- 所有同号码写路径先抢号码行悲观锁，全局锁顺序一致（号码 → 授权码/申请 → 关系），无死锁；
- **最多一笔成功**；失败方得到唯一、可解释的错误码（`ACTIVE_APPLICATION_EXISTS`、
  `AUTH_CODE_ALREADY_USED/EXPIRED/REVOKED`、`ILLEGAL_TRANSITION`、`CONCURRENT_MODIFICATION` 等）；
- 最终状态与号码归属、服务关系始终自洽（并发测试以 8 线程栅栏并发验证）。

### 5. 受控回退

- 切换完成后进入 `SWITCHED`，并确定**回退截止时间**（默认切换后 24 小时，
  由 `number-porting.rollback-window` 配置）。
- 窗口内可发起回退：同样在单事务内关闭新运营商关系、重建原运营商关系并恢复归属，提交前做相同不变量校验，
  终态为 `ROLLED_BACK`，服务关系完整历史保留。
- **超过回退窗口只能创建新的携转申请**：迟到的回退请求返回 `ROLLBACK_WINDOW_CLOSED`，
  申请结束生命周期并释放活动名额，号码归属保持在新运营商。

### 6. 不可变事件与查询

- 每一次状态变化都在同一事务追加一条 `porting_event`（提交、审核通过/拒绝、取消、失效、
  切换开始/完成/失败、回退开始/完成/失败、授权码撤销/到期、回退窗口结束）。
- 事件表只增不改不删：字段 `updatable=false` + JPA 删除回调拒绝 + 数据库层触发器
  （生产库以等价触发器/只授予 INSERT 与 SELECT 权限实现）。
- 查询接口：申请详情、号码当前归属（含活动申请）、号码/申请两个维度的事件时间线、
  号码服务关系开通/关闭历史。

## 申请状态机

```
PENDING_REVIEW ──approve──▶ APPROVED ──switch──▶ SWITCHED ──rollback(窗口内)──▶ ROLLED_BACK
      │                         │                    │
      ├──reject──▶ REJECTED     ├──cancel──▶ CANCELLED └─窗口结束──▶ (终态,归属留在新运营商)
      ├──cancel──▶ CANCELLED    └──窗口外/授权码失效──▶ EXPIRED
      └──授权码失效/窗口超时──▶ EXPIRED
```

## REST API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/numbers/provision` | 号码入网，建立初始归属与 ACTIVE 服务关系 |
| GET  | `/api/numbers/{number}` | 号码归属查询（当前运营商 + 活动申请） |
| GET  | `/api/numbers/{number}/relationships` | 服务关系历史 |
| GET  | `/api/numbers/{number}/events` | 号码维度事件时间线 |
| POST | `/api/auth-codes` | 签发授权码（可指定有效期分钟数） |
| POST | `/api/auth-codes/{code}/revoke` | 撤销授权码（联动活动申请失效） |
| POST | `/api/port-applications` | 提交携转申请（申请号幂等） |
| GET  | `/api/port-applications/{id}` | 申请详情 |
| GET  | `/api/port-applications/{id}/events` | 申请维度事件时间线 |
| POST | `/api/port-applications/{id}/approve` | 审核通过 |
| POST | `/api/port-applications/{id}/reject` | 审核拒绝 |
| POST | `/api/port-applications/{id}/cancel` | 取消 |
| POST | `/api/port-applications/{id}/switch` | 执行原子切换 |
| POST | `/api/port-applications/{id}/rollback` | 窗口内受控回退 |

错误响应统一为 `{"errorCode": "...", "message": "..."}`：404 资源不存在、409 状态/并发冲突、
422 幂等载荷冲突、400 参数校验失败、500 切换/回退执行故障（已回滚，可重试）。

## 自动化测试

`src/test` 下覆盖（共 29 个用例）：

- 完整 提交→审核→切换→回退 链路，校验归属、服务关系与事件时间线；
- 申请号幂等（含载荷不一致）、唯一活动申请、取消后可再申请；
- 授权码单次消费、有效期、号码/运营商绑定、撤销与自然到期扫描；
- **原子切换故障注入**：在“旧关系已关闭、新关系未建立”处抛错，验证整体回滚、无双/无归属且可重试；
- **并发测试**（8 线程）：并发提交、同申请号重复提交、并发切换、取消 vs 切换、授权码撤销 vs 提交，
  断言最多一笔成功、失败错误码明确、最终归属自洽；
- 切换窗口/回退窗口边界、非法状态迁移、超窗后只能新申请；
- 事件不可变（数据库触发器拒绝 UPDATE/DELETE）；
- REST 端到端（成功链路、幂等重放、校验/404/409 错误码）。

测试使用可控时钟（`MutableClock`）驱动有效期与窗口，后台扫描默认关闭
（`number-porting.scheduling-enabled=false`），可手动调用 sweep 服务验证。
