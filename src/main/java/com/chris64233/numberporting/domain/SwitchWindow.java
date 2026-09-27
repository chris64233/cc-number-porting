package com.chris64233.numberporting.domain;

import java.time.Instant;

import jakarta.persistence.Embeddable;

/**
 * 期望切换窗口（半开区间 [start, end)）。切换只允许在窗口内执行。
 */
@Embeddable
public record SwitchWindow(Instant start, Instant end) {

    public SwitchWindow {
        if (start == null || end == null) {
            throw new IllegalArgumentException("switch window bounds are required");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("switch window end must be after start");
        }
    }

    public boolean contains(Instant now) {
        return !now.isBefore(start) && now.isBefore(end);
    }
}
