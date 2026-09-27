package com.chris64233.numberporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.chris64233.numberporting.domain.PortEvent;
import com.chris64233.numberporting.domain.PortOrder;
import com.chris64233.numberporting.domain.PortOrderStatus;
import com.chris64233.numberporting.service.BusinessRuleException;
import com.chris64233.numberporting.service.ErrorCode;
import com.chris64233.numberporting.support.AbstractPortingIntegrationTest;
import com.chris64233.numberporting.support.EventTamper;
import com.chris64233.numberporting.support.TestClockConfig;

/** 幂等、唯一活动申请、授权码一次性与有效期、失效竞争、事件审计等业务规则。 */
class BusinessRulesTest extends AbstractPortingIntegrationTest {

    @Autowired
    private EventTamper eventTamper;

    @Test
    void duplicate_request_id_is_idempotent() {
        register("13900000001", CMCC);
        issueCode("BC-1", "13900000001");
        var first = submitOrder("IDEM-1", "13900000001", CMCC, CUCC, "BC-1");
        var second = submitOrder("IDEM-1", "13900000001", CMCC, CUCC, "BC-1");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(orders.count()).isEqualTo(1);
        // 只有一笔 ORDER_CREATED 事件。
        assertThat(events.findByOrderIdOrderByIdAsc(first.getId()))
                .filteredOn(e -> e.getType() == PortEvent.Type.ORDER_CREATED)
                .hasSize(1);
    }

    @Test
    void same_request_id_with_different_payload_conflicts() {
        register("13900000002", CMCC);
        issueCode("BC-2", "13900000002");
        submitOrder("IDEM-2", "13900000002", CMCC, CUCC, "BC-2");

        assertThatThrownBy(() -> submitOrder("IDEM-2", "13900000002", CMCC, CTCC, "BC-2"))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void only_one_active_order_per_number() {
        register("13900000003", CMCC);
        issueCode("BC-3", "13900000003");
        submitOrder("IDEM-3A", "13900000003", CMCC, CUCC, "BC-3");

        assertThatThrownBy(() -> submitOrder("IDEM-3B", "13900000003", CMCC, CTCC, "BC-3"))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVE_ORDER_EXISTS);
    }

