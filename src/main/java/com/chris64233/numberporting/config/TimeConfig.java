package com.chris64233.numberporting.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 统一时间源：业务逻辑全部经由注入的 {@link Clock} 取当前时间，
 * 测试可替换为固定时钟 / 可控时钟来验证有效期与窗口。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
