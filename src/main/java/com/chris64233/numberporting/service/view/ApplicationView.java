package com.chris64233.numberporting.service.view;

import java.time.Instant;

import com.chris64233.numberporting.domain.Carrier;
import com.chris64233.numberporting.domain.PortingStatus;

/**
 * 申请详情。事件时间线通过独立接口查询。
 */
public record ApplicationView(
        String applicationId,
        String number,
        Carrier donorCarrier,
        Carrier recipientCarrier,
        String authCode,
        Instant windowStart,
        Instant windowEnd,
        PortingStatus status,
        Instant submittedAt,
        Instant reviewedAt,
        Instant switchedAt,
        Instant rollbackDeadline,
        Instant rolledBackAt,
        Instant endedAt,
        String endReason,
        boolean idempotentReplay) {
}
