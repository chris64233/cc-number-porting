package com.chris64233.numberporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 运营商。 */
@Entity
@Table(name = "carrier")
public class Carrier {

    @Id
    @Column(length = 32)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    protected Carrier() {
    }

    public Carrier(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
