package com.chris64233.numberporting.api.dto;

import java.time.Instant;

public record EventView(
        Long id,
        Long orderId,
        String phoneNumber,
        String type,
        String typeDescription,
        String fromStatus,
        String toStatus,
        String detail,
        Instant occurredAt) {
}
