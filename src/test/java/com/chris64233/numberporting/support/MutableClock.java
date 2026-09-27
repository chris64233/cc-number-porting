package com.chris64233.numberporting.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/** 测试用可变时钟：可显式推进/跳变，用于驱动授权码有效期、切换窗口与回退窗口。 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> current;

    public MutableClock(Instant start) {
        this.current = new AtomicReference<>(start);
    }

    public static MutableClock startAt(Instant start) {
        return new MutableClock(start);
    }

    public void setInstant(Instant instant) {
        current.set(instant);
    }

    public void advanceSeconds(long seconds) {
        current.updateAndGet(t -> t.plusSeconds(seconds));
    }

    public void advance(java.time.Duration duration) {
        current.updateAndGet(t -> t.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return current.get();
    }
}
