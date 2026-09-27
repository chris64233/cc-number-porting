package com.chris64233.numberporting.api.dto;

import java.time.Instant;

public record IssueAuthCodeRequest(
        String code,
        String phoneNumber,
        Instant expiresAt) {
}
