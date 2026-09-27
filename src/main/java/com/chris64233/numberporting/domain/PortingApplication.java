package com.chris64233.numberporting.domain;

import java.time.Instant;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 携转申请。
 *
 * <p>{@code applicationId} 为客户端提供的申请号，全局唯一，保证提交幂等（并发/重试最多一笔成功）。
 * {@code activeSlot} 为固定哨兵值 1：仅活动申请占用该槽位（数据库唯一约束
 * {@code uk_porting_active (number, active_slot)} 保证同一号码同一时刻最多一笔活动申请，
 * 终态申请该列为 NULL，不参与唯一约束）。
 */
@Entity
@Table(name = "porting_application",
        uniqueConstraints = {
                @jakarta.persistence.UniqueConstraint(name = "uk_porting_application_id",
                        columnNames = "application_id"),
                @jakarta.persistence.UniqueConstraint(name = "uk_porting_active",
                        columnNames = {"number", "active_slot"})
        })
public class PortingApplication {

    /** 活动申请占用的固定槽位；终态时置空。 */
    public static final Long ACTIVE_SLOT = 1L;

    @Id
    @Column(name = "application_id", length = 64, nullable = false, updatable = false)
    private String applicationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "number", nullable = false, updatable = false)
    private PhoneNumber phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "donor_carrier", length = 32, nullable = false, updatable = false)
    private Carrier donorCarrier;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_carrier", length = 32, nullable = false, updatable = false)
    private Carrier recipientCarrier;

    @Column(name = "auth_code_value", length = 64, nullable = false, updatable = false)
    private String authCodeValue;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auth_code_id", nullable = false, updatable = false)
    private AuthorizationCode authorizationCode;

    @Embedded
    @AttributeOverride(name = "start", column = @Column(name = "window_start", nullable = false, updatable = false))
    @AttributeOverride(name = "end", column = @Column(name = "window_end", nullable = false, updatable = false))
    private SwitchWindow switchWindow;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private PortingStatus status;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "switched_at")
    private Instant switchedAt;

    /** 回退窗口截止时间；切换完成时确定，超过该时间不能回退。 */
    @Column(name = "rollback_deadline")
    private Instant rollbackDeadline;

    @Column(name = "rolled_back_at")
    private Instant rolledBackAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "end_reason", length = 255)
    private String endReason;

    /** 非空即活动申请；与 number 构成部分唯一索引。 */
    @Column(name = "active_slot")
    private Long activeSlot;

    @Version
    private long version;

    protected PortingApplication() {
    }

    public PortingApplication(String applicationId, PhoneNumber phoneNumber,
                              Carrier donorCarrier, Carrier recipientCarrier,
                              AuthorizationCode authorizationCode, SwitchWindow switchWindow,
                              Instant submittedAt) {
        this.applicationId = applicationId;
        this.phoneNumber = phoneNumber;
        this.donorCarrier = donorCarrier;
        this.recipientCarrier = recipientCarrier;
        this.authorizationCode = authorizationCode;
        this.authCodeValue = authorizationCode.getCode();
        this.switchWindow = switchWindow;
        this.status = PortingStatus.PENDING_REVIEW;
        this.submittedAt = submittedAt;
        this.activeSlot = ACTIVE_SLOT;
    }

    /** 转入终态时释放活动槽位。 */
    private void releaseSlot() {
        this.activeSlot = null;
    }

    public void approve(Instant now) {
        if (status != PortingStatus.PENDING_REVIEW) {
            throw new IllegalStateException("application " + applicationId + " not in PENDING_REVIEW: " + status);
        }
        this.status = PortingStatus.APPROVED;
        this.reviewedAt = now;
    }

    public void reject(Instant now, String reason) {
        if (status != PortingStatus.PENDING_REVIEW) {
            throw new IllegalStateException("application " + applicationId + " not in PENDING_REVIEW: " + status);
        }
        this.status = PortingStatus.REJECTED;
        this.reviewedAt = now;
        this.endedAt = now;
        this.endReason = reason;
        releaseSlot();
    }

    public void cancel(Instant now) {
        if (status != PortingStatus.PENDING_REVIEW && status != PortingStatus.APPROVED) {
            throw new IllegalStateException("application " + applicationId + " cannot be cancelled in " + status);
        }
        this.status = PortingStatus.CANCELLED;
        this.endedAt = now;
        this.endReason = "cancelled by subscriber";
        releaseSlot();
    }

    public void expire(Instant now, String reason) {
        if (status != PortingStatus.PENDING_REVIEW && status != PortingStatus.APPROVED) {
            throw new IllegalStateException("application " + applicationId + " cannot expire in " + status);
        }
        this.status = PortingStatus.EXPIRED;
        this.endedAt = now;
        this.endReason = reason;
        releaseSlot();
    }

    /**
     * 切换完成：仍占用活动槽位（回退窗口内号码仍受本申请约束），窗口结束后由
     * {@link #completeLifecycle(Instant, String)} 释放。
     */
    public void markSwitched(Instant now, Instant rollbackDeadline) {
        if (status != PortingStatus.APPROVED) {
            throw new IllegalStateException("application " + applicationId + " not in APPROVED: " + status);
        }
        this.status = PortingStatus.SWITCHED;
        this.switchedAt = now;
        this.rollbackDeadline = rollbackDeadline;
    }

    public void markRolledBack(Instant now) {
        if (status != PortingStatus.SWITCHED) {
            throw new IllegalStateException("application " + applicationId + " not in SWITCHED: " + status);
        }
        this.status = PortingStatus.ROLLED_BACK;
        this.rolledBackAt = now;
        this.endedAt = now;
        this.endReason = "controlled rollback within rollback window";
        releaseSlot();
    }

    /** 回退窗口结束：SWITCHED 申请正常走完生命周期，号码可以发起新申请。 */
    public void completeLifecycle(Instant now, String reason) {
        if (status != PortingStatus.SWITCHED) {
            throw new IllegalStateException("application " + applicationId + " not in SWITCHED: " + status);
        }
        this.endedAt = now;
        this.endReason = reason;
        releaseSlot();
    }

    public String getApplicationId() {
        return applicationId;
    }

    public PhoneNumber getPhoneNumber() {
        return phoneNumber;
    }

    public Carrier getDonorCarrier() {
        return donorCarrier;
    }

    public Carrier getRecipientCarrier() {
        return recipientCarrier;
    }

    public String getAuthCodeValue() {
        return authCodeValue;
    }

    public AuthorizationCode getAuthorizationCode() {
        return authorizationCode;
    }

    public SwitchWindow getSwitchWindow() {
        return switchWindow;
    }

    public PortingStatus getStatus() {
        return status;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
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

    public Instant getEndedAt() {
        return endedAt;
    }

    public String getEndReason() {
        return endReason;
    }

    public Long getActiveSlot() {
        return activeSlot;
    }
}
