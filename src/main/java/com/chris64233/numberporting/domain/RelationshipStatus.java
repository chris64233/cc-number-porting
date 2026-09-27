package com.chris64233.numberporting.domain;

/**
 * 运营商服务关系状态。一个号码在任一运营商最多只有一条 ACTIVE 关系，
 * 且 ACTIVE 关系的运营商必须等于号码当前归属。
 */
public enum RelationshipStatus {
    ACTIVE,
    CLOSED
}
