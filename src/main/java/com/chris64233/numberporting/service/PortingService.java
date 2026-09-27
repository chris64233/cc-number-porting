package com.chris64233.numberporting.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.numberporting.domain.AuthorizationCode;
import com.chris64233.numberporting.domain.Carrier;
import com.chris64233.numberporting.domain.PhoneNumber;
import com.chris64233.numberporting.domain.PortingApplication;
import com.chris64233.numberporting.domain.PortingEvent;
import com.chris64233.numberporting.domain.PortingEventType;
import com.chris64233.numberporting.domain.PortingStatus;
import com.chris64233.numberporting.domain.RelationshipStatus;
import com.chris64233.numberporting.domain.SwitchWindow;
import com.chris64233.numberporting.exception.ActiveApplicationExistsException;
import com.chris64233.numberporting.exception.ApplicationNotFoundException;
import com.chris64233.numberporting.exception.AuthCodeMismatchException;
import com.chris64233.numberporting.exception.BusinessRuleException;
import com.chris64233.numberporting.exception.ErrorCode;
import com.chris64233.numberporting.exception.IdempotencyMismatchException;
import com.chris64233.numberporting.exception.IllegalTransitionException;
import com.chris64233.numberporting.exception.RollbackExecutionException;
import com.chris64233.numberporting.exception.SwitchExecutionException;
import com.chris64233.numberporting.repository.AuthorizationCodeRepository;
import com.chris64233.numberporting.repository.PhoneNumberRepository;
import com.chris64233.numberporting.repository.PortingApplicationRepository;
import com.chris64233.numberporting.repository.ServiceRelationshipRepository;
import com.chris64233.numberporting.service.view.ApplicationView;
import com.chris64233.numberporting.service.view.EventView;
import com.chris64233.numberporting.service.view.NumberOwnershipView;
import com.chris64233.numberporting.service.view.RelationshipView;
import com.chris64233.numberporting.service.view.SubmitApplicationCommand;

/**
 * 号码携转应用门面：申请提交（申请号幂等）、审核、取消、执行切换 / 回退的异常转译，
 * 以及申请详情、号码归属、事件时间线查询。
 *
 * <p>所有写路径对同一号码都先锁号码行（{@code FOR UPDATE}），再锁申请 / 授权码 / 关系行，
 * 全局锁顺序一致，因此并发提交、取消、切换、授权码失效之间不会死锁，且最多一笔成功，
 * 失败方拿到唯一可解释的业务错误码与终态。
 */
@Service
public class PortingService {

    private static final Logger log = LoggerFactory.getLogger(PortingService.class);

    private final PortingApplicationRepository applicationRepository;
    private final PhoneNumberRepository phoneNumberRepository;
    private final AuthorizationCodeRepository authCodeRepository;
    private final ServiceRelationshipRepository relationshipRepository;
    private final PortingEventRecorder eventRecorder;
    private final PortingExecutionService executionService;
    private final Clock clock;

    public PortingService(PortingApplicationRepository applicationRepository,
                          PhoneNumberRepository phoneNumberRepository,
                          AuthorizationCodeRepository authCodeRepository,
                          ServiceRelationshipRepository relationshipRepository,
                          PortingEventRecorder eventRecorder,
                          PortingExecutionService executionService,
                          Clock clock) {
        this.applicationRepository = applicationRepository;
        this.phoneNumberRepository = phoneNumberRepository;
        this.authCodeRepository = authCodeRepository;
        this.relationshipRepository = relationshipRepository;
        this.eventRecorder = eventRecorder;
        this.executionService = executionService;
        this.clock = clock;
    }

