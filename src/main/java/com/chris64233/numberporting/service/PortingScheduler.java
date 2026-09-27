package com.chris64233.numberporting.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 后台定时扫描：授权码到期、切换窗口超时、回退窗口结束。
 * 可用 number-porting.scheduling-enabled=false 关闭（测试中通常关闭以保持时间确定性）。
 */
@Component
@ConditionalOnProperty(name = "number-porting.scheduling-enabled", havingValue = "true",
        matchIfMissing = true)
public class PortingScheduler {

    private static final Logger log = LoggerFactory.getLogger(PortingScheduler.class);

    private final AuthorizationCodeService authorizationCodeService;
    private final LifecycleSweepService lifecycleSweepService;

    public PortingScheduler(AuthorizationCodeService authorizationCodeService,
                            LifecycleSweepService lifecycleSweepService) {
        this.authorizationCodeService = authorizationCodeService;
        this.lifecycleSweepService = lifecycleSweepService;
    }

    @Scheduled(fixedDelayString = "${number-porting.sweep-interval-ms:60000}")
    public void sweep() {
        try {
            authorizationCodeService.findDueIds().forEach(authorizationCodeService::expireIfDue);
            lifecycleSweepService.findApplicationsPastSwitchWindow()
                    .forEach(lifecycleSweepService::expireIfPastWindow);
            lifecycleSweepService.findSwitchedPastRollbackDeadline()
                    .forEach(lifecycleSweepService::completeIfPastDeadline);
        } catch (RuntimeException e) {
            // 扫描失败不能拖垮调度线程；下一轮继续
            log.warn("porting lifecycle sweep failed", e);
        }
    }
}
