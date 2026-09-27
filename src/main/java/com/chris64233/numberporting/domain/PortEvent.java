package com.chris64233.numberporting.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * 不可修改的状态变化事件（事件审计线）。
 *
 * <p>只允许 insert；落库后任何 update/delete 都被 {@link ImmutableGuard} 拒绝，
 * 业务代码也只通过 {@code portEvent(...)} 工厂追加事件。
 */
@Entity
@Table(name = "port_event")
@EntityListeners(PortEvent.ImmutableGuard.class)
public class PortEvent {

    /** 申请生命周期事件类型。 */
    public enum Type {
        ORDER_CREATED("申请已创建"),
        ORDER_APPROVED("审核通过，进入待切换"),
        ORDER_CANCELLED("申请已取消"),
        AUTH_CODE_EXPIRED("授权码失效，申请关闭"),
        SWITCHED("切换完成：归属与服务关系已原子变更"),
        ROLLED_BACK("受控回退完成：原归属与服务关系已恢复"),
        ORDER_COMPLETED("回退窗口关闭，携转终态完成");

        public final String description;

        Type(String description) {
            this.description = description;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "phone_number", nullable = false, length = 16)
    private String phoneNumber;

    @Column(nullable = false, length = 32)
    private String type;

    /** 事件发生前状态，首个事件为 null。 */
    @Column(name = "from_status", length = 32)
    private String fromStatus;

    /** 事件发生后状态。 */
    @Column(name = "to_status", length = 32)
    private String toStatus;

    @Column(length = 256)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected PortEvent() {
    }

    private PortEvent(Long orderId, String phoneNumber, Type type,
                      PortOrderStatus from, PortOrderStatus to, String detail, Instant now) {
        this.orderId = orderId;
        this.phoneNumber = phoneNumber;
        this.type = type.name();
        this.fromStatus = from == null ? null : from.name();
        this.toStatus = to == null ? null : to.name();
        this.detail = detail;
        this.occurredAt = now;
    }

    public static PortEvent portEvent(Long orderId, String phoneNumber, Type type,
                                      PortOrderStatus from, PortOrderStatus to,
                                      String detail, Instant now) {
        return new PortEvent(orderId, phoneNumber, type, from, to, detail, now);
    }

    /** 持久化后禁止修改/删除。 */
    public static class ImmutableGuard {
        @PreUpdate
        public void preUpdate(PortEvent event) {
            throw new IllegalStateException("事件不可修改: eventId=" + event.id);
        }

        @PreRemove
        public void preRemove(PortEvent event) {
            throw new IllegalStateException("事件不可删除: eventId=" + event.id);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public Type getType() {
        return Type.valueOf(type);
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
