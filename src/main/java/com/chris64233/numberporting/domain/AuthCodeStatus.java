package com.chris64233.numberporting.domain;

/**
 * 授权码状态。授权码只能被成功使用一次。
 */
public enum AuthCodeStatus {
    ISSUED,
    CONSUMED,
    REVOKED,
    EXPIRED
}
