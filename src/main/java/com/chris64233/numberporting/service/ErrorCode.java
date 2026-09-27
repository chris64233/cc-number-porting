package com.chris64233.numberporting.service;

import org.springframework.http.HttpStatus;

/** 业务错误码：携带 HTTP 状态、稳定错误标识与面向调用方的可解释信息。 */
public enum ErrorCode {

    NUMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "NUMBER_NOT_FOUND", "号码不存在"),
    CARRIER_NOT_FOUND(HttpStatus.NOT_FOUND, "CARRIER_NOT_FOUND", "运营商不存在"),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "携转申请不存在"),
    AUTH_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "AUTH_CODE_NOT_FOUND", "授权码不存在"),

    NUMBER_ALREADY_REGISTERED(HttpStatus.CONFLICT, "NUMBER_ALREADY_REGISTERED", "号码已注册"),
    ACTIVE_ORDER_EXISTS(HttpStatus.CONFLICT, "ACTIVE_ORDER_EXISTS",
            "该号码已有一笔活动携转申请，同一时间只能存在一笔"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
            "幂等键已被内容不同的申请占用"),
    SAME_CARRIER(HttpStatus.UNPROCESSABLE_ENTITY, "SAME_CARRIER",
            "原运营商与新运营商不能相同"),
    CARRIER_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "CARRIER_MISMATCH",
            "原运营商必须是号码当前归属运营商"),
    INVALID_SWITCH_WINDOW(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SWITCH_WINDOW",
            "期望切换窗口的起点必须早于终点"),

    AUTH_CODE_INVALID(HttpStatus.CONFLICT, "AUTH_CODE_INVALID",
            "授权码与号码或原运营商不匹配"),
    AUTH_CODE_USED(HttpStatus.CONFLICT, "AUTH_CODE_USED", "授权码已被使用，只能成功使用一次"),
    AUTH_CODE_EXPIRED(HttpStatus.CONFLICT, "AUTH_CODE_EXPIRED", "授权码已过有效期"),
    AUTH_CODE_ALREADY_ISSUED(HttpStatus.CONFLICT, "AUTH_CODE_ALREADY_ISSUED",
            "该授权码已存在"),

    ILLEGAL_ORDER_STATUS(HttpStatus.CONFLICT, "ILLEGAL_ORDER_STATUS",
            "申请当前状态不允许该操作"),
    OUTSIDE_SWITCH_WINDOW(HttpStatus.CONFLICT, "OUTSIDE_SWITCH_WINDOW",
            "当前时间不在期望切换窗口内"),
    ROLLBACK_WINDOW_CLOSED(HttpStatus.CONFLICT, "ROLLBACK_WINDOW_CLOSED",
            "已超过受控回退窗口，不能回退，只能创建新的携转申请");

    private final HttpStatus httpStatus;
    private final String code;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String code, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
