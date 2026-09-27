package com.chris64233.numberporting.exception;

public class ActiveApplicationExistsException extends BusinessRuleException {
    public ActiveApplicationExistsException(String number, String existingApplicationId) {
        super(ErrorCode.ACTIVE_APPLICATION_EXISTS,
                "number " + number + " already has active application " + existingApplicationId);
    }
}
