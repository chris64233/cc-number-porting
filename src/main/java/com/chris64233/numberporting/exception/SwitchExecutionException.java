package com.chris64233.numberporting.exception;

public class SwitchExecutionException extends BusinessRuleException {
    public SwitchExecutionException(String applicationId, Throwable cause) {
        super(ErrorCode.SWITCH_EXECUTION_FAILED,
                "switch execution failed for application " + applicationId
                        + "; transaction rolled back, ownership and service relationships unchanged: "
                        + cause.getMessage());
    }
}
