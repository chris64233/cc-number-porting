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

/**
 * 号码与运营商之间的服务关系。切换时关闭旧关系、建立新关系；回退时恢复原关系。
 * 任一号码任意时刻恰有一条 ACTIVE 关系（业务不变量，由切换/回退事务保证）。
 */
@Entity
@Table(name = "service_relation")
public class ServiceRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "phone_number", nullable = false, length = 16)
    private String phoneNumber;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "carrier", nullable = false)
    private Carrier carrier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ServiceStatus status;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** INITIAL / PORT_IN / ROLLBACK_RESTORE。 */
    @Column(name = "open_reason", nullable = false, length = 32)
    private String openReason;

    /** PORT_OUT / ROLLBACK_CLOSE。 */
    @Column(name = "close_reason", length = 32)
    private String closeReason;

    protected ServiceRelation() {
    }

    private ServiceRelation(String phoneNumber, Carrier carrier, Instant openedAt, String openReason) {
        this.phoneNumber = phoneNumber;
        this.carrier = carrier;
        this.status = ServiceStatus.ACTIVE;
        this.openedAt = openedAt;
        this.openReason = openReason;
    }

    public static ServiceRelation initial(String phoneNumber, Carrier carrier, Instant now) {
        return new ServiceRelation(phoneNumber, carrier, now, "INITIAL");
    }

    public static ServiceRelation portIn(String phoneNumber, Carrier carrier, Instant now) {
        return new ServiceRelation(phoneNumber, carrier, now, "PORT_IN");
    }

    public void close(String reason, Instant now) {
        if (this.status != ServiceStatus.ACTIVE) {
            throw new IllegalStateException("服务关系已关闭，不能重复关闭: " + id);
        }
        this.status = ServiceStatus.CLOSED;
        this.closedAt = now;
        this.closeReason = reason;
    }

    /** 回退时完整恢复被切换关闭的原服务关系（关闭痕迹清除，恢复动作由事件记录）。 */
    public void reopen(Instant now) {
        if (this.status != ServiceStatus.CLOSED) {
            throw new IllegalStateException("只有已关闭的服务关系可以恢复: " + id);
        }
        this.status = ServiceStatus.ACTIVE;
        this.closedAt = null;
        this.closeReason = null;
    }

    public Long getId() {
        return id;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public Carrier getCarrier() {
        return carrier;
    }

    public ServiceStatus getStatus() {
        return status;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public String getOpenReason() {
        return openReason;
    }

    public String getCloseReason() {
        return closeReason;
    }
}
