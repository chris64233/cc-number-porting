package com.chris64233.numberporting.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 授权码签发请求：validityMinutes 为空时使用服务端默认有效期（24 小时）。
 */
public record IssueAuthCodeRequest(
        @NotBlank @Pattern(regexp = "\\d{6,20}", message = "number must be 6-20 digits")
        String number,
        @Positive Long validityMinutes) {
}
