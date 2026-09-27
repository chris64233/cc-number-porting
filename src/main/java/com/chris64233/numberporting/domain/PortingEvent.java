package com.chris64233.numberporting.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * 携转领域事件：只增不改不删，构成号码携转的不可变时间线。
 *
 * <p>通过 JPA 生命周期回调在持久化层拒绝任何更新/删除尝试；Repository 仅暴露追加与查询。
 */
@Entity
@Table(name = "porting_event")
public class PortingEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", length = 64, nullable = false, unique = true, updatable = false)
    private String eventId;

    @Column(name = "number", length = 20, nullable = false, updatable = false)
    private String number;

    @Column(name = "application_id", length = 64, updatable = false)
    private String applicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 32, nullable = false, updatable = false)
    private PortingEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32, updatable = false)
    private PortingStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 32, updatable = false)
    private PortingStatus toStatus;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "detail", length = 500, updatable = false)
    private String detail;

    protected PortingEvent() {
    }

    public PortingEvent(String eventId, String number, String applicationId, PortingEventType eventType,
                        PortingStatus fromStatus, PortingStatus toStatus, Instant occurredAt, String detail) {
        this.eventId = eventId;
        this.number = number;
        this.applicationId = applicationId;
        this.eventType = eventType;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.occurredAt = occurredAt;
        this.detail = detail;
    }

    @PreUpdate
    void preUpdate() {
        throw new IllegalStateException("porting events are immutable and must never be updated: " + eventId);
    }

    @PreRemove
    void preRemove() {
        throw new IllegalStateException("porting events must never be deleted: " + eventId);
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getNumber() {
        return number;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public PortingEventType getEventType() {
        return eventType;
    }

    public PortingStatus getFromStatus() {
        return fromStatus;
    }

    public PortingStatus getToStatus() {
        return toStatus;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getDetail() {
        return detail;
    }
}
