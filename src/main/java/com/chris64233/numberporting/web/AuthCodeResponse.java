package com.chris64233.numberporting.web;

import java.time.Instant;

import com.chris64233.numberporting.domain.AuthCodeStatus;

public record AuthCodeResponse(
        String code,
        String number,
        String carrier,
        AuthCodeStatus status,
        Instant issuedAt,
        Instant expiresAt,
        Instant consumedAt,
        String consumedByApplicationId,
        Instant revokedAt) {
}
