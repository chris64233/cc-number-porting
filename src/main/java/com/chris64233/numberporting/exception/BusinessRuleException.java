package com.chris64233.numberporting.exception;

/**
 * 所有可预期业务异常的基类：携带稳定错误码，便于并发场景给出唯一可解释的最终状态。
 */
public class BusinessRuleException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessRuleException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
