package com.chris64233.numberporting.service.view;

import java.time.Instant;

import com.chris64233.numberporting.domain.PortingEventType;
import com.chris64233.numberporting.domain.PortingStatus;

public record EventView(
        String eventId,
        String number,
        String applicationId,
        PortingEventType eventType,
        PortingStatus fromStatus,
        PortingStatus toStatus,
        Instant occurredAt,
        String detail) {
}
