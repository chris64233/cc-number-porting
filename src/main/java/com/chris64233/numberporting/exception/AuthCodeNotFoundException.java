package com.chris64233.numberporting.exception;

public class AuthCodeNotFoundException extends BusinessRuleException {
    public AuthCodeNotFoundException(String code) {
        super(ErrorCode.AUTH_CODE_NOT_FOUND, "authorization code not found: " + code);
    }
}
