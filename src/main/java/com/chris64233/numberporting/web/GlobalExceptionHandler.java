package com.chris64233.numberporting.web;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.chris64233.numberporting.exception.ActiveApplicationExistsException;
import com.chris64233.numberporting.exception.BusinessRuleException;
import com.chris64233.numberporting.exception.ErrorCode;
import com.chris64233.numberporting.exception.IdempotencyMismatchException;
import com.chris64233.numberporting.exception.IllegalTransitionException;
import com.chris64233.numberporting.exception.InvariantViolationException;
import com.chris64233.numberporting.exception.NumberNotFoundException;
import com.chris64233.numberporting.exception.ApplicationNotFoundException;
import com.chris64233.numberporting.exception.AuthCodeAlreadyUsedException;
import com.chris64233.numberporting.exception.AuthCodeExpiredException;
import com.chris64233.numberporting.exception.AuthCodeMismatchException;
import com.chris64233.numberporting.exception.AuthCodeNotFoundException;
import com.chris64233.numberporting.exception.AuthCodeRevokedException;
import com.chris64233.numberporting.exception.RollbackExecutionException;
import com.chris64233.numberporting.exception.RollbackWindowClosedException;
import com.chris64233.numberporting.exception.SwitchExecutionException;
import com.chris64233.numberporting.exception.SwitchWindowClosedException;

/**
 * 把业务异常统一转译为稳定错误码与 HTTP 状态：
 * <ul>
 *   <li>404：号码 / 申请 / 授权码不存在；</li>
 *   <li>409 状态冲突：活动申请存在、授权码已用/失效/不匹配、非法状态迁移、窗口关闭、
 *       不变量被破坏（理论不可达）、并发修改；</li>
 *   <li>422：同一申请号载荷不一致的幂等冲突；</li>
 *   <li>400：请求参数校验失败；</li>
 *   <li>500：执行期非预期失败（归属与关系已随事务回滚，可安全重试）。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({NumberNotFoundException.class, ApplicationNotFoundException.class,
            AuthCodeNotFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(BusinessRuleException e) {
        return conflict(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler({ActiveApplicationExistsException.class, AuthCodeAlreadyUsedException.class,
            AuthCodeExpiredException.class, AuthCodeRevokedException.class, AuthCodeMismatchException.class,
            SwitchWindowClosedException.class, RollbackWindowClosedException.class,
            IllegalTransitionException.class, InvariantViolationException.class})
    public ResponseEntity<ApiError> handleConflict(BusinessRuleException e) {
        return conflict(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(IdempotencyMismatchException.class)
    public ResponseEntity<ApiError> handleIdempotency(BusinessRuleException e) {
        return conflict(HttpStatus.UNPROCESSABLE_ENTITY, e);
    }

    @ExceptionHandler({SwitchExecutionException.class, RollbackExecutionException.class})
    public ResponseEntity<ApiError> handleExecutionFailure(BusinessRuleException e) {
        return conflict(HttpStatus.INTERNAL_SERVER_ERROR, e);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessRuleException e) {
        HttpStatus status = e.getErrorCode() == ErrorCode.VALIDATION_ERROR
                ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
        return conflict(status, e);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return conflict(HttpStatus.BAD_REQUEST,
                new BusinessRuleException(ErrorCode.VALIDATION_ERROR, detail));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        return conflict(HttpStatus.CONFLICT, new BusinessRuleException(ErrorCode.CONCURRENT_MODIFICATION,
                "concurrent modification detected; retry with the latest state"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException e) {
        return conflict(HttpStatus.CONFLICT, new BusinessRuleException(ErrorCode.CONCURRENT_MODIFICATION,
                "concurrent submission conflict on the unique active application/idempotency constraint"));
    }

    private static ResponseEntity<ApiError> conflict(HttpStatus status, BusinessRuleException e) {
        return ResponseEntity.status(status).body(new ApiError(e.getErrorCode().name(), e.getMessage()));
    }
}
