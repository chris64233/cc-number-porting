package com.chris64233.numberporting.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.chris64233.numberporting.domain.AuthorizationCode;
import com.chris64233.numberporting.domain.Carrier;
import com.chris64233.numberporting.domain.PhoneNumber;
import com.chris64233.numberporting.domain.PortEvent;
import com.chris64233.numberporting.domain.PortOrder;
import com.chris64233.numberporting.domain.PortOrderStatus;
import com.chris64233.numberporting.domain.ServiceRelation;
import com.chris64233.numberporting.domain.ServiceStatus;
import com.chris64233.numberporting.repository.AuthorizationCodeRepository;
import com.chris64233.numberporting.repository.CarrierRepository;
import com.chris64233.numberporting.repository.PhoneNumberRepository;
import com.chris64233.numberporting.repository.PortEventRepository;
import com.chris64233.numberporting.repository.PortOrderRepository;
import com.chris64233.numberporting.repository.ServiceRelationRepository;

/**
 * 携转申请生命周期服务。
 *
 * <p>并发控制：所有写操作在单事务内按固定顺序加悲观行锁
 * （号码行 → 申请行 → 授权码行），避免死锁并保证同一号码的所有状态迁移串行化；
 * 另有 request_id 唯一约束与 active_order_id 唯一约束作为数据库层兜底。
 */
@Service
public class PortOrderService {

    private static final List<PortOrderStatus> ACTIVE_STATUSES =
            List.of(PortOrderStatus.PENDING_REVIEW, PortOrderStatus.APPROVED,
                    PortOrderStatus.SWITCHED);

    private final Clock clock;
    private final PortingProperties properties;
    private final PortOrderRepository orders;
    private final PhoneNumberRepository numbers;
    private final CarrierRepository carriers;
    private final AuthorizationCodeRepository authCodes;
    private final ServiceRelationRepository relations;
    private final PortEventRepository events;
    private final TransactionTemplate txTemplate;

    public PortOrderService(Clock clock,
                            PortingProperties properties,
                            PortOrderRepository orders,
                            PhoneNumberRepository numbers,
                            CarrierRepository carriers,
                            AuthorizationCodeRepository authCodes,
                            ServiceRelationRepository relations,
                            PortEventRepository events,
                            org.springframework.transaction.PlatformTransactionManager txManager) {
        this.clock = clock;
        this.properties = properties;
        this.orders = orders;
        this.numbers = numbers;
        this.carriers = carriers;
        this.authCodes = authCodes;
        this.relations = relations;
        this.events = events;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * 提交携转申请（幂等）。
     *
     * <p>同一 requestId 的重复提交（含并发）返回同一笔申请；内容不一致返回 409。
     * 同一号码已有活动申请时拒绝；若上一笔已切换且回退窗口已过，
     * 先将旧申请收尾为 COMPLETED（释放活动名额），再创建新申请。
     */
    @Transactional
    public PortOrder createOrder(String requestId, String number, String fromCarrierCode,
                                 String toCarrierCode, String authCodeValue,
                                 Instant windowStart, Instant windowEnd) {
        Instant now = Instant.now(clock);
        validateWindow(windowStart, windowEnd);
        if (fromCarrierCode.equals(toCarrierCode)) {
            throw new BusinessRuleException(ErrorCode.SAME_CARRIER);
        }

        // 1. 号码行锁：串行化同一号码上的所有申请/切换/取消/回退。
        PhoneNumber phone = numbers.findForUpdate(number)
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.NUMBER_NOT_FOUND, number));

        // 2. 幂等：同 requestId 直接返回原申请（校验内容一致）。
        PortOrder existing = orders.findByRequestId(requestId).orElse(null);
        if (existing != null) {
            return reconcileIdempotent(existing, number, fromCarrierCode, toCarrierCode,
                    authCodeValue, windowStart, windowEnd);
        }

        Carrier from = requireCarrier(fromCarrierCode);
        Carrier to = requireCarrier(toCarrierCode);
        if (!phone.getCurrentCarrier().getCode().equals(fromCarrierCode)) {
            throw new BusinessRuleException(ErrorCode.CARRIER_MISMATCH,
                    "当前归属为 " + phone.getCurrentCarrier().getCode());
        }

