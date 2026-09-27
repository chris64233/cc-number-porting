package com.chris64233.numberporting.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

/**
 * 携转申请提交请求。申请号由调用方生成并保证稳定重试。
 */
public record SubmitPortingRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}",
                message = "applicationId must be 1-64 chars of [A-Za-z0-9_-]")
        String applicationId,
        @NotBlank @Pattern(regexp = "\\d{6,20}", message = "number must be 6-20 digits")
        String number,
        @NotBlank String donorCarrier,
        @NotBlank String recipientCarrier,
        @NotBlank String authCode,
        @NotNull Instant windowStart,
        @NotNull Instant windowEnd) {
}
