package com.chris64233.numberporting.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.numberporting.domain.PortingEvent;
import com.chris64233.numberporting.domain.PortingEventType;
import com.chris64233.numberporting.domain.PortingStatus;
import com.chris64233.numberporting.repository.PortingEventRepository;

/**
 * 事件追加器：所有状态变化在同一业务事务内追加不可变事件（事件落库与状态修改同生共死）。
 * 唯一例外是 {@link #recordFailureInNewTransaction}：切换/回退执行抛错时，用独立事务
 * 留下失败事件，而主事务整体回滚，保证时间线可解释且不破坏原子性。
 */
@Service
public class PortingEventRecorder {

    private final PortingEventRepository eventRepository;

    public PortingEventRecorder(PortingEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PortingEvent append(String number, String applicationId, PortingEventType type,
                               PortingStatus from, PortingStatus to, Instant occurredAt, String detail) {
        PortingEvent event = new PortingEvent(
                UUID.randomUUID().toString(), number, applicationId, type, from, to, occurredAt, detail);
        return eventRepository.save(event);
    }

    /**
     * 独立事务记录执行失败（SWITCH_FAILED / ROLLBACK_FAILED）。
     * 即使外层业务事务回滚，失败事件与原因仍保留在时间线上。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailureInNewTransaction(String number, String applicationId, PortingEventType type,
                                              PortingStatus status, Instant occurredAt, String detail) {
        eventRepository.save(new PortingEvent(
                UUID.randomUUID().toString(), number, applicationId, type, status, status, occurredAt, detail));
    }

    @Transactional(readOnly = true)
    public List<PortingEvent> timelineOfNumber(String number) {
        return eventRepository.findByNumberOrderByOccurredAtAscIdAsc(number);
    }

    @Transactional(readOnly = true)
    public List<PortingEvent> timelineOfApplication(String applicationId) {
        return eventRepository.findByApplicationIdOrderByOccurredAtAscIdAsc(applicationId);
    }
}