        // 3. 授权码存在性与归属校验（切换时再次校验一次性/有效期）。
        //    先校验授权码，再处理旧申请收尾，避免无效请求错误地把旧申请推进终态。
        AuthorizationCode code = authCodes.findByCode(authCodeValue)
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.AUTH_CODE_NOT_FOUND,
                        authCodeValue));
        if (!code.isUsable(number, from, now)) {
            throw unusableCodeError(code, now);
        }

        // 4. 活动名额检查。窗口已过的旧切换先收尾。
        if (phone.getActiveOrderId() != null) {
            PortOrder active = orders.findByIdForUpdate(phone.getActiveOrderId()).orElseThrow();
            if (active.getStatus() == PortOrderStatus.SWITCHED
                    && !isWithinRollbackWindow(active, now)) {
                finalizeCompleted(active, now, "回退窗口已关闭，受理新的携转申请");
                phone.setActiveOrderId(null);
            } else {
                throw new BusinessRuleException(ErrorCode.ACTIVE_ORDER_EXISTS,
                        "活动申请 id=" + active.getId() + ", 状态=" + active.getStatus());
            }
        }

        PortOrder order;
        try {
            order = orders.save(new PortOrder(requestId, number, from, to,
                    authCodeValue, windowStart, windowEnd, now));
            orders.flush();
            phone.setActiveOrderId(order.getId());
            numbers.save(phone);
        } catch (DataIntegrityViolationException e) {
            // request_id / active_order_id 唯一约束的并发兜底。
            throw new BusinessRuleException(ErrorCode.ACTIVE_ORDER_EXISTS,
                    "并发提交冲突，请稍后重试");
        }
        recordEvent(order, PortEvent.Type.ORDER_CREATED, null, PortOrderStatus.PENDING_REVIEW,
                "申请已提交，期望窗口 " + windowStart + " ~ " + windowEnd, now);
        return order;
    }

    /** 审核通过：待审核 → 待切换。 */
    @Transactional
    public PortOrder approve(Long orderId) {
        Instant now = Instant.now(clock);
        PortOrder order = lockOrderById(orderId);
        if (order.getStatus() != PortOrderStatus.PENDING_REVIEW) {
            throw illegalStatus(order, "审核通过");
        }
        order.markApproved(now);
        recordEvent(order, PortEvent.Type.ORDER_APPROVED,
                PortOrderStatus.PENDING_REVIEW, PortOrderStatus.APPROVED, null, now);
        return order;
    }

    /**
     * 执行切换：待切换 → 已切换。
     *
     * <p>在同一事务、同一行锁保护下原子完成：
     * 关闭旧服务关系、建立新服务关系、更新号码归属、消费授权码。
     * 任一步失败整体回滚，不会留下双归属或无归属号码。
     *
     * <p>与授权码失效竞争且失效方胜出时，失效终态在独立事务中先落库，
     * 事务成功提交后再向调用方抛出可解释错误，保证"申请已关闭"这一最终状态不丢失。
     */
    public PortOrder switch_(Long orderId) {
        SwitchResult result = txTemplate.execute(status -> doSwitch(orderId));
        if (result.failedCode() != null) {
            throw unusableCodeError(result.failedCode(), Instant.now(clock));
        }
        return result.order();
    }

    /** 切换事务体，返回切换成功的申请，或竞争失败时的失效授权码。 */
    private SwitchResult doSwitch(Long orderId) {
        Instant now = Instant.now(clock);

        PhoneNumber phone = lockPhone(orderId);
        PortOrder order = lockActiveOrder(phone, orderId);
        if (order.getStatus() != PortOrderStatus.APPROVED) {
            throw illegalStatus(order, "执行切换");
        }
        if (!order.isWithinSwitchWindow(now)) {
            throw new BusinessRuleException(ErrorCode.OUTSIDE_SWITCH_WINDOW,
                    "窗口 " + order.getWindowStart() + " ~ " + order.getWindowEnd()
                            + "，当前 " + now);
        }

        AuthorizationCode code = authCodes.findByCodeForUpdate(order.getAuthCode())
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.AUTH_CODE_NOT_FOUND,
                        order.getAuthCode()));
        if (!code.isUsable(order.getPhoneNumber(), order.getFromCarrier(), now)) {
            // 授权码失效在与切换的竞争中胜出：申请先落到唯一可解释的终态并提交。
            expireOrder(phone, order, code, now);
            return new SwitchResult(null, code);
        }

        Carrier to = order.getToCarrier();

        // 原子变更四步，全部在本事务内：
        ServiceRelation oldRelation = relations
                .findFirstByPhoneNumberAndStatusOrderByIdDesc(order.getPhoneNumber(),
                        ServiceStatus.ACTIVE)
                .orElseThrow(() -> new IllegalStateException(
                        "号码缺少 ACTIVE 服务关系: " + order.getPhoneNumber()));
        if (!oldRelation.getCarrier().getCode().equals(order.getFromCarrier().getCode())) {
            throw new IllegalStateException("当前 ACTIVE 服务关系与申请的原运营商不一致，拒绝切换");
        }
        oldRelation.close("PORT_OUT", now);                                   // 1) 关闭旧关系
        relations.save(ServiceRelation.portIn(order.getPhoneNumber(), to, now)); // 2) 建立新关系
        phone.setCurrentCarrier(to);                                          // 3) 更新归属
        code.markUsed(order.getId(), now);                                    // 4) 消费授权码

        Instant deadline = now.plus(properties.rollbackWindow());
        order.markSwitched(now, deadline);
        recordEvent(order, PortEvent.Type.SWITCHED,
                PortOrderStatus.APPROVED, PortOrderStatus.SWITCHED,
                "归属 " + order.getFromCarrier().getCode() + " -> " + to.getCode()
                        + "；旧服务关系已关闭、新服务关系已建立；回退截止 " + deadline, now);
        return new SwitchResult(order, null);
    }

    /** 切换事务结果：成功携带申请；授权码失效竞争失败携带失效授权码。 */
    private record SwitchResult(PortOrder order, AuthorizationCode failedCode) {
    }

    /** 取消申请：待审核/待切换 → 已取消，释放活动名额。已切换申请在回退窗口内不可取消。 */
    @Transactional
    public PortOrder cancel(Long orderId, String reason) {
        Instant now = Instant.now(clock);
        PhoneNumber phone = lockPhone(orderId);
        PortOrder order = lockActiveOrder(phone, orderId);
        return switch (order.getStatus()) {
            case PENDING_REVIEW, APPROVED -> {
                PortOrderStatus from = order.getStatus();
                order.markCancelled(now, reason);
                phone.setActiveOrderId(null);
                recordEvent(order, PortEvent.Type.ORDER_CANCELLED,
                        from, PortOrderStatus.CANCELLED, reason, now);
                yield order;
            }
            case SWITCHED -> throw new BusinessRuleException(ErrorCode.ILLEGAL_ORDER_STATUS,
                    "已切换申请在回退窗口内只能回退，不能取消；回退截止 "
                            + order.getRollbackDeadline());
            default -> throw illegalStatus(order, "取消");
        };
    }

    /**
     * 受控回退：已切换且在回退窗口内 → 已回退。
     *
     * <p>在同一事务内完整恢复：关闭新运营商服务关系、恢复原运营商服务关系、
     * 归属改回原运营商、释放活动名额。超过窗口抛出 ROLLBACK_WINDOW_CLOSED，
     * 调用方只能创建新的携转申请。
     */
    @Transactional
    public PortOrder rollback(Long orderId, String reason) {
        Instant now = Instant.now(clock);
        PhoneNumber phone = lockPhone(orderId);
        PortOrder order = lockActiveOrder(phone, orderId);
        if (order.getStatus() != PortOrderStatus.SWITCHED) {
            throw illegalStatus(order, "受控回退");
        }
        if (!isWithinRollbackWindow(order, now)) {
            throw new BusinessRuleException(ErrorCode.ROLLBACK_WINDOW_CLOSED,
                    "回退截止 " + order.getRollbackDeadline() + "，当前 " + now
                            + "；请创建新的携转申请");
        }

        String number = order.getPhoneNumber();

        // 关闭新运营商关系。
        ServiceRelation newRelation = relations
                .findFirstByPhoneNumberAndStatusAndCarrier_CodeOrderByIdDesc(
                        number, ServiceStatus.ACTIVE, order.getToCarrier().getCode())
                .orElseThrow(() -> new IllegalStateException(
                        "缺少新运营商 ACTIVE 服务关系，无法回退: " + number));
        newRelation.close("ROLLBACK_CLOSE", now);

        // 恢复原运营商关系（切换时以 PORT_OUT 关闭的那一条）。
        ServiceRelation originalRelation = relations
                .findFirstByPhoneNumberAndStatusAndCarrier_CodeOrderByIdDesc(
                        number, ServiceStatus.CLOSED, order.getFromCarrier().getCode())
                .orElseThrow(() -> new IllegalStateException(
                        "找不到被切换关闭的原服务关系，无法回退: " + number));
        if (!"PORT_OUT".equals(originalRelation.getCloseReason())) {
            throw new IllegalStateException("原服务关系不是由本次携转关闭，拒绝恢复: " + number);
        }
        originalRelation.reopen(now);

        phone.setCurrentCarrier(order.getFromCarrier());
        phone.setActiveOrderId(null);
        order.markRolledBack(now);
        recordEvent(order, PortEvent.Type.ROLLED_BACK,
                PortOrderStatus.SWITCHED, PortOrderStatus.ROLLED_BACK,
                "归属已恢复为 " + order.getFromCarrier().getCode()
                        + "，原服务关系已恢复、新服务关系已关闭。原因：" + reason, now);
        return order;
    }

    /**
     * 授权码主动失效（吊销）：授权码立即不可用，所有引用它的活动申请落到
     * AUTH_CODE_EXPIRED 终态并释放活动名额。锁顺序与其他操作一致（号码→申请→授权码）。
     */
    @Transactional
    public AuthorizationCode revokeAuthCode(String authCodeValue, String reason) {
        Instant now = Instant.now(clock);

        List<PortOrder> candidates = orders.findByAuthCodeAndStatusIn(
                authCodeValue, ACTIVE_STATUSES);
        // 先按号码排序后逐一加号码行锁，消除多号码间的死锁可能。
        candidates.stream()
                .map(PortOrder::getPhoneNumber)
                .distinct()
                .sorted()
                .forEach(n -> numbers.findForUpdate(n));
        AuthorizationCode code = authCodes.findByCodeForUpdate(authCodeValue)
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.AUTH_CODE_NOT_FOUND,
                        authCodeValue));
        code.revoke(now);

        for (PortOrder order : orders.findByAuthCodeAndStatusInForUpdate(
                authCodeValue, ACTIVE_STATUSES)) {
            PhoneNumber phone = numbers.findById(order.getPhoneNumber()).orElseThrow();
            if (order.getStatus() == PortOrderStatus.SWITCHED) {
                // 已切换申请占用活动名额仅用于回退，授权码失效不影响其回退权利。
                continue;
            }
            expireOrder(phone, order, code, now, reason);
        }
        return code;
    }

    /**
     * 申请维度的授权码失效处理（定时巡检/显式调用）：
     * 待审核或待切换申请若授权码已过期，关闭为 AUTH_CODE_EXPIRED。
     */
    @Transactional
    public PortOrder expireOrderIfCodeExpired(Long orderId) {
        Instant now = Instant.now(clock);
        PhoneNumber phone = lockPhone(orderId);
        PortOrder order = lockActiveOrder(phone, orderId);
        if (order.getStatus() != PortOrderStatus.PENDING_REVIEW
                && order.getStatus() != PortOrderStatus.APPROVED) {
            throw illegalStatus(order, "授权码失效处理");
        }
        AuthorizationCode code = authCodes.findByCodeForUpdate(order.getAuthCode())
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.AUTH_CODE_NOT_FOUND,
                        order.getAuthCode()));
        if (code.isUsable(order.getPhoneNumber(), order.getFromCarrier(), now)) {
            throw new BusinessRuleException(ErrorCode.ILLEGAL_ORDER_STATUS,
                    "授权码仍在有效期内，不能失效处理");
        }
        expireOrder(phone, order, code, now);
        return order;
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private void expireOrder(PhoneNumber phone, PortOrder order, AuthorizationCode code,
                             Instant now) {
        expireOrder(phone, order, code, now, code.isUsed() ? "授权码已被使用" : "授权码已失效");
    }

    private void expireOrder(PhoneNumber phone, PortOrder order, AuthorizationCode code,
                             Instant now, String reason) {
        PortOrderStatus from = order.getStatus();
        order.markAuthCodeExpired(now, reason);
        if (phone.getActiveOrderId() != null && phone.getActiveOrderId().equals(order.getId())) {
            phone.setActiveOrderId(null);
        }
        recordEvent(order, PortEvent.Type.AUTH_CODE_EXPIRED, from,
                PortOrderStatus.AUTH_CODE_EXPIRED, reason, now);
    }

    private void finalizeCompleted(PortOrder order, Instant now, String reason) {
        order.markCompleted(now, reason);
        recordEvent(order, PortEvent.Type.ORDER_COMPLETED,
                PortOrderStatus.SWITCHED, PortOrderStatus.COMPLETED, reason, now);
    }

    private boolean isWithinRollbackWindow(PortOrder order, Instant now) {
        return order.getRollbackDeadline() != null && !now.isAfter(order.getRollbackDeadline());
    }

    private void validateWindow(Instant start, Instant end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new BusinessRuleException(ErrorCode.INVALID_SWITCH_WINDOW);
        }
    }

    private PortOrder reconcileIdempotent(PortOrder existing, String number,
                                          String fromCarrierCode, String toCarrierCode,
                                          String authCodeValue, Instant start, Instant end) {
        boolean same = existing.getPhoneNumber().equals(number)
                && existing.getFromCarrier().getCode().equals(fromCarrierCode)
                && existing.getToCarrier().getCode().equals(toCarrierCode)
                && existing.getAuthCode().equals(authCodeValue)
                && existing.getWindowStart().equals(start)
                && existing.getWindowEnd().equals(end);
        if (!same) {
            throw new BusinessRuleException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "requestId=" + existing.getRequestId());
        }
        return existing;
    }

    private PhoneNumber lockPhone(Long orderId) {
        String number = orders.findPhoneNumberById(orderId)
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.ORDER_NOT_FOUND,
                        String.valueOf(orderId)));
        return numbers.findForUpdate(number)
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.NUMBER_NOT_FOUND, number));
    }

    private PortOrder lockOrderById(Long orderId) {
        return orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.ORDER_NOT_FOUND,
                        String.valueOf(orderId)));
    }

    /** 经号码行上的活动指针取申请并加锁，拒绝操作已不占名额的申请。 */
    private PortOrder lockActiveOrder(PhoneNumber phone, Long orderId) {
        PortOrder order = lockOrderById(orderId);
        if (phone.getActiveOrderId() == null
                || !phone.getActiveOrderId().equals(order.getId())) {
            throw new BusinessRuleException(ErrorCode.ILLEGAL_ORDER_STATUS,
                    "该申请不是号码的当前活动申请（当前状态=" + order.getStatus() + "）");
        }
        return order;
    }

    private Carrier requireCarrier(String code) {
        return carriers.findById(code)
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.CARRIER_NOT_FOUND, code));
    }

    private BusinessRuleException illegalStatus(PortOrder order, String action) {
        return new BusinessRuleException(ErrorCode.ILLEGAL_ORDER_STATUS,
                "不能在状态 " + order.getStatus() + " 下执行：" + action);
    }

    private BusinessRuleException unusableCodeError(AuthorizationCode code, Instant now) {
        if (code.isUsed()) {
            return new BusinessRuleException(ErrorCode.AUTH_CODE_USED, code.getCode());
        }
        if (code.isRevoked() || !now.isBefore(code.getExpiresAt())) {
            return new BusinessRuleException(ErrorCode.AUTH_CODE_EXPIRED,
                    code.getCode() + "，有效期至 " + code.getExpiresAt());
        }
        return new BusinessRuleException(ErrorCode.AUTH_CODE_INVALID, code.getCode());
    }

    private void recordEvent(PortOrder order, PortEvent.Type type,
                             PortOrderStatus from, PortOrderStatus to,
                             String detail, Instant now) {
        events.save(PortEvent.portEvent(order.getId(), order.getPhoneNumber(), type,
                from, to, detail, now));
    }
}
