package com.chris64233.numberporting.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    /** 统一时钟，测试可替换为可控时钟以驱动授权码/窗口/回退截止等时间规则。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** 便于业务代码取当前时间。 */
    public static Instant now(Clock clock) {
        return Instant.now(clock);
    }
}
