package com.chris64233.numberporting.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：业务全部经由注入的 {@link Clock} 取时，测试可自由推进时间，
 * 验证授权码有效期、切换窗口、回退窗口。所有方法 synchronized 以支持并发测试。
 */
public class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone = ZoneOffset.UTC;

    public MutableClock(Instant initial) {
        this.instant = initial;
    }

    public synchronized void advance(Duration duration) {
        this.instant = instant.plus(duration);
    }

    public synchronized void setInstant(Instant instant) {
        this.instant = instant;
    }

    @Override
    public synchronized Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