    /**
     * 提交携转申请。
     * <ol>
     *   <li>同一申请号：载荷一致直接返回首笔结果（幂等重放）；载荷不一致拒绝；</li>
     *   <li>同一号码已有活动申请（PENDING_REVIEW / APPROVED / SWITCHED 窗口内）：拒绝；</li>
     *   <li>授权码必须属于该号码、由原运营商签发、在有效期内且未被使用——成功消费一次。</li>
     * </ol>
     * 授权码自然到期的判定状态（EXPIRED）即便本次提交被拒也提交落库（noRollbackFor），
     * 使失效状态明确可见；其余失败全部回滚，不产生任何申请。
     */
    @Transactional(noRollbackFor =
            com.chris64233.numberporting.exception.AuthCodeExpiredException.class)
    public ApplicationView submit(SubmitApplicationCommand command) {
        Instant now = Instant.now(clock);

        // 1) 号码行锁：所有同号码写路径在此串行化
        PhoneNumber phone = phoneNumberRepository.findByIdForUpdate(command.number())
                .orElseThrow(() -> new com.chris64233.numberporting.exception.NumberNotFoundException(command.number()));

        // 2) 申请号幂等（无锁短读即可；并发同申请号首插已被号码行锁串行化）
        PortingApplication existing = applicationRepository.findById(command.applicationId()).orElse(null);
        if (existing != null) {
            verifyIdempotentPayload(existing, command);
            return toView(existing, true);
        }

        // 3) 唯一活动申请
        PortingApplication active = applicationRepository
                .findActiveByNumberForUpdate(command.number()).orElse(null);
        if (active != null) {
            throw new ActiveApplicationExistsException(command.number(), active.getApplicationId());
        }

        Carrier donor = parseCarrier(command.donorCarrier(), "donorCarrier");
        Carrier recipient = parseCarrier(command.recipientCarrier(), "recipientCarrier");
        if (donor == recipient) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_ERROR,
                    "donor and recipient carrier must differ");
        }
        if (phone.getCurrentCarrier() != donor) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_ERROR,
                    "donor carrier " + donor + " does not match current ownership "
                            + phone.getCurrentCarrier() + " of number " + command.number());
        }
        SwitchWindow window = new SwitchWindow(command.windowStart(), command.windowEnd());

        // 4) 锁授权码并原子消费（只能成功使用一次 + 有效期校验）
        AuthorizationCode authCode = authCodeRepository.findByCodeForUpdate(command.authCode())
                .orElseThrow(() -> new com.chris64233.numberporting.exception.AuthCodeNotFoundException(command.authCode()));
        if (!authCode.getPhoneNumber().getNumber().equals(command.number())) {
            throw new AuthCodeMismatchException(
                    "authorization code is bound to another number");
        }
        if (authCode.getCarrier() != donor) {
            throw new AuthCodeMismatchException(
                    "authorization code was not issued by donor carrier " + donor);
        }
        // consume 内部统一处理：已用 / 已撤销 / 已过期 / 超过有效期，任一失败整体回滚
        authCode.consume(command.applicationId(), now);

        PortingApplication application = new PortingApplication(command.applicationId(), phone, donor,
                recipient, authCode, window, now);
        applicationRepository.save(application);
        // 号码行锁已保证同号码活动申请互斥；uk_porting_active 唯一约束是最后防线，
        // 极端竞态下的约束违例由全局异常处理转译为 409 CONCURRENT_MODIFICATION（事务回滚、授权码不消费）。

        eventRecorder.append(command.number(), command.applicationId(),
                PortingEventType.APPLICATION_SUBMITTED, null, PortingStatus.PENDING_REVIEW, now,
                "donor=" + donor + ", recipient=" + recipient + ", window=[" + window.start()
                        + ", " + window.end() + ")");
        return toView(application, false);
    }

    /** 审核通过：进入待切换（APPROVED）。重复审核幂等返回。 */
    @Transactional(noRollbackFor = com.chris64233.numberporting.exception.SwitchWindowClosedException.class)
    public ApplicationView approve(String applicationId) {
        Instant now = Instant.now(clock);
        PortingApplication application = lockByApplication(applicationId);
        switch (application.getStatus()) {
            case PENDING_REVIEW -> {
                if (!now.isBefore(application.getSwitchWindow().end())) {
                    String reason = "approval after switch window end " + application.getSwitchWindow().end();
                    return expireForWindow(application, now, reason);
                }
                application.approve(now);
                eventRecorder.append(application.getPhoneNumber().getNumber(), applicationId,
                        PortingEventType.APPLICATION_APPROVED,
                        PortingStatus.PENDING_REVIEW, PortingStatus.APPROVED, now, null);
                return toView(application, false);
            }
            case APPROVED, SWITCHED -> {
                return toView(application, false);
            }
            default -> throw new IllegalTransitionException(
                    "application " + applicationId + " is " + application.getStatus() + ", cannot approve");
        }
    }

    /** 审核拒绝（终态）。 */
    @Transactional
    public ApplicationView reject(String applicationId, String reason) {
        Instant now = Instant.now(clock);
        PortingApplication application = lockByApplication(applicationId);
        if (application.getStatus() != PortingStatus.PENDING_REVIEW) {
            throw new IllegalTransitionException(
                    "application " + applicationId + " is " + application.getStatus() + ", cannot reject");
        }
        application.reject(now, reason);
        eventRecorder.append(application.getPhoneNumber().getNumber(), applicationId,
                PortingEventType.APPLICATION_REJECTED,
                PortingStatus.PENDING_REVIEW, PortingStatus.REJECTED, now, reason);
        return toView(application, false);
    }

    /**
     * 用户取消：仅 PENDING_REVIEW / APPROVED 可取消。切换已开始或已完成时取消请求被明确拒绝
     * （窗口内请用回退）。与切换并发时，二者在号码行锁上串行，先到者得唯一结果。
     */
    @Transactional
    public ApplicationView cancel(String applicationId) {
        Instant now = Instant.now(clock);
        PortingApplication application = lockByApplication(applicationId);
        return switch (application.getStatus()) {
            case PENDING_REVIEW, APPROVED -> {
                PortingStatus from = application.getStatus();
                application.cancel(now);
                eventRecorder.append(application.getPhoneNumber().getNumber(), applicationId,
                        PortingEventType.APPLICATION_CANCELLED,
                        from, PortingStatus.CANCELLED, now, null);
                yield toView(application, false);
            }
            case SWITCHED -> throw new IllegalTransitionException(
                    "application " + applicationId + " already switched; rollback is available before "
                            + application.getRollbackDeadline());
            default -> throw new IllegalTransitionException(
                    "application " + applicationId + " is " + application.getStatus() + ", cannot cancel");
        };
    }

    /**
     * 执行切换。期望内的业务拒绝（状态不符、窗口外）原样抛出并保留已落库的失效状态；
     * 非预期执行异常先整体回滚（归属与关系不变），用独立事务补记 SWITCH_FAILED，
     * 再转译为 {@link SwitchExecutionException}，可安全重试。
     */
    public ApplicationView switchApplication(String applicationId) {
        try {
            return toView(executionService.executeSwitch(applicationId), false);
        } catch (com.chris64233.numberporting.exception.SwitchWindowClosedException
                 | IllegalTransitionException
                 | com.chris64233.numberporting.exception.InvariantViolationException
                 | ApplicationNotFoundException
                 | com.chris64233.numberporting.exception.NumberNotFoundException e) {
            // 可解释的业务拒绝：窗口关闭（已提交失效）、状态不符、资源不存在等，直接上抛
            throw e;
        } catch (RuntimeException e) {
            // 仅非预期执行异常才整体回滚 + 补记失败事件
            if (e instanceof BusinessRuleException be) {
                throw be;
            }
            recordExecutionFailure(applicationId, PortingEventType.SWITCH_FAILED, e);
            throw new SwitchExecutionException(applicationId, e);
        }
    }

    /** 窗口内受控回退；语义同 {@link #switchApplication}。 */
    public ApplicationView rollback(String applicationId) {
        try {
            return toView(executionService.executeRollback(applicationId), false);
        } catch (com.chris64233.numberporting.exception.RollbackWindowClosedException
                 | IllegalTransitionException
                 | com.chris64233.numberporting.exception.InvariantViolationException
                 | ApplicationNotFoundException
                 | com.chris64233.numberporting.exception.NumberNotFoundException e) {
            throw e;
        } catch (RuntimeException e) {
            if (e instanceof BusinessRuleException be) {
                throw be;
            }
            recordExecutionFailure(applicationId, PortingEventType.ROLLBACK_FAILED, e);
            throw new RollbackExecutionException(applicationId, e);
        }
    }

    private void recordExecutionFailure(String applicationId, PortingEventType failureType, RuntimeException e) {
        try {
            PortingApplication fresh = applicationRepository.findById(applicationId).orElse(null);
            if (fresh == null) {
                return;
            }
            String detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            eventRecorder.recordFailureInNewTransaction(
                    fresh.getPhoneNumber().getNumber(), applicationId, failureType,
                    fresh.getStatus(), Instant.now(clock), truncate(detail));
        } catch (RuntimeException recordError) {
            // 失败事件本身落不库时只记日志，不掩盖原始异常
            log.warn("failed to record {} event for application {}", failureType, applicationId, recordError);
        }
    }

    private ApplicationView expireForWindow(PortingApplication application, Instant now, String reason) {
        PortingStatus from = application.getStatus();
        application.expire(now, reason);
        eventRecorder.append(application.getPhoneNumber().getNumber(), application.getApplicationId(),
                PortingEventType.APPLICATION_EXPIRED, from, PortingStatus.EXPIRED, now, reason);
        throw new com.chris64233.numberporting.exception.SwitchWindowClosedException(reason);
    }

    @Transactional(readOnly = true)
    public ApplicationView getApplication(String applicationId) {
        return toView(applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId)), false);
    }

    @Transactional(readOnly = true)
    public NumberOwnershipView getOwnership(String number) {
        PhoneNumber phone = phoneNumberRepository.findById(number)
                .orElseThrow(() -> new com.chris64233.numberporting.exception.NumberNotFoundException(number));
        PortingApplication active = applicationRepository
                .findByPhoneNumber_NumberAndActiveSlotNotNull(number).orElse(null);
        Carrier activeRelationshipCarrier = relationshipRepository
                .findActiveCarrier(number, RelationshipStatus.ACTIVE).orElse(null);
        if (activeRelationshipCarrier != null && activeRelationshipCarrier != phone.getCurrentCarrier()) {
            // 理论不可达：切换/回退事务已校验；暴露出来而不是掩盖
            throw new com.chris64233.numberporting.exception.InvariantViolationException(
                    "ownership " + phone.getCurrentCarrier() + " disagrees with ACTIVE relationship "
                            + activeRelationshipCarrier + " for " + number);
        }
        return new NumberOwnershipView(number, phone.getCurrentCarrier(), phone.getProvisionedAt(),
                phone.getLastSwitchedAt(),
                active == null ? null : active.getApplicationId(),
                active == null ? null : active.getStatus().name());
    }

    @Transactional(readOnly = true)
    public List<EventView> timeline(String number) {
        if (!phoneNumberRepository.existsById(number)) {
            throw new com.chris64233.numberporting.exception.NumberNotFoundException(number);
        }
        return eventRecorder.timelineOfNumber(number).stream().map(PortingService::toEventView).toList();
    }

    @Transactional(readOnly = true)
    public List<EventView> applicationTimeline(String applicationId) {
        if (!applicationRepository.existsById(applicationId)) {
            throw new ApplicationNotFoundException(applicationId);
        }
        return eventRecorder.timelineOfApplication(applicationId).stream()
                .map(PortingService::toEventView).toList();
    }

    @Transactional(readOnly = true)
    public List<RelationshipView> relationshipHistory(String number) {
        if (!phoneNumberRepository.existsById(number)) {
            throw new com.chris64233.numberporting.exception.NumberNotFoundException(number);
        }
        return relationshipRepository.findHistoryByNumber(number).stream()
                .map(r -> new RelationshipView(r.getId(), number, r.getCarrier(), r.getStatus(),
                        r.getOpenedAt(), r.getClosedAt()))
                .toList();
    }

    private PortingApplication lockByApplication(String applicationId) {
        // 投影短读号码（不加载实体）→ 锁号码行 → 锁申请行，与执行路径锁顺序一致
        String number = applicationRepository.findNumberById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
        phoneNumberRepository.findByIdForUpdate(number);
        return applicationRepository.findByIdForUpdate(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
    }

    private void verifyIdempotentPayload(PortingApplication existing, SubmitApplicationCommand repeated) {
        String id = repeated.applicationId();
        String number = existing.getPhoneNumber().getNumber();
        if (!number.equals(repeated.number())) {
            throw new IdempotencyMismatchException(id, "number", number, repeated.number());
        }
        if (!existing.getDonorCarrier().name().equals(repeated.donorCarrier())) {
            throw new IdempotencyMismatchException(id, "donorCarrier",
                    existing.getDonorCarrier(), repeated.donorCarrier());
        }
        if (!existing.getRecipientCarrier().name().equals(repeated.recipientCarrier())) {
            throw new IdempotencyMismatchException(id, "recipientCarrier",
                    existing.getRecipientCarrier(), repeated.recipientCarrier());
        }
        if (!existing.getAuthCodeValue().equals(repeated.authCode())) {
            throw new IdempotencyMismatchException(id, "authCode",
                    existing.getAuthCodeValue(), repeated.authCode());
        }
        if (!Objects.equals(existing.getSwitchWindow().start(), repeated.windowStart())
                || !Objects.equals(existing.getSwitchWindow().end(), repeated.windowEnd())) {
            throw new IdempotencyMismatchException(id, "switchWindow",
                    existing.getSwitchWindow(), "[" + repeated.windowStart() + ", " + repeated.windowEnd() + ")");
        }
    }

    private static Carrier parseCarrier(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_ERROR, field + " is required");
        }
        try {
            return Carrier.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_ERROR, "unknown carrier: " + value);
        }
    }

    private static String truncate(String s) {
        return s == null ? null : (s.length() <= 500 ? s : s.substring(0, 500));
    }

    private static ApplicationView toView(PortingApplication a, boolean idempotentReplay) {
        return new ApplicationView(
                a.getApplicationId(),
                a.getPhoneNumber().getNumber(),
                a.getDonorCarrier(),
                a.getRecipientCarrier(),
                a.getAuthCodeValue(),
                a.getSwitchWindow().start(),
                a.getSwitchWindow().end(),
                a.getStatus(),
                a.getSubmittedAt(),
                a.getReviewedAt(),
                a.getSwitchedAt(),
                a.getRollbackDeadline(),
                a.getRolledBackAt(),
                a.getEndedAt(),
                a.getEndReason(),
                idempotentReplay);
    }

    private static EventView toEventView(PortingEvent e) {
        return new EventView(e.getEventId(), e.getNumber(), e.getApplicationId(), e.getEventType(),
                e.getFromStatus(), e.getToStatus(), e.getOccurredAt(), e.getDetail());
    }
}
