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
 * 号码携转申请（聚合根）。
 *
 * <p>业务约束：
 * <ul>
 *   <li>同一号码同一时间至多一笔活动申请，由 phone_number.active_order_id 的唯一约束兜底；</li>
 *   <li>requestId 全局唯一，保证申请幂等（并发重复提交最多一笔成功）；</li>
 *   <li>授权码在切换成功时一次性消费；</li>
 *   <li>切换/回退必须在同一事务内完成归属与服务关系变更。</li>
 * </ul>
 */
@Entity
@Table(name = "port_order")
public class PortOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 客户端幂等键，唯一。 */
    @Column(name = "request_id", nullable = false, unique = true, length = 64)
    private String requestId;

    @Column(name = "phone_number", nullable = false, length = 16)
    private String phoneNumber;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "from_carrier", nullable = false)
    private Carrier fromCarrier;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "to_carrier", nullable = false)
    private Carrier toCarrier;

    @Column(name = "auth_code", nullable = false, length = 64)
    private String authCode;

    /** 期望切换窗口起点（含）。 */
    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    /** 期望切换窗口终点（不含）。 */
    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PortOrderStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "switched_at")
    private Instant switchedAt;

    /** 回退截止时间（切换时间 + 回退窗口），切换后非空。 */
    @Column(name = "rollback_deadline")
    private Instant rollbackDeadline;

    @Column(name = "rolled_back_at")
    private Instant rolledBackAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** 进入 AUTH_CODE_EXPIRED 的时间。 */
    @Column(name = "expired_at")
    private Instant expiredAt;

    /** 状态补充说明（如失效/取消原因）。 */
    @Column(length = 256)
    private String note;

    @Version
    private long version;

    protected PortOrder() {
    }

    public PortOrder(String requestId, String phoneNumber, Carrier fromCarrier, Carrier toCarrier,
                     String authCode, Instant windowStart, Instant windowEnd, Instant now) {
        this.requestId = requestId;
        this.phoneNumber = phoneNumber;
        this.fromCarrier = fromCarrier;
        this.toCarrier = toCarrier;
        this.authCode = authCode;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.status = PortOrderStatus.PENDING_REVIEW;
        this.createdAt = now;
    }

    public void markApproved(Instant now) {
        this.status = PortOrderStatus.APPROVED;
        this.approvedAt = now;
    }

    public void markSwitched(Instant now, Instant rollbackDeadline) {
        this.status = PortOrderStatus.SWITCHED;
        this.switchedAt = now;
        this.rollbackDeadline = rollbackDeadline;
    }

    public void markRolledBack(Instant now) {
        this.status = PortOrderStatus.ROLLED_BACK;
        this.rolledBackAt = now;
    }

    public void markCancelled(Instant now, String reason) {
        this.status = PortOrderStatus.CANCELLED;
        this.cancelledAt = now;
        this.note = reason;
    }

    public void markAuthCodeExpired(Instant now, String reason) {
        this.status = PortOrderStatus.AUTH_CODE_EXPIRED;
        this.expiredAt = now;
        this.note = reason;
    }

    /** 回退窗口关闭，携转进入终态（由新申请或显式关闭触发）。 */
    public void markCompleted(Instant now, String reason) {
        this.status = PortOrderStatus.COMPLETED;
        this.note = reason;
    }

    /** 当前时间是否落在期望切换窗口内（起点含、终点不含）。 */
    public boolean isWithinSwitchWindow(Instant now) {
        return !now.isBefore(windowStart) && now.isBefore(windowEnd);
    }

    public Long getId() {
        return id;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public Carrier getFromCarrier() {
        return fromCarrier;
    }

    public Carrier getToCarrier() {
        return toCarrier;
    }

    public String getAuthCode() {
        return authCode;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public PortOrderStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getSwitchedAt() {
        return switchedAt;
    }

    public Instant getRollbackDeadline() {
        return rollbackDeadline;
    }

    public Instant getRolledBackAt() {
        return rolledBackAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Instant getExpiredAt() {
        return expiredAt;
    }

    public String getNote() {
        return note;
    }
}
