package com.chris64233.numberporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 电话号码及其当前归属。
 * currentCarrier 与唯一一条 ACTIVE 的 {@link ServiceRelation} 必须始终一致（归属不变量）。
 */
@Entity
@Table(name = "phone_number")
public class PhoneNumber {

    @Id
    @Column(length = 16)
    private String number;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "current_carrier", nullable = false)
    private Carrier currentCarrier;

    /**
     * 当前占用该号码唯一活动名额的携转申请 id；无活动申请时为 null。
     * 数据库唯一约束保证同一号码至多一条非空记录。
     */
    @Column(name = "active_order_id", unique = true)
    private Long activeOrderId;

    @Version
    private long version;

    protected PhoneNumber() {
    }

    public PhoneNumber(String number, Carrier currentCarrier) {
        this.number = number;
        this.currentCarrier = currentCarrier;
    }

    public String getNumber() {
        return number;
    }

    public Carrier getCurrentCarrier() {
        return currentCarrier;
    }

    public void setCurrentCarrier(Carrier carrier) {
        this.currentCarrier = carrier;
    }

    public Long getActiveOrderId() {
        return activeOrderId;
    }

    public void setActiveOrderId(Long activeOrderId) {
        this.activeOrderId = activeOrderId;
    }
}
