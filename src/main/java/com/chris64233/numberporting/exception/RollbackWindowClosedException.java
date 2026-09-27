package com.chris64233.numberporting.exception;

import java.time.Instant;

public class RollbackWindowClosedException extends BusinessRuleException {
    public RollbackWindowClosedException(String applicationId, Instant deadline) {
        super(ErrorCode.ROLLBACK_WINDOW_CLOSED,
                "rollback window of application " + applicationId + " closed at " + deadline
                        + "; submit a new porting application instead");
    }
}
