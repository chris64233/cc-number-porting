package com.chris64233.numberporting.exception;

public class IllegalTransitionException extends BusinessRuleException {
    public IllegalTransitionException(String message) {
        super(ErrorCode.ILLEGAL_TRANSITION, message);
    }
}
