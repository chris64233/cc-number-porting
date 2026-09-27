package com.chris64233.numberporting.service.view;

import java.time.Instant;

import com.chris64233.numberporting.domain.Carrier;
import com.chris64233.numberporting.domain.RelationshipStatus;

public record RelationshipView(
        Long id,
        String number,
        Carrier carrier,
        RelationshipStatus status,
        Instant openedAt,
        Instant closedAt) {
}
