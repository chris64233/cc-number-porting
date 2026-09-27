package com.chris64233.numberporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.chris64233.numberporting.domain.PortOrderStatus;
import com.chris64233.numberporting.domain.ServiceStatus;
import com.chris64233.numberporting.service.BusinessRuleException;
import com.chris64233.numberporting.service.ErrorCode;
import com.chris64233.numberporting.support.AbstractPortingIntegrationTest;
import com.chris64233.numberporting.support.TestClockConfig;

/** 申请、审核、切换、回退主流程及核心业务规则测试。 */
class PortOrderLifecycleTest extends AbstractPortingIntegrationTest {

    @Test
    void full_lifecycle_apply_approve_switch_rollback() {
        register("13800000001", CMCC);
        issueCode("AC-1", "13800000001", Duration.ofHours(12));

        var order = submitOrder("REQ-1", "13800000001", CMCC, CUCC, "AC-1");
        assertThat(order.getStatus()).isEqualTo(PortOrderStatus.PENDING_REVIEW);

        var approved = orderService.approve(order.getId());
        assertThat(approved.getStatus()).isEqualTo(PortOrderStatus.APPROVED);
        assertThat(approved.getApprovedAt()).isNotNull();

        var switched = orderService.switch_(order.getId());
        assertThat(switched.getStatus()).isEqualTo(PortOrderStatus.SWITCHED);
        assertThat(switched.getSwitchedAt()).isNotNull();
        assertThat(switched.getRollbackDeadline())
                .isEqualTo(TestClockConfig.now().plus(Duration.ofHours(48)));

        // 归属已更新、授权码一次性消费。
        var ownership = queryService.getOwnership("13800000001");
        assertThat(ownership.number().getCurrentCarrier().getCode()).isEqualTo(CUCC);
        assertThat(ownership.ownershipConsistent()).isTrue();
        assertThat(authCodes.findByCode("AC-1").orElseThrow().isUsed()).isTrue();
        assertThat(relations.countByPhoneNumberAndStatus("13800000001", ServiceStatus.ACTIVE))
                .isEqualTo(1);

        // 回退窗口内回退，原归属与服务关系完整恢复。
        var rolledBack = orderService.rollback(order.getId(), "用户要求回退");
        assertThat(rolledBack.getStatus()).isEqualTo(PortOrderStatus.ROLLED_BACK);
        var restored = queryService.getOwnership("13800000001");
        assertThat(restored.number().getCurrentCarrier().getCode()).isEqualTo(CMCC);
        assertThat(restored.ownershipConsistent()).isTrue();
        assertThat(restored.number().getActiveOrderId()).isNull();
        assertThat(relations.countByPhoneNumberAndStatus("13800000001", ServiceStatus.ACTIVE))
                .isEqualTo(1);
        // 恢复的是同一条原始服务关系（不是新建）。
        var allRelations = relations.findByPhoneNumberOrderByIdAsc("13800000001");
        assertThat(allRelations).hasSize(2);
        assertThat(allRelations.get(0).getStatus()).isEqualTo(ServiceStatus.ACTIVE);
        assertThat(allRelations.get(0).getCarrier().getCode()).isEqualTo(CMCC);
        assertThat(allRelations.get(1).getStatus()).isEqualTo(ServiceStatus.CLOSED);
        assertThat(allRelations.get(1).getCarrier().getCode()).isEqualTo(CUCC);
    }

    @Test
    void switch_requires_approved_status() {
        register("13800000002", CMCC);
        issueCode("AC-2", "13800000002");
        var order = submitOrder("REQ-2", "13800000002", CMCC, CUCC, "AC-2");

        assertThatThrownBy(() -> orderService.switch_(order.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).getErrorCode())
                .isEqualTo(ErrorCode.ILLEGAL_ORDER_STATUS);
    }

    @Test
    void switch_outside_window_rejected() {
        register("13800000003", CMCC);
        issueCode("AC-3", "13800000003");
        Instant now = TestClockConfig.now();
        var order = orderService.createOrder("REQ-3", "13800000003", CMCC, CUCC, "AC-3",
                now.plusSeconds(3600), now.plusSeconds(7200));
        orderService.approve(order.getId());

        assertThatThrownBy(() -> orderService.switch_(order.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).getErrorCode())
                .isEqualTo(ErrorCode.OUTSIDE_SWITCH_WINDOW);
    }

    @Test
    void rollback_after_window_rejected_and_new_order_allowed() {
        register("13800000004", CMCC);
        issueCode("AC-4", "13800000004");
        var switched = switchedOrder("REQ-4", "13800000004", CMCC, CUCC, "AC-4");

        TestClockConfig.advance(Duration.ofHours(49));
        assertThatThrownBy(() -> orderService.rollback(switched.getId(), "超窗口回退"))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).getErrorCode())
                .isEqualTo(ErrorCode.ROLLBACK_WINDOW_CLOSED);

        // 归属仍在新运营商，未被破坏。
        assertThat(queryService.getOwnership("13800000004").number()
                .getCurrentCarrier().getCode()).isEqualTo(CUCC);

        // 超过窗口只能创建新的携转申请：旧申请被收尾为 COMPLETED，新申请可创建。
        issueCode("AC-4B", "13800000004", Duration.ofHours(12));
        var second = submitOrder("REQ-4B", "13800000004", CUCC, CTCC, "AC-4B");
        assertThat(second.getStatus()).isEqualTo(PortOrderStatus.PENDING_REVIEW);
        var first = orders.findById(switched.getId()).orElseThrow();
        assertThat(first.getStatus()).isEqualTo(PortOrderStatus.COMPLETED);
        assertThat(queryService.getOwnership("13800000004").number().getActiveOrderId())
                .isEqualTo(second.getId());
    }

    @Test
    void cancel_releases_active_slot() {
        register("13800000005", CMCC);
        issueCode("AC-5", "13800000005");
        var order = submitOrder("REQ-5", "13800000005", CMCC, CUCC, "AC-5");

        var cancelled = orderService.cancel(order.getId(), "填错资料");
        assertThat(cancelled.getStatus()).isEqualTo(PortOrderStatus.CANCELLED);
        assertThat(queryService.getOwnership("13800000005").number().getActiveOrderId()).isNull();

        // 释放后可用新 requestId 再次申请。
        var again = submitOrder("REQ-5B", "13800000005", CMCC, CUCC, "AC-5");
        assertThat(again.getId()).isNotEqualTo(order.getId());
    }

    @Test
    void cancel_switched_order_rejected_only_rollback_allowed() {
        register("13800000006", CMCC);
        issueCode("AC-6", "13800000006");
        var switched = switchedOrder("REQ-6", "13800000006", CMCC, CUCC, "AC-6");

        assertThatThrownBy(() -> orderService.cancel(switched.getId(), "试图取消已切换"))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).getErrorCode())
                .isEqualTo(ErrorCode.ILLEGAL_ORDER_STATUS);
    }
}
