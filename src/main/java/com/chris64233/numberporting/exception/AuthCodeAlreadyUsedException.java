package com.chris64233.numberporting.exception;

public class AuthCodeAlreadyUsedException extends BusinessRuleException {
    public AuthCodeAlreadyUsedException(String code) {
        super(ErrorCode.AUTH_CODE_ALREADY_USED, "authorization code already consumed: " + code);
    }
}
