package com.chris64233.numberporting.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.numberporting.config.PortingProperties;
import com.chris64233.numberporting.domain.Carrier;
import com.chris64233.numberporting.domain.PhoneNumber;
import com.chris64233.numberporting.domain.PortingApplication;
import com.chris64233.numberporting.domain.PortingEventType;
import com.chris64233.numberporting.domain.PortingStatus;
import com.chris64233.numberporting.domain.RelationshipStatus;
import com.chris64233.numberporting.domain.ServiceRelationship;
import com.chris64233.numberporting.exception.IllegalTransitionException;
import com.chris64233.numberporting.exception.InvariantViolationException;
import com.chris64233.numberporting.exception.RollbackWindowClosedException;
import com.chris64233.numberporting.exception.SwitchWindowClosedException;
import com.chris64233.numberporting.repository.PhoneNumberRepository;
import com.chris64233.numberporting.repository.PortingApplicationRepository;
import com.chris64233.numberporting.repository.ServiceRelationshipRepository;

/**
 * 切换 / 回退的事务边界：归属变更、旧服务关系关闭、新服务关系建立与事件追加
 * 全部在<strong>同一个数据库事务</strong>内完成，事务结束前做不变量校验
 * （恰好一条 ACTIVE 关系，且其运营商等于号码归属）。任一步失败整体回滚，
 * 绝不留下双归属或无归属号码。
 *
 * <p>锁顺序固定为 号码行 → 申请行 → 关系行，与提交（授权码行 → 号码行 → 申请行）
 * 等其他路径对同一号码的操作在号码行上串行化，避免死锁与并发交叉。
 */
@Service
public class PortingExecutionService {

    private static final Logger log = LoggerFactory.getLogger(PortingExecutionService.class);

    private final PortingApplicationRepository applicationRepository;
    private final PhoneNumberRepository phoneNumberRepository;
    private final ServiceRelationshipRepository relationshipRepository;
    private final PortingEventRecorder eventRecorder;
    private final PortingProperties properties;
    private final Clock clock;

    /**
     * 测试用故障注入钩子：在“旧关系已关闭、新关系尚未建立”的临界点抛出异常，
     * 验证整个切换事务回滚后号码仍是唯一归属。生产环境保持 null。
     */
    private volatile Consumer<PortingApplication> switchFailureHook;

    public PortingExecutionService(PortingApplicationRepository applicationRepository,
                                   PhoneNumberRepository phoneNumberRepository,
                                   ServiceRelationshipRepository relationshipRepository,
                                   PortingEventRecorder eventRecorder,
                                   PortingProperties properties,
                                   Clock clock) {
        this.applicationRepository = applicationRepository;
        this.phoneNumberRepository = phoneNumberRepository;
        this.relationshipRepository = relationshipRepository;
        this.eventRecorder = eventRecorder;
        this.properties = properties;
        this.clock = clock;
    }

    /** 仅供测试注入故障。 */
    public void setSwitchFailureHook(Consumer<PortingApplication> hook) {
        this.switchFailureHook = hook;
    }