    @Test
    void auth_code_can_only_be_used_once() {
        register("13900000004", CMCC);
        issueCode("BC-4", "13900000004");
        // 第一笔完成切换并回退，释放活动名额；授权码已被消费。
        var switched = switchedOrder("IDEM-4A", "13900000004", CMCC, CUCC, "BC-4");
        orderService.rollback(switched.getId(), "回退");

        assertThatThrownBy(() -> submitOrder("IDEM-4B", "13900000004", CMCC, CTCC, "BC-4"))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_CODE_USED);
    }

    @Test
    void expired_auth_code_rejected_at_creation() {
        register("13900000005", CMCC);
        issueCode("BC-5", "13900000005", Duration.ofMinutes(10));
        TestClockConfig.advance(Duration.ofMinutes(11));

        assertThatThrownBy(() -> submitOrder("IDEM-5", "13900000005", CMCC, CUCC, "BC-5"))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_CODE_EXPIRED);
    }

    @Test
    void auth_code_expiring_before_switch_closes_order_to_explainable_terminal_state() {
        register("13900000006", CMCC);
        issueCode("BC-6", "13900000006", Duration.ofMinutes(10));
        var order = approvedOrder("IDEM-6", "13900000006", CMCC, CUCC, "BC-6");

        TestClockConfig.advance(Duration.ofMinutes(11));
        assertThatThrownBy(() -> orderService.switch_(order.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_CODE_EXPIRED);

        PortOrder reloaded = orders.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PortOrderStatus.AUTH_CODE_EXPIRED);
        // 活动名额释放，可重新申请。
        assertThat(queryService.getOwnership("13900000006").number().getActiveOrderId()).isNull();
        assertThat(queryService.getOwnership("13900000006").number().getCurrentCarrier().getCode())
                .isEqualTo(CMCC);
    }

    @Test
    void revoke_auth_code_closes_pending_and_approved_orders() {
        register("13900000007", CMCC);
        issueCode("BC-7", "13900000007");
        var pending = submitOrder("IDEM-7P", "13900000007", CMCC, CUCC, "BC-7");

        orderService.revokeAuthCode("BC-7", "运营商侧吊销");

        assertThat(orders.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(PortOrderStatus.AUTH_CODE_EXPIRED);
        assertThat(queryService.getOwnership("13900000007").number().getActiveOrderId()).isNull();
        assertThat(events.findByOrderIdOrderByIdAsc(pending.getId()).getLast().getType())
                .isEqualTo(PortEvent.Type.AUTH_CODE_EXPIRED);
    }

    @Test
    void revoke_auth_code_closes_approved_order_and_switch_is_rejected() {
        register("13900000070", CMCC);
        issueCode("BC-70", "13900000070");
        var approved = approvedOrder("IDEM-70", "13900000070", CMCC, CUCC, "BC-70");

        orderService.revokeAuthCode("BC-70", "运营商侧吊销");

        assertThatThrownBy(() -> orderService.switch_(approved.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).getErrorCode())
                .isEqualTo(ErrorCode.ILLEGAL_ORDER_STATUS);
        assertThat(orders.findById(approved.getId()).orElseThrow().getStatus())
                .isEqualTo(PortOrderStatus.AUTH_CODE_EXPIRED);
    }

    @Test
    void revoke_after_switch_does_not_affect_rollback_right() {
        register("13900000008", CMCC);
        issueCode("BC-8", "13900000008");
        var switched = switchedOrder("IDEM-8", "13900000008", CMCC, CUCC, "BC-8");

        orderService.revokeAuthCode("BC-8", "事后吊销");
        // 已切换申请仍可正常回退。
        var rolledBack = orderService.rollback(switched.getId(), "窗口内回退");
        assertThat(rolledBack.getStatus()).isEqualTo(PortOrderStatus.ROLLED_BACK);
    }

    @Test
    void invalid_inputs_rejected() {
        register("13900000009", CMCC);
        issueCode("BC-9", "13900000009");
        Instant now = TestClockConfig.now();

        assertThatThrownBy(() -> orderService.createOrder("X1", "13900000009", CMCC, CMCC,
                "BC-9", now.minusSeconds(60), now.plusSeconds(60)))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).getErrorCode())
                .isEqualTo(ErrorCode.SAME_CARRIER);

        assertThatThrownBy(() -> orderService.createOrder("X2", "13900000009", CMCC, CUCC,
                "BC-9", now.plusSeconds(60), now))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_SWITCH_WINDOW);

        assertThatThrownBy(() -> submitOrder("X3", "13900000009", CUCC, CTCC, "BC-9"))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARRIER_MISMATCH);
    }

    @Test
    void timeline_records_every_state_transition_in_order() {
        register("13900000010", CMCC);
        issueCode("BC-10", "13900000010");
        var switched = switchedOrder("IDEM-10", "13900000010", CMCC, CUCC, "BC-10");
        orderService.rollback(switched.getId(), "回退");

        List<PortEvent> timeline = queryService.timelineOfNumber("13900000010");
        assertThat(timeline).extracting(PortEvent::getType).containsExactly(
                PortEvent.Type.ORDER_CREATED,
                PortEvent.Type.ORDER_APPROVED,
                PortEvent.Type.SWITCHED,
                PortEvent.Type.ROLLED_BACK);
        assertThat(timeline).extracting(PortEvent::getOccurredAt)
                .isSorted();
        assertThat(timeline).extracting(PortEvent::getToStatus).containsExactly(
                "PENDING_REVIEW", "APPROVED", "SWITCHED", "ROLLED_BACK");
    }

    @Test
    void events_are_immutable() {
        register("13900000011", CMCC);
        issueCode("BC-11", "13900000011");
        var order = submitOrder("IDEM-11", "13900000011", CMCC, CUCC, "BC-11");
        Long eventId = events.findByOrderIdOrderByIdAsc(order.getId()).get(0).getId();

        assertThat(causeChainOf(() -> eventTamper.tryUpdate(eventId)))
                .anyMatch(t -> t.getMessage() != null && t.getMessage().contains("事件不可修改"));
        assertThat(causeChainOf(() -> eventTamper.tryDelete(eventId)))
                .anyMatch(t -> t.getMessage() != null && t.getMessage().contains("事件不可删除"));
        // 被拒绝的篡改没有落库：事件内容不变。
        var reloaded = events.findById(eventId).orElseThrow();
        assertThat(reloaded.getDetail()).contains("申请已提交");
    }

    private static java.util.List<Throwable> causeChainOf(RunnableThrowing runnable) {
        try {
            runnable.run();
            throw new AssertionError("期望抛出异常");
        } catch (Throwable t) {
            java.util.List<Throwable> chain = new java.util.ArrayList<>();
            for (Throwable c = t; c != null; c = c.getCause()) {
                chain.add(c);
            }
            return chain;
        }
    }

    @FunctionalInterface
    private interface RunnableThrowing {
        void run() throws Throwable;
    }
}
