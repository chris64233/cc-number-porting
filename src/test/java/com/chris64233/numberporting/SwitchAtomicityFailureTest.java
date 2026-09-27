package com.chris64233.numberporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.chris64233.numberporting.domain.PortEvent;
import com.chris64233.numberporting.domain.PortOrderStatus;
import com.chris64233.numberporting.domain.ServiceRelation;
import com.chris64233.numberporting.domain.ServiceStatus;
import com.chris64233.numberporting.repository.ServiceRelationRepository;
import com.chris64233.numberporting.support.AbstractPortingIntegrationTest;

/**
 * 切换原子性故障注入：在"建立新服务关系"这一步注入数据库故障，
 * 验证整个切换事务回滚——不留下双归属或无归属号码，授权码未消费，申请仍待切换。
 */
class SwitchAtomicityFailureTest extends AbstractPortingIntegrationTest {

    @MockitoSpyBean
    private ServiceRelationRepository relationSpy;

    @Autowired
    private ServiceRelationRepository relations;

    @Test
    void failure_during_new_relation_creation_rolls_back_entire_switch() {
        register("13500000001", CMCC);
        issueCode("ATOM-1", "13500000001");
        var order = approvedOrder("ATOM-REQ-1", "13500000001", CMCC, CUCC, "ATOM-1");

        // 在切换事务的"建立新服务关系"步骤注入故障（spy 仅对本次切换生效）。
        doThrow(new DataAccessResourceFailureException("模拟新运营商开户系统故障"))
                .when(relationSpy).save(any(ServiceRelation.class));

        assertThatThrownBy(() -> orderService.switch_(order.getId()))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageContaining("开户系统故障");

        // 归属不变、仍为原运营商；不存在双归属或无归属。
        var ownership = queryService.getOwnership("13500000001");
        assertThat(ownership.number().getCurrentCarrier().getCode()).isEqualTo(CMCC);
        assertThat(ownership.ownershipConsistent()).isTrue();
        assertThat(ownership.number().getActiveOrderId()).isEqualTo(order.getId());

        // 恰有一条 ACTIVE 关系，且仍是原运营商的初始关系；没有产生半条新关系。
        var all = relations.findByPhoneNumberOrderByIdAsc("13500000001");
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getStatus()).isEqualTo(ServiceStatus.ACTIVE);
        assertThat(all.get(0).getCarrier().getCode()).isEqualTo(CMCC);

        // 授权码未被消费（整个事务回滚）。
        assertThat(authCodes.findByCode("ATOM-1").orElseThrow().isUsed()).isFalse();

        // 申请仍是待切换，可在故障恢复后重试。
        var reloaded = orders.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PortOrderStatus.APPROVED);
        // 失败没有产生 SWITCHED 事件。
        assertThat(events.findByOrderIdOrderByIdAsc(order.getId()))
                .extracting(PortEvent::getType)
                .doesNotContain(PortEvent.Type.SWITCHED);
    }

    @Test
    void after_failure_recovery_switch_succeeds_atomically() {
        register("13500000002", CMCC);
        issueCode("ATOM-2", "13500000002");
        var order = approvedOrder("ATOM-REQ-2", "13500000002", CMCC, CUCC, "ATOM-2");

        doThrow(new DataAccessResourceFailureException("瞬时故障"))
                .when(relationSpy).save(any(ServiceRelation.class));
        assertThatThrownBy(() -> orderService.switch_(order.getId())).isNotNull();

        // 恢复后（不再注入故障）重试切换，完整成功。
        org.mockito.Mockito.reset(relationSpy);
        var switched = orderService.switch_(order.getId());
        assertThat(switched.getStatus()).isEqualTo(PortOrderStatus.SWITCHED);
        var ownership = queryService.getOwnership("13500000002");
        assertThat(ownership.number().getCurrentCarrier().getCode()).isEqualTo(CUCC);
        assertThat(ownership.ownershipConsistent()).isTrue();
        assertThat(relations.countByPhoneNumberAndStatus("13500000002", ServiceStatus.ACTIVE))
                .isEqualTo(1);
        assertThat(relations.findByPhoneNumberOrderByIdAsc("13500000002")).hasSize(2);
    }
}
