package com.chris64233.numberporting.exception;

public class RollbackExecutionException extends BusinessRuleException {
    public RollbackExecutionException(String applicationId, Throwable cause) {
        super(ErrorCode.ROLLBACK_EXECUTION_FAILED,
                "rollback execution failed for application " + applicationId
                        + "; transaction rolled back, ownership and service relationships unchanged: "
                        + cause.getMessage());
    }
}
