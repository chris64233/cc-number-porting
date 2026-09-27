package com.chris64233.numberporting.exception;

/**
 * 原子切换 / 回退后的不变量校验失败（出现双归属、无归属或归属与 ACTIVE 关系不一致）。
 * 抛出后整个事务回滚，绝不让损坏状态落库。
 */
public class InvariantViolationException extends BusinessRuleException {
    public InvariantViolationException(String message) {
        super(ErrorCode.INVARIANT_VIOLATION, message);
    }
}
