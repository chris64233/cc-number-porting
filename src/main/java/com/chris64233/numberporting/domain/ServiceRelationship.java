package com.chris64233.numberporting.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 号码与运营商之间的服务关系。切换时关闭旧关系并建立新关系，回退时同样关闭/新建，
 * 保留完整历史。任一时刻一个号码只有一条 ACTIVE 关系（由业务事务加锁 + 不变量校验保证）。
 */
@Entity
@Table(name = "service_relationship")
public class ServiceRelationship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "number", nullable = false, updatable = false)
    private PhoneNumber phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "carrier", length = 32, nullable = false, updatable = false)
    private Carrier carrier;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private RelationshipStatus status;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    private long version;

    protected ServiceRelationship() {
    }

    public ServiceRelationship(PhoneNumber phoneNumber, Carrier carrier, Instant openedAt) {
        this.phoneNumber = phoneNumber;
        this.carrier = carrier;
        this.status = RelationshipStatus.ACTIVE;
        this.openedAt = openedAt;
    }

    public void close(Instant closedAt) {
        if (this.status != RelationshipStatus.ACTIVE) {
            throw new IllegalStateException("relationship already closed: " + id);
        }
        this.status = RelationshipStatus.CLOSED;
        this.closedAt = closedAt;
    }

    public Long getId() {
        return id;
    }

    public PhoneNumber getPhoneNumber() {
        return phoneNumber;
    }

    public Carrier getCarrier() {
        return carrier;
    }

    public RelationshipStatus getStatus() {
        return status;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
