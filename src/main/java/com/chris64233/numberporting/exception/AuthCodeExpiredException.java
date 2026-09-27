package com.chris64233.numberporting.exception;

public class AuthCodeExpiredException extends BusinessRuleException {
    public AuthCodeExpiredException(String code) {
        super(ErrorCode.AUTH_CODE_EXPIRED, "authorization code expired: " + code);
    }
}
