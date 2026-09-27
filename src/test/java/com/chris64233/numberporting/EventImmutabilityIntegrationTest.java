package com.chris64233.numberporting;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.numberporting.domain.Carrier;
import com.chris64233.numberporting.domain.PortingEvent;
import com.chris64233.numberporting.repository.PortingEventRepository;
import com.chris64233.numberporting.service.PortingService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 事件不可变：事件表从数据库层面（H2 触发器，生产环境等价为触发器/只授予 INSERT 权限）
 * 拒绝 UPDATE 与 DELETE；应用层另有 JPA 回调和字段 updatable=false 双重保护。
 */
class EventImmutabilityIntegrationTest extends AbstractIntegrationTest {

    private static final String NUMBER = "13800000007";

    @Autowired
    private PortingService portingService;

    @Autowired
    private PortingEventRepository eventRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        provision(NUMBER, Carrier.CHINA_MOBILE);
        String code = issueCode(NUMBER, Duration.ofHours(2));
        portingService.submit(defaultCommand("ev-1", NUMBER, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, code));
    }

    @Test
    @Transactional
    void bulkUpdateOfAnEventIsRejectedByTheDatabase() {
        PortingEvent event = firstEvent();
        assertThatThrownBy(() -> {
            entityManager.createQuery("update PortingEvent e set e.detail = 'tampered' where e.id = :id")
                    .setParameter("id", event.getId())
                    .executeUpdate();
            entityManager.flush();
        }).hasMessageContaining("append-only");
    }

    @Test
    @Transactional
    void deletingAnEventIsRejected() {
        PortingEvent event = firstEvent();
        PortingEvent managed = entityManager.find(PortingEvent.class, event.getId());
        assertThatThrownBy(() -> {
            entityManager.remove(managed);
            entityManager.flush();
        }).hasMessageContaining("never be deleted");
    }

    private PortingEvent firstEvent() {
        List<PortingEvent> events = eventRepository.findByNumberOrderByOccurredAtAscIdAsc(NUMBER);
        return events.get(0);
    }
}
