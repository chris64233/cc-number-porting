package com.chris64233.numberporting.service.view;

import java.time.Instant;

/**
 * 提交携转申请的入参。
 */
public record SubmitApplicationCommand(
        String applicationId,
        String number,
        String donorCarrier,
        String recipientCarrier,
        String authCode,
        Instant windowStart,
        Instant windowEnd) {
}
