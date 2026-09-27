package com.chris64233.numberporting.exception;

public class SwitchWindowClosedException extends BusinessRuleException {
    public SwitchWindowClosedException(String message) {
        super(ErrorCode.SWITCH_WINDOW_CLOSED, message);
    }

    public static SwitchWindowClosedException forApplication(String applicationId) {
        return new SwitchWindowClosedException(
                "current time is outside the requested switch window for application " + applicationId
                        + "; application has been expired");
    }
}
