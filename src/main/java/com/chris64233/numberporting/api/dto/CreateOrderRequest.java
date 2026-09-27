package com.chris64233.numberporting.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateOrderRequest(
        @NotBlank(message = "requestId 不能为空")
        @Size(max = 64)
        String requestId,

        @NotBlank(message = "号码不能为空")
        @Size(max = 16)
        String phoneNumber,

        @NotBlank(message = "原运营商不能为空")
        String fromCarrier,

        @NotBlank(message = "新运营商不能为空")
        String toCarrier,

        @NotBlank(message = "授权码不能为空")
        String authCode,

        @NotNull(message = "期望切换窗口起点不能为空")
        Instant windowStart,

        @NotNull(message = "期望切换窗口终点不能为空")
        Instant windowEnd) {
}
