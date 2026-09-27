package com.chris64233.numberporting.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 携转授权码。由原运营商签发，有明确有效期，切换成功时原子地标记为已使用。
 * 授权码只能成功使用一次：used=true 或已过 expiresAt 即不可再用。
 */
@Entity
@Table(name = "authorization_code")
public class AuthorizationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "phone_number", nullable = false, length = 16)
    private String phoneNumber;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "issuing_carrier", nullable = false)
    private Carrier issuingCarrier;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean used = false;

    @Column(name = "used_at")
    private Instant usedAt;

    /** 使用该授权码并完成切换的携转申请。 */
    @Column(name = "used_by_order")
    private Long usedByOrderId;

    /** 主动失效（吊销）。与到期时间任一成立即不可用。 */
    @Column(nullable = false)
    private boolean revoked = false;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected AuthorizationCode() {
    }

    public AuthorizationCode(String code, String phoneNumber, Carrier issuingCarrier,
                             Instant createdAt, Instant expiresAt) {
        this.code = code;
        this.phoneNumber = phoneNumber;
        this.issuingCarrier = issuingCarrier;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return revoked || !now.isBefore(expiresAt);
    }

    /** 是否可用于指定号码的切换：未使用、未失效、在有效期内、归属号码一致。 */
    public boolean isUsable(String forNumber, Instant now) {
        return !used && !isExpired(now) && this.phoneNumber.equals(forNumber);
    }

    /** 是否可用于指定号码与原运营商。 */
    public boolean isUsable(String forNumber, Carrier issuer, Instant now) {
        return isUsable(forNumber, now) && this.issuingCarrier.getCode().equals(issuer.getCode());
    }

    public void revoke(Instant now) {
        if (!revoked) {
            this.revoked = true;
            this.revokedAt = now;
        }
    }

    public void markUsed(Long orderId, Instant now) {
        if (used) {
            throw new IllegalStateException("授权码已被使用: " + code);
        }
        this.used = true;
        this.usedAt = now;
        this.usedByOrderId = orderId;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public Carrier getIssuingCarrier() {
        return issuingCarrier;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isUsed() {
        return used;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Long getUsedByOrderId() {
        return usedByOrderId;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
