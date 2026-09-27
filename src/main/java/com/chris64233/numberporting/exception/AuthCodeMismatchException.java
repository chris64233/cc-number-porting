package com.chris64233.numberporting.exception;

public class AuthCodeMismatchException extends BusinessRuleException {
    public AuthCodeMismatchException(String detail) {
        super(ErrorCode.AUTH_CODE_MISMATCH, detail);
    }
}
