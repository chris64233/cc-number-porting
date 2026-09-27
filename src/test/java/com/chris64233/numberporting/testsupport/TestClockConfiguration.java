package com.chris64233.numberporting.testsupport;

import java.time.Clock;
import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 用固定起点的可控时钟替换生产时钟（2026-09-27T00:00:00Z）。
 * {@link MutableClock} 本身就是 {@link Clock}，作为唯一 @Primary Clock Bean，
 * 同时可按具体类型注入以推进时间。
 */
@TestConfiguration
public class TestClockConfiguration {

    public static final Instant BASE = Instant.parse("2026-09-27T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE);
    }
}
