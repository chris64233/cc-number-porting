package com.chris64233.numberporting.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 携转业务参数。 */
@ConfigurationProperties(prefix = "number-porting")
public record PortingProperties(Duration rollbackWindow) {

    public PortingProperties {
        if (rollbackWindow == null) {
            rollbackWindow = Duration.ofDays(2);
        }
    }
}