    /**
     * 原子执行切换。前置状态不符时抛 {@link IllegalTransitionException}（回滚，无任何修改）；
     * 当前时间已在期望切换窗口外时，申请先置 EXPIRED 并提交，再抛
     * {@link SwitchWindowClosedException}（noRollbackFor，不回滚该状态变化）。
     */
    @Transactional(noRollbackFor = SwitchWindowClosedException.class)
    public PortingApplication executeSwitch(String applicationId) {
        Instant now = Instant.now(clock);

        PhoneNumber phone = lockNumberByApplication(applicationId);
        String number = phone.getNumber();

        PortingApplication application = applicationRepository.findByIdForUpdate(applicationId)
                .orElseThrow(() -> new com.chris64233.numberporting.exception.ApplicationNotFoundException(applicationId));

        if (application.getStatus() != PortingStatus.APPROVED) {
            // 已被取消/失效/并发切换等：返回唯一可解释的实际终态
            throw new IllegalTransitionException(
                    "application " + applicationId + " is " + application.getStatus()
                            + ", only APPROVED applications can be switched");
        }
        if (!application.getSwitchWindow().contains(now)) {
            // 窗口外：申请失效（释放槽位 + 事件）与本次拒绝必须一起落库
            PortingStatus from = application.getStatus();
            String reason = "switch executed outside requested window ["
                    + application.getSwitchWindow().start() + ", " + application.getSwitchWindow().end() + ")";
            application.expire(now, reason);
            eventRecorder.append(number, applicationId, PortingEventType.APPLICATION_EXPIRED,
                    from, PortingStatus.EXPIRED, now, reason);
            throw SwitchWindowClosedException.forApplication(applicationId);
        }

        Carrier donor = application.getDonorCarrier();
        Carrier recipient = application.getRecipientCarrier();

        eventRecorder.append(number, applicationId, PortingEventType.SWITCH_STARTED,
                PortingStatus.APPROVED, PortingStatus.APPROVED, now,
                "switching from " + donor + " to " + recipient);

        // 1) 加锁加载并关闭旧服务关系
        List<ServiceRelationship> active = relationshipRepository.findActiveByNumberForUpdate(
                number, RelationshipStatus.ACTIVE);
        if (active.size() != 1) {
            throw new InvariantViolationException(
                    "expected exactly one ACTIVE relationship for " + number + ", found " + active.size());
        }
        ServiceRelationship oldRelationship = active.get(0);
        if (oldRelationship.getCarrier() != donor) {
            throw new InvariantViolationException("ACTIVE relationship carrier " + oldRelationship.getCarrier()
                    + " does not match donor " + donor);
        }
        oldRelationship.close(now);

        // 故障注入点（测试）：旧关系已关闭、号码归属和新关系尚未动。
        Consumer<PortingApplication> hook = switchFailureHook;
        if (hook != null) {
            hook.accept(application);
        }

        // 2) 建立新服务关系并原子更新号码归属
        relationshipRepository.save(new ServiceRelationship(phone, recipient, now));
        phone.setCurrentCarrier(recipient);
        phone.setLastSwitchedAt(now);

        // 3) 申请进入已切换（回退窗口内），确定回退截止时间
        Instant rollbackDeadline = now.plus(properties.rollbackWindow());
        application.markSwitched(now, rollbackDeadline);

        // 4) flush 后做提交前不变量校验：恰好一条 ACTIVE，且归属一致 —— 无双归属/无归属
        relationshipRepository.flush();
        long activeCount = relationshipRepository.countActiveByNumber(number, RelationshipStatus.ACTIVE);
        if (activeCount != 1) {
            throw new InvariantViolationException(
                    "post-switch invariant violated for " + number + ": active relationships=" + activeCount);
        }
        Carrier activeCarrier = relationshipRepository
                .findActiveCarrier(number, RelationshipStatus.ACTIVE)
                .orElseThrow(() -> new InvariantViolationException("no ACTIVE carrier after switch: " + number));
        if (activeCarrier != recipient || phone.getCurrentCarrier() != recipient) {
            throw new InvariantViolationException(
                    "post-switch carrier mismatch for " + number + ": ownership=" + phone.getCurrentCarrier()
                            + ", activeRelationship=" + activeCarrier + ", expected=" + recipient);
        }

        eventRecorder.append(number, applicationId, PortingEventType.SWITCH_COMPLETED,
                PortingStatus.APPROVED, PortingStatus.SWITCHED, now,
                "rollback allowed before " + rollbackDeadline);

        log.info("number {} switched {} -> {}, rollback deadline {}", number, donor, recipient, rollbackDeadline);
        return application;
    }

