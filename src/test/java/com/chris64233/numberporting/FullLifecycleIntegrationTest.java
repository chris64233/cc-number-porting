package com.chris64233.numberporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.chris64233.numberporting.domain.Carrier;
import com.chris64233.numberporting.domain.PortingEventType;
import com.chris64233.numberporting.domain.PortingStatus;
import com.chris64233.numberporting.service.PortingService;
import com.chris64233.numberporting.service.view.ApplicationView;
import com.chris64233.numberporting.service.view.EventView;
import com.chris64233.numberporting.service.view.NumberOwnershipView;
import com.chris64233.numberporting.service.view.RelationshipView;

/**
 * 完整主链路：提交 → 审核 → 切换 → 回退，校验归属、服务关系、回退窗口与事件时间线。
 */
class FullLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final String NUMBER = "13800000001";

    @Autowired
    private PortingService portingService;

    @BeforeEach
    void setUp() {
        provision(NUMBER, Carrier.CHINA_MOBILE);
    }

    @Test
    void submitApproveSwitchThenRollbackRestoresOwnershipAndRelationships() {
        String code = issueCode(NUMBER, Duration.ofHours(2));

        ApplicationView submitted = portingService.submit(
                defaultCommand("app-1", NUMBER, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, code));
        assertThat(submitted.status()).isEqualTo(PortingStatus.PENDING_REVIEW);
        assertThat(submitted.idempotentReplay()).isFalse();

        ApplicationView approved = portingService.approve("app-1");
        assertThat(approved.status()).isEqualTo(PortingStatus.APPROVED);

        // 切换：归属移动 → 联通，旧关系关闭，新关系 ACTIVE
        ApplicationView switched = portingService.switchApplication("app-1");
        assertThat(switched.status()).isEqualTo(PortingStatus.SWITCHED);
        assertThat(switched.rollbackDeadline()).isEqualTo(switched.switchedAt().plus(Duration.ofHours(24)));

        NumberOwnershipView ownership = portingService.getOwnership(NUMBER);
        assertThat(ownership.currentCarrier()).isEqualTo(Carrier.CHINA_UNICOM);
        assertThat(ownership.activeApplicationId()).isEqualTo("app-1");
        assertThat(ownership.activeApplicationStatus()).isEqualTo("SWITCHED");

        List<RelationshipView> relationships = portingService.relationshipHistory(NUMBER);
        assertThat(relationships).hasSize(2);
        assertThat(relationships.get(0).carrier()).isEqualTo(Carrier.CHINA_MOBILE);
        assertThat(relationships.get(0).status().name()).isEqualTo("CLOSED");
        assertThat(relationships.get(0).closedAt()).isNotNull();
        assertThat(relationships.get(1).carrier()).isEqualTo(Carrier.CHINA_UNICOM);
        assertThat(relationships.get(1).status().name()).isEqualTo("ACTIVE");
        assertThat(relationships.get(1).closedAt()).isNull();

        // 窗口内回退：归属与 ACTIVE 关系完整恢复为移动（历史保留 3 条关系）
        clock.advance(Duration.ofHours(23));
        ApplicationView rolledBack = portingService.rollback("app-1");
        assertThat(rolledBack.status()).isEqualTo(PortingStatus.ROLLED_BACK);
        assertThat(rolledBack.rolledBackAt()).isNotNull();

        NumberOwnershipView afterRollback = portingService.getOwnership(NUMBER);
        assertThat(afterRollback.currentCarrier()).isEqualTo(Carrier.CHINA_MOBILE);
        assertThat(afterRollback.activeApplicationId()).isNull();

        List<RelationshipView> afterRelationships = portingService.relationshipHistory(NUMBER);
        assertThat(afterRelationships).hasSize(3);
        assertThat(afterRelationships.get(1).status().name()).isEqualTo("CLOSED");
        assertThat(afterRelationships.get(2).carrier()).isEqualTo(Carrier.CHINA_MOBILE);
        assertThat(afterRelationships.get(2).status().name()).isEqualTo("ACTIVE");

        // 时间线包含全部关键事件且按时间排列
        List<EventView> timeline = portingService.timeline(NUMBER);
        assertThat(timeline).extracting(EventView::eventType).containsExactly(
                PortingEventType.APPLICATION_SUBMITTED,
                PortingEventType.APPLICATION_APPROVED,
                PortingEventType.SWITCH_STARTED,
                PortingEventType.SWITCH_COMPLETED,
                PortingEventType.ROLLBACK_STARTED,
                PortingEventType.ROLLBACK_COMPLETED);
        assertThat(timeline).allSatisfy(e -> {
            assertThat(e.number()).isEqualTo(NUMBER);
            assertThat(e.eventId()).isNotBlank();
        });
        assertThat(timeline).extracting(EventView::occurredAt).isSorted();
    }
}
