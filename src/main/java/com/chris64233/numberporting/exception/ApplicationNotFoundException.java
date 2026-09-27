package com.chris64233.numberporting.exception;

public class ApplicationNotFoundException extends BusinessRuleException {
    public ApplicationNotFoundException(String applicationId) {
        super(ErrorCode.APPLICATION_NOT_FOUND, "application not found: " + applicationId);
    }
}
