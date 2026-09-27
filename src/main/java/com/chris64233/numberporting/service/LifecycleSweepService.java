package com.chris64233.numberporting.service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.numberporting.domain.PortingApplication;
import com.chris64233.numberporting.domain.PortingEventType;
import com.chris64233.numberporting.domain.PortingStatus;
import com.chris64233.numberporting.repository.PhoneNumberRepository;
import com.chris64233.numberporting.repository.PortingApplicationRepository;

/**
 * 生命周期后台处理（逐笔独立事务，可配置关闭）：
 * <ul>
 *   <li>超过期望切换窗口仍未切换的 PENDING_REVIEW / APPROVED 申请 → EXPIRED；</li>
 *   <li>回退窗口到期的 SWITCHED 申请 → 结束生命周期、释放活动槽位（号码归属保持新运营商）。</li>
 * </ul>
 * 这些规则在用户路径（审核 / 切换 / 回退 / 查询活动申请）上也会即时判定，扫描只是最终兜底。
 */
@Service
public class LifecycleSweepService {

    private final PortingApplicationRepository applicationRepository;
    private final PhoneNumberRepository phoneNumberRepository;
    private final PortingEventRecorder eventRecorder;
    private final PortingExecutionService executionService;
    private final Clock clock;

    public LifecycleSweepService(PortingApplicationRepository applicationRepository,
                                 PhoneNumberRepository phoneNumberRepository,
                                 PortingEventRecorder eventRecorder,
                                 PortingExecutionService executionService,
                                 Clock clock) {
        this.applicationRepository = applicationRepository;
        this.phoneNumberRepository = phoneNumberRepository;
        this.eventRecorder = eventRecorder;
        this.executionService = executionService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<String> findApplicationsPastSwitchWindow() {
        return applicationRepository.findApplicationIdsPastWindow(
                EnumSet.of(PortingStatus.PENDING_REVIEW, PortingStatus.APPROVED), Instant.now(clock));
    }

    /** 逐笔独立事务：锁顺序号码行 → 申请行，与在线路径一致。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireIfPastWindow(String applicationId) {
        String number = applicationRepository.findNumberById(applicationId).orElse(null);
        if (number == null) {
            return;
        }
        phoneNumberRepository.findByIdForUpdate(number);
        PortingApplication application = applicationRepository.findByIdForUpdate(applicationId).orElse(null);
        if (application == null) {
            return;
        }
        Instant now = Instant.now(clock);
        if ((application.getStatus() != PortingStatus.PENDING_REVIEW
                && application.getStatus() != PortingStatus.APPROVED)
                || now.isBefore(application.getSwitchWindow().end())) {
            return;
        }
        PortingStatus from = application.getStatus();
        String reason = "switch window ended at " + application.getSwitchWindow().end() + " without switch";
        application.expire(now, reason);
        eventRecorder.append(number, applicationId, PortingEventType.APPLICATION_EXPIRED,
                from, PortingStatus.EXPIRED, now, reason);
    }

    @Transactional(readOnly = true)
    public List<String> findSwitchedPastRollbackDeadline() {
        return applicationRepository.findSwitchedPastDeadline(PortingStatus.SWITCHED, Instant.now(clock))
                .stream().map(PortingApplication::getApplicationId).toList();
    }

    /** 逐笔独立事务完成回退窗口。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeIfPastDeadline(String applicationId) {
        var deadline = applicationRepository
                .findRollbackDeadline(applicationId, PortingStatus.SWITCHED).orElse(null);
        if (deadline == null || Instant.now(clock).isBefore(deadline)) {
            return;
        }
        executionService.completeRollbackWindow(applicationId);
    }
}
