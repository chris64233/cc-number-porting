package com.chris64233.numberporting.api.dto;

import java.time.Instant;

import com.chris64233.numberporting.domain.PortOrderStatus;

public record OrderDetail(
        Long id,
        String requestId,
        String phoneNumber,
        String fromCarrier,
        String toCarrier,
        String authCode,
        Instant windowStart,
        Instant windowEnd,
        PortOrderStatus status,
        Instant createdAt,
        Instant approvedAt,
        Instant switchedAt,
        Instant rollbackDeadline,
        Instant rolledBackAt,
        Instant cancelledAt,
        Instant expiredAt,
        String note) {
}
