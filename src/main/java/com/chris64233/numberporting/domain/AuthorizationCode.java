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

import com.chris64233.numberporting.exception.AuthCodeAlreadyUsedException;
import com.chris64233.numberporting.exception.AuthCodeExpiredException;
import com.chris64233.numberporting.exception.AuthCodeRevokedException;

/**
 * 携转授权码：由号码当前归属运营商签发，绑定号码与运营商，具有明确有效期，
 * 只能被一笔申请成功使用一次。
 */
@Entity
@Table(name = "authorization_code")
public class AuthorizationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", length = 64, nullable = false, unique = true, updatable = false)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "number", nullable = false, updatable = false)
    private PhoneNumber phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "carrier", length = 32, nullable = false, updatable = false)
    private Carrier carrier;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private AuthCodeStatus status;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "consumed_by_application_id", length = 64)
    private String consumedByApplicationId;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version
    private long version;

    protected AuthorizationCode() {
    }

    public AuthorizationCode(String code, PhoneNumber phoneNumber, Carrier carrier,
                             Instant issuedAt, Instant expiresAt) {
        this.code = code;
        this.phoneNumber = phoneNumber;
        this.carrier = carrier;
        this.status = AuthCodeStatus.ISSUED;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    /**
     * 在行锁 + 乐观版本保护下消费授权码。任何失败都抛出异常，调用方事务整体回滚。
     */
    public void consume(String applicationId, Instant now) {
        if (status == AuthCodeStatus.CONSUMED) {
            throw new AuthCodeAlreadyUsedException(code);
        }
        if (status == AuthCodeStatus.REVOKED) {
            throw new AuthCodeRevokedException(code);
        }
        if (status == AuthCodeStatus.EXPIRED || !now.isBefore(expiresAt)) {
            status = AuthCodeStatus.EXPIRED;
            throw new AuthCodeExpiredException(code);
        }
        this.status = AuthCodeStatus.CONSUMED;
        this.consumedAt = now;
        this.consumedByApplicationId = applicationId;
    }

    public void markExpired(Instant now) {
        if (status == AuthCodeStatus.ISSUED && !now.isBefore(expiresAt)) {
            status = AuthCodeStatus.EXPIRED;
        }
    }

    public void revoke(Instant now) {
        if (status == AuthCodeStatus.ISSUED) {
            status = AuthCodeStatus.REVOKED;
            revokedAt = now;
        }
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public PhoneNumber getPhoneNumber() {
        return phoneNumber;
    }

    public Carrier getCarrier() {
        return carrier;
    }

    public AuthCodeStatus getStatus() {
        return status;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public String getConsumedByApplicationId() {
        return consumedByApplicationId;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
