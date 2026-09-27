package com.chris64233.numberporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.chris64233.numberporting.domain.Carrier;
import com.chris64233.numberporting.domain.PortingEventType;
import com.chris64233.numberporting.domain.PortingStatus;
import com.chris64233.numberporting.domain.RelationshipStatus;
import com.chris64233.numberporting.exception.SwitchExecutionException;
import com.chris64233.numberporting.repository.PortingEventRepository;
import com.chris64233.numberporting.repository.ServiceRelationshipRepository;
import com.chris64233.numberporting.service.PortingExecutionService;
import com.chris64233.numberporting.service.PortingService;

/**
 * 原子切换：在“旧关系已关闭、新关系与归属尚未更新”的临界点注入失败，
 * 验证事务整体回滚——无双归属、无无归属，且失败可重试、最终成功。
 */
class AtomicSwitchFailureIntegrationTest extends AbstractIntegrationTest {

    private static final String NUMBER = "13800000004";

    @Autowired
    private PortingService portingService;

    @Autowired
    private PortingExecutionService executionService;

    @Autowired
    private ServiceRelationshipRepository relationshipRepository;

    @Autowired
    private PortingEventRepository eventRepository;

    @BeforeEach
    void setUp() {
        provision(NUMBER, Carrier.CHINA_MOBILE);
    }

    @Test
    void failureAfterClosingOldRelationshipRollsBackCompletelyAndCanBeRetried() {
        String code = issueCode(NUMBER, Duration.ofHours(2));
        portingService.submit(defaultCommand("atomic-1", NUMBER,
                Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, code));
        portingService.approve("atomic-1");

        // 注入临界点故障
        executionService.setSwitchFailureHook(app -> {
            throw new IllegalStateException("simulated downstream provisioning failure");
        });
        try {
            assertThatThrownBy(() -> portingService.switchApplication("atomic-1"))
                    .isInstanceOf(SwitchExecutionException.class);
        } finally {
            executionService.setSwitchFailureHook(null);
        }

        // 回滚后：归属仍是移动，且恰好一条移动 ACTIVE 关系
        var ownership = portingService.getOwnership(NUMBER);
        assertThat(ownership.currentCarrier()).isEqualTo(Carrier.CHINA_MOBILE);
        assertThat(relationshipRepository.countActiveByNumber(NUMBER, RelationshipStatus.ACTIVE)).isEqualTo(1);
        assertThat(relationshipRepository.findActiveCarrier(NUMBER, RelationshipStatus.ACTIVE))
                .contains(Carrier.CHINA_MOBILE);
        assertThat(relationshipRepository.findHistoryByNumber(NUMBER)).hasSize(1);

        // 申请仍是 APPROVED；失败事务整体回滚（其中的 SWITCH_STARTED 一并回滚），
        // 只有独立事务记录的 SWITCH_FAILED 保留，且没有完成事件
        assertThat(portingService.getApplication("atomic-1").status()).isEqualTo(PortingStatus.APPROVED);
        List<PortingEventType> types = eventRepository.findByApplicationIdOrderByOccurredAtAscIdAsc("atomic-1")
                .stream().map(e -> e.getEventType()).toList();
        assertThat(types).contains(PortingEventType.SWITCH_FAILED);
        assertThat(types).doesNotContain(
                PortingEventType.SWITCH_COMPLETED, PortingEventType.ROLLBACK_STARTED);

        // 排除故障后重试必须成功
        var switched = portingService.switchApplication("atomic-1");
        assertThat(switched.status()).isEqualTo(PortingStatus.SWITCHED);
        assertThat(portingService.getOwnership(NUMBER).currentCarrier())
                .isEqualTo(Carrier.CHINA_UNICOM);
        assertThat(relationshipRepository.findHistoryByNumber(NUMBER)).hasSize(2);
    }
}
