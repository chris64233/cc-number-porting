package com.chris64233.numberporting.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 号码携转业务参数。
 *
 * @param rollbackWindow 切换完成后允许受控回退的窗口长度
 * @param schedulingEnabled 是否开启授权码到期 / 回退窗口结束的后台扫描
 */
@ConfigurationProperties(prefix = "number-porting")
public record PortingProperties(Duration rollbackWindow, boolean schedulingEnabled) {

    public PortingProperties {
        if (rollbackWindow == null || rollbackWindow.isZero() || rollbackWindow.isNegative()) {
            rollbackWindow = Duration.ofHours(24);
        }
    }

    public static PortingProperties defaults() {
        return new PortingProperties(Duration.ofHours(24), true);
    }
}
