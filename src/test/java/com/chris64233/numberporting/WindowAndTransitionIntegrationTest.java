package com.chris64233.numberporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.chris64233.numberporting.domain.AuthCodeStatus;
import com.chris64233.numberporting.domain.Carrier;
import com.chris64233.numberporting.domain.PortingEventType;
import com.chris64233.numberporting.domain.PortingStatus;
import com.chris64233.numberporting.exception.BusinessRuleException;
import com.chris64233.numberporting.exception.ErrorCode;
import com.chris64233.numberporting.exception.IllegalTransitionException;
import com.chris64233.numberporting.exception.RollbackWindowClosedException;
import com.chris64233.numberporting.exception.SwitchWindowClosedException;
import com.chris64233.numberporting.repository.AuthorizationCodeRepository;
import com.chris64233.numberporting.repository.PortingEventRepository;
import com.chris64233.numberporting.service.LifecycleSweepService;
import com.chris64233.numberporting.service.PortingService;
import com.chris64233.numberporting.testsupport.TestClockConfiguration;

/**
 * 切换窗口、回退窗口与非法状态迁移规则。
 */
class WindowAndTransitionIntegrationTest extends AbstractIntegrationTest {

    private static final String NUMBER = "13800000005";

    @Autowired
    private PortingService portingService;

    @Autowired
    private LifecycleSweepService lifecycleSweepService;

    @Autowired
    private PortingEventRepository eventRepository;

    @Autowired
    private AuthorizationCodeRepository authCodeRepository;

    @BeforeEach
    void setUp() {
        provision(NUMBER, Carrier.CHINA_MOBILE);
    }

    @Test
    void switchOutsideRequestedWindowExpiresTheApplication() {
        String code = issueCode(NUMBER, Duration.ofHours(2));
        portingService.submit(command("win-1", NUMBER, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, code,
                TestClockConfiguration.BASE.minus(Duration.ofMinutes(30)),
                TestClockConfiguration.BASE.plus(Duration.ofMinutes(30))));
        portingService.approve("win-1");

        clock.advance(Duration.ofMinutes(31));
        assertThatThrownBy(() -> portingService.switchApplication("win-1"))
                .isInstanceOf(SwitchWindowClosedException.class);

        // 申请已失效并释放槽位，归属不变；可重新申请
        assertThat(portingService.getApplication("win-1").status()).isEqualTo(PortingStatus.EXPIRED);
        assertThat(portingService.getOwnership(NUMBER).currentCarrier()).isEqualTo(Carrier.CHINA_MOBILE);
        assertThat(portingService.getOwnership(NUMBER).activeApplicationId()).isNull();
    }

    @Test
    void sweepExpiresApplicationsPastSwitchWindowAndCompletesSwitchedAfterRollbackDeadline() {
        // 场景 A：审核后窗口过去仍未切换 → 扫描失效
        String codeA = issueCode(NUMBER, Duration.ofHours(2));
        portingService.submit(command("win-a", NUMBER, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, codeA,
                TestClockConfiguration.BASE, TestClockConfiguration.BASE.plus(Duration.ofMinutes(10))));
        portingService.approve("win-a");
        clock.advance(Duration.ofMinutes(11));
        lifecycleSweepService.findApplicationsPastSwitchWindow()
                .forEach(lifecycleSweepService::expireIfPastWindow);
        assertThat(portingService.getApplication("win-a").status()).isEqualTo(PortingStatus.EXPIRED);

        // 场景 B：另一号码切换完成，回退窗口到期 → 结束生命周期
        String other = "13800000006";
        provision(other, Carrier.CHINA_MOBILE);
        String codeB = issueCode(other, Duration.ofHours(48));
        portingService.submit(command("win-b", other, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, codeB,
                TestClockConfiguration.BASE, TestClockConfiguration.BASE.plus(Duration.ofHours(2))));
        portingService.approve("win-b");
        portingService.switchApplication("win-b");
        clock.advance(Duration.ofHours(25));
        lifecycleSweepService.findSwitchedPastRollbackDeadline()
                .forEach(lifecycleSweepService::completeIfPastDeadline);

        // 窗口结束后申请不再是活动申请，归属保持在新运营商
        assertThat(portingService.getOwnership(other).activeApplicationId()).isNull();
        assertThat(portingService.getOwnership(other).currentCarrier()).isEqualTo(Carrier.CHINA_UNICOM);
    }

