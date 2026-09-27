package com.chris64233.numberporting.exception;

/**
 * 同一申请号重复提交但请求载荷与首次不一致：拒绝并指出冲突字段，避免幂等键被误用。
 */
public class IdempotencyMismatchException extends BusinessRuleException {
    public IdempotencyMismatchException(String applicationId, String field, Object existing, Object repeated) {
        super(ErrorCode.IDEMPOTENCY_MISMATCH,
                "idempotency key " + applicationId + " reused with different '" + field
                        + "': existing=" + existing + ", repeated=" + repeated);
    }
}
