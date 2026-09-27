package com.chris64233.numberporting.service;

/** 业务规则违反时抛出，携带稳定错误码与可解释信息，由全局异常处理器映射为 HTTP 响应。 */
public class BusinessRuleException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessRuleException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public BusinessRuleException(ErrorCode errorCode, String detail) {
        super(errorCode.getDefaultMessage() + ": " + detail);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