    /**
     * 窗口内受控回退：完整恢复原归属与原运营商 ACTIVE 服务关系（关闭当前新运营商关系、
     * 新建原运营商关系，保留完整历史）。超过回退窗口时，申请结束生命周期并提交，
     * 再抛 {@link RollbackWindowClosedException}（noRollbackFor），此后只能新建携转申请。
     */
    @Transactional(noRollbackFor = RollbackWindowClosedException.class)
    public PortingApplication executeRollback(String applicationId) {
        Instant now = Instant.now(clock);

        PhoneNumber phone = lockNumberByApplication(applicationId);
        String number = phone.getNumber();

        PortingApplication application = applicationRepository.findByIdForUpdate(applicationId)
                .orElseThrow(() -> new com.chris64233.numberporting.exception.ApplicationNotFoundException(applicationId));

        if (application.getStatus() != PortingStatus.SWITCHED) {
            throw new IllegalTransitionException(
                    "application " + applicationId + " is " + application.getStatus()
                            + ", only SWITCHED applications can be rolled back");
        }
        if (!now.isBefore(application.getRollbackDeadline())) {
            // 超窗不允许回退：申请结束生命周期，只能重新提交申请。该状态变化也要落库。
            application.completeLifecycle(now, "rollback requested after window; new application required");
            eventRecorder.append(number, applicationId, PortingEventType.APPLICATION_COMPLETED,
                    PortingStatus.SWITCHED, PortingStatus.SWITCHED, now,
                    "late rollback rejected, deadline was " + application.getRollbackDeadline());
            throw new RollbackWindowClosedException(applicationId, application.getRollbackDeadline());
        }

        Carrier donor = application.getDonorCarrier();

        eventRecorder.append(number, applicationId, PortingEventType.ROLLBACK_STARTED,
                PortingStatus.SWITCHED, PortingStatus.SWITCHED, now,
                "rolling back to " + donor);

        List<ServiceRelationship> active = relationshipRepository.findActiveByNumberForUpdate(
                number, RelationshipStatus.ACTIVE);
        if (active.size() != 1) {
            throw new InvariantViolationException(
                    "expected exactly one ACTIVE relationship for " + number + ", found " + active.size());
        }
        ServiceRelationship current = active.get(0);
        if (current.getCarrier() != application.getRecipientCarrier()) {
            throw new InvariantViolationException("ACTIVE relationship carrier " + current.getCarrier()
                    + " does not match recipient " + application.getRecipientCarrier());
        }
        current.close(now);
        relationshipRepository.save(new ServiceRelationship(phone, donor, now));
        phone.setCurrentCarrier(donor);
        phone.setLastSwitchedAt(now);

        application.markRolledBack(now);

        relationshipRepository.flush();
        long activeCount = relationshipRepository.countActiveByNumber(number, RelationshipStatus.ACTIVE);
        if (activeCount != 1) {
            throw new InvariantViolationException(
                    "post-rollback invariant violated for " + number + ": active relationships=" + activeCount);
        }
        Carrier activeCarrier = relationshipRepository
                .findActiveCarrier(number, RelationshipStatus.ACTIVE)
                .orElseThrow(() -> new InvariantViolationException("no ACTIVE carrier after rollback: " + number));
        if (activeCarrier != donor || phone.getCurrentCarrier() != donor) {
            throw new InvariantViolationException(
                    "post-rollback carrier mismatch for " + number + ": ownership=" + phone.getCurrentCarrier()
                            + ", activeRelationship=" + activeCarrier + ", expected=" + donor);
        }

        eventRecorder.append(number, applicationId, PortingEventType.ROLLBACK_COMPLETED,
                PortingStatus.SWITCHED, PortingStatus.ROLLED_BACK, now,
                "ownership and service relationship restored to " + donor);

        log.info("number {} rolled back to {}", number, donor);
        return application;
    }

    /**
     * 回退窗口到期：正常结束 SWITCHED 申请、释放活动槽位（归属保持在新运营商不变）。
     */
    @Transactional
    public PortingApplication completeRollbackWindow(String applicationId) {
        Instant now = Instant.now(clock);
        // 与切换/回退相同的锁顺序：号码行 → 申请行，杜绝交叉死锁。
        PhoneNumber phone = lockNumberByApplication(applicationId);
        PortingApplication application = applicationRepository.findByIdForUpdate(applicationId)
                .orElseThrow(() -> new com.chris64233.numberporting.exception.ApplicationNotFoundException(applicationId));
        if (application.getStatus() != PortingStatus.SWITCHED) {
            return application;
        }
        application.completeLifecycle(now, "rollback window closed; porting finalized");
        eventRecorder.append(phone.getNumber(), applicationId, PortingEventType.APPLICATION_COMPLETED,
                PortingStatus.SWITCHED, PortingStatus.SWITCHED, now,
                "rollback window closed at " + application.getRollbackDeadline());
        return application;
    }

    private PhoneNumber lockNumberByApplication(String applicationId) {
        // 先用投影短读拿到号码（不加载实体、不产生版本冲突），再按固定顺序
        // 号码行 → 申请行 加锁。
        String number = applicationRepository.findNumberById(applicationId)
                .orElseThrow(() -> new com.chris64233.numberporting.exception.ApplicationNotFoundException(applicationId));
        return phoneNumberRepository.findByIdForUpdate(number)
                .orElseThrow(() -> new com.chris64233.numberporting.exception.NumberNotFoundException(number));
    }

    public Duration rollbackWindow() {
        return properties.rollbackWindow();
    }
}