    @Test
    void rollbackAfterDeadlineIsRejectedAndNewApplicationRequired() {
        String code = issueCode(NUMBER, Duration.ofHours(48));
        portingService.submit(defaultCommand("rb-1", NUMBER, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, code));
        portingService.approve("rb-1");
        portingService.switchApplication("rb-1");

        clock.advance(Duration.ofHours(25));
        assertThatThrownBy(() -> portingService.rollback("rb-1"))
                .isInstanceOf(RollbackWindowClosedException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ROLLBACK_WINDOW_CLOSED));

        // 归属留在新运营商，槽位已释放：可以发起一笔全新申请（用新授权码）
        assertThat(portingService.getOwnership(NUMBER).currentCarrier()).isEqualTo(Carrier.CHINA_UNICOM);
        String newCode = issueCode(NUMBER, Duration.ofHours(2));
        var second = portingService.submit(defaultCommand("rb-2", NUMBER,
                Carrier.CHINA_UNICOM, Carrier.CHINA_TELECOM, newCode));
        assertThat(second.status()).isEqualTo(PortingStatus.PENDING_REVIEW);
    }

    @Test
    void illegalTransitionsAreRejectedWithExplicitState() {
        String code = issueCode(NUMBER, Duration.ofHours(2));
        portingService.submit(defaultCommand("tr-1", NUMBER, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, code));

        // 未审核不能切换
        assertThatThrownBy(() -> portingService.switchApplication("tr-1"))
                .isInstanceOf(IllegalTransitionException.class);

        portingService.approve("tr-1");
        // 已审核不能重复拒绝
        assertThatThrownBy(() -> portingService.reject("tr-1", "late"))
                .isInstanceOf(IllegalTransitionException.class);

        portingService.switchApplication("tr-1");
        // 已切换不能取消
        assertThatThrownBy(() -> portingService.cancel("tr-1"))
                .isInstanceOf(IllegalTransitionException.class);
        // 已回退后不能再回退
        portingService.rollback("tr-1");
        assertThatThrownBy(() -> portingService.rollback("tr-1"))
                .isInstanceOf(IllegalTransitionException.class);

        assertThat(portingService.getApplication("tr-1").status()).isEqualTo(PortingStatus.ROLLED_BACK);
    }

    @Test
    void revokingAnUnusedAuthCodeMakesItUnusableForSubmission() {
        String code = issueCode(NUMBER, Duration.ofHours(2));
        authorizationCodeService.revoke(code);

        assertThat(authCodeRepository.findByCode(code).orElseThrow().getStatus())
                .isEqualTo(AuthCodeStatus.REVOKED);
        assertThatThrownBy(() -> portingService.submit(
                defaultCommand("rev-1", NUMBER, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, code)))
                .isInstanceOf(com.chris64233.numberporting.exception.AuthCodeRevokedException.class);
        // 没有产生任何活动申请，归属不变
        assertThat(portingService.getOwnership(NUMBER).activeApplicationId()).isNull();
        assertThat(portingService.getOwnership(NUMBER).currentCarrier()).isEqualTo(Carrier.CHINA_MOBILE);
    }

    @Test
    void naturalExpiryOfAuthCodeIsPickedUpBySweep() {
        String code = issueCode(NUMBER, Duration.ofMinutes(10));
        clock.advance(Duration.ofMinutes(11));
        authorizationCodeService.findDueIds().forEach(authorizationCodeService::expireIfDue);

        assertThat(authCodeRepository.findByCode(code).orElseThrow().getStatus())
                .isEqualTo(AuthCodeStatus.EXPIRED);
        assertThatThrownBy(() -> portingService.submit(
                defaultCommand("rev-2", NUMBER, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, code)))
                .isInstanceOf(com.chris64233.numberporting.exception.AuthCodeExpiredException.class);
    }
}
