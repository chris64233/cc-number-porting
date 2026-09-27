package com.chris64233.numberporting.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 用可变时钟替换系统时钟，测试可精确控制授权码/窗口/回退截止等时间相关规则。 */
@TestConfiguration
public class TestClockConfig {

    private static final Instant EPOCH = Instant.parse("2026-09-01T00:00:00Z");

    private static final MutableClock CLOCK = MutableClock.startAt(EPOCH);

    @Bean
    @Primary
    public Clock clock() {
        return CLOCK;
    }

    /** 重置到固定起点，避免测试间状态污染。 */
    public static void reset() {
        CLOCK.setInstant(EPOCH);
    }

    public static Instant now() {
        return CLOCK.instant();
    }

    public static void advance(Duration duration) {
        CLOCK.advance(duration);
    }

    public static void setInstant(Instant instant) {
        CLOCK.setInstant(instant);
    }

    public static ZoneId zone() {
        return ZoneId.of("UTC");
    }
}
