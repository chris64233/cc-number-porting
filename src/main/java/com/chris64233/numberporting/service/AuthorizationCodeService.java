package com.chris64233.numberporting.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.numberporting.domain.AuthCodeStatus;
import com.chris64233.numberporting.domain.AuthorizationCode;
import com.chris64233.numberporting.domain.PhoneNumber;
import com.chris64233.numberporting.domain.PortingEventType;
import com.chris64233.numberporting.domain.PortingStatus;
import com.chris64233.numberporting.exception.AuthCodeNotFoundException;
import com.chris64233.numberporting.exception.NumberNotFoundException;
import com.chris64233.numberporting.repository.AuthorizationCodeRepository;
import com.chris64233.numberporting.repository.PhoneNumberRepository;
import com.chris64233.numberporting.repository.PortingApplicationRepository;

/**
 * 授权码签发、撤销与到期处理。
 *
 * <p>撤销 / 自然到期一笔正被活动申请使用的授权码时（“授权码失效”与申请生命周期并发），
 * 在同一事务里把申请置为 EXPIRED、释放活动槽位并追加事件，得到唯一可解释终态。
 *
 * <p>锁顺序与全部写路径一致：先号码行（无锁短读授权码得到号码），再锁号码行，
 * 然后授权码行、申请行，避免与提交路径交叉死锁。
 */
@Service
public class AuthorizationCodeService {

    private static final Duration DEFAULT_VALIDITY = Duration.ofHours(24);

    private final AuthorizationCodeRepository authCodeRepository;
    private final PhoneNumberRepository phoneNumberRepository;
    private final PortingApplicationRepository applicationRepository;
    private final PortingEventRecorder eventRecorder;
    private final Clock clock;

    public AuthorizationCodeService(AuthorizationCodeRepository authCodeRepository,
                                    PhoneNumberRepository phoneNumberRepository,
                                    PortingApplicationRepository applicationRepository,
                                    PortingEventRecorder eventRecorder,
                                    Clock clock) {
        this.authCodeRepository = authCodeRepository;
        this.phoneNumberRepository = phoneNumberRepository;
        this.applicationRepository = applicationRepository;
        this.eventRecorder = eventRecorder;
        this.clock = clock;
    }

    /** 由号码当前归属运营商签发授权码，有效期默认 24 小时。 */
    @Transactional
    public AuthorizationCode issue(String number, Duration validity) {
        Instant now = Instant.now(clock);
        PhoneNumber phoneNumber = phoneNumberRepository.findByIdForUpdate(number)
                .orElseThrow(() -> new NumberNotFoundException(number));
        Duration ttl = (validity == null || validity.isZero() || validity.isNegative())
                ? DEFAULT_VALIDITY : validity;
        String code = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        return authCodeRepository.save(new AuthorizationCode(
                code, phoneNumber, phoneNumber.getCurrentCarrier(), now, now.plus(ttl)));
    }

    /**
     * 主动撤销授权码。若已有活动申请引用它（PENDING_REVIEW / APPROVED），申请在同一事务内失效。
     * 对已终态授权码幂等。
     */
    @Transactional
    public AuthorizationCode revoke(String code) {
        // 投影短读拿到号码 → 锁号码行 → 锁授权码行（此时才首次加载实体），固定全局锁顺序，
        // 避免在号码锁等待期间持有授权码旧版本导致会话版本冲突
        String number = authCodeRepository.findNumberByCode(code)
                .orElseThrow(() -> new AuthCodeNotFoundException(code));
        phoneNumberRepository.findByIdForUpdate(number)
                .orElseThrow(() -> new NumberNotFoundException(number));
        AuthorizationCode authCode = authCodeRepository.findByCodeForUpdate(code)
                .orElseThrow(() -> new AuthCodeNotFoundException(code));
        if (authCode.getStatus() != AuthCodeStatus.ISSUED) {
            return authCode;
        }
        Instant now = Instant.now(clock);
        authCode.revoke(now);
        expireActiveApplicationUsing(authCode, now, PortingEventType.AUTH_CODE_REVOKED,
                "authorization code " + code + " revoked by donor carrier");
        return authCode;
    }

    /** 扫描候选：已到有效期但仍 ISSUED 的授权码 ID（不加锁）。 */
    @Transactional(readOnly = true)
    public List<Long> findDueIds() {
        return authCodeRepository.findExpirableIds(AuthCodeStatus.ISSUED, Instant.now(clock));
    }

    /**
     * 逐笔处理自然到期：独立事务，锁顺序 号码行 → 授权码行 → 申请行。
     * 已被其他事务处理（撤销/消费）则空操作。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireIfDue(Long id) {
        String number = authCodeRepository.findNumberById(id).orElse(null);
        if (number == null) {
            return;
        }
        phoneNumberRepository.findByIdForUpdate(number);
        AuthorizationCode authCode = authCodeRepository.findIdForUpdate(id).orElse(null);
        if (authCode == null) {
            return;
        }
        Instant now = Instant.now(clock);
        if (authCode.getStatus() != AuthCodeStatus.ISSUED || now.isBefore(authCode.getExpiresAt())) {
            return;
        }
        authCode.markExpired(now);
        expireActiveApplicationUsing(authCode, now, PortingEventType.AUTH_CODE_EXPIRED,
                "authorization code expired at " + authCode.getExpiresAt());
    }

    private void expireActiveApplicationUsing(AuthorizationCode authCode, Instant now,
                                              PortingEventType authEvent, String reason) {
        String number = authCode.getPhoneNumber().getNumber();
        String code = authCode.getCode();
        applicationRepository.findActiveByNumberForUpdate(number).ifPresent(active -> {
            if (!code.equals(active.getAuthCodeValue())) {
                return;
            }
            PortingStatus from = active.getStatus();
            active.expire(now, reason);
            eventRecorder.append(number, active.getApplicationId(), PortingEventType.APPLICATION_EXPIRED,
                    from, PortingStatus.EXPIRED, now, reason);
            eventRecorder.append(number, active.getApplicationId(), authEvent,
                    from, PortingStatus.EXPIRED, now, null);
        });
    }
}
