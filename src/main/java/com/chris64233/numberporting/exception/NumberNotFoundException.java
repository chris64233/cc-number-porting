package com.chris64233.numberporting.exception;

public class NumberNotFoundException extends BusinessRuleException {
    public NumberNotFoundException(String number) {
        super(ErrorCode.NUMBER_NOT_FOUND, "phone number not found: " + number);
    }
}
