package com.chris64233.numberporting.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 号码及其当前归属运营商。切换 / 回退时归属变更与服务关系变更在同一事务内原子完成。
 */
@Entity
@Table(name = "phone_number")
public class PhoneNumber {

    @Id
    @Column(name = "number", length = 20, nullable = false, updatable = false)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_carrier", length = 32, nullable = false)
    private Carrier currentCarrier;

    @Column(name = "provisioned_at", nullable = false, updatable = false)
    private Instant provisionedAt;

    @Column(name = "last_switched_at")
    private Instant lastSwitchedAt;

    @Version
    private long version;

    protected PhoneNumber() {
    }

    public PhoneNumber(String number, Carrier currentCarrier, Instant provisionedAt) {
        this.number = number;
        this.currentCarrier = currentCarrier;
        this.provisionedAt = provisionedAt;
    }

    public String getNumber() {
        return number;
    }

    public Carrier getCurrentCarrier() {
        return currentCarrier;
    }

    public void setCurrentCarrier(Carrier currentCarrier) {
        this.currentCarrier = currentCarrier;
    }

    public Instant getProvisionedAt() {
        return provisionedAt;
    }

    public Instant getLastSwitchedAt() {
        return lastSwitchedAt;
    }

    public void setLastSwitchedAt(Instant lastSwitchedAt) {
        this.lastSwitchedAt = lastSwitchedAt;
    }

    public long getVersion() {
        return version;
    }
}
