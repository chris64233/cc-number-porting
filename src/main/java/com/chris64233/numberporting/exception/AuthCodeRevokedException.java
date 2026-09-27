package com.chris64233.numberporting.exception;

public class AuthCodeRevokedException extends BusinessRuleException {
    public AuthCodeRevokedException(String code) {
        super(ErrorCode.AUTH_CODE_REVOKED, "authorization code revoked: " + code);
    }
}
