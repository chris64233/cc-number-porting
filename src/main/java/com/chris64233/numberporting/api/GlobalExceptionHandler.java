package com.chris64233.numberporting.api;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.chris64233.numberporting.service.BusinessRuleException;
import com.chris64233.numberporting.service.ErrorCode;

/** 统一错误响应：HTTP 状态 + 稳定错误码 + 可解释信息 + 时间戳。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ErrorBody(Instant timestamp, int status, String error, String message) {
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ErrorBody> handleBusiness(BusinessRuleException ex) {
        ErrorCode code = ex.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus())
                .body(body(code.getHttpStatus(), code.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse("请求参数校验失败");
        return ResponseEntity.badRequest()
                .body(body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message));
    }

    private static ErrorBody body(HttpStatus status, String error, String message) {
        return new ErrorBody(Instant.now(), status.value(), error, message);
    }
}
