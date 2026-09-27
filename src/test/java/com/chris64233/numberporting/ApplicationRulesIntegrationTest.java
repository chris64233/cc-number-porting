package com.chris64233.numberporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.chris64233.numberporting.domain.AuthCodeStatus;
import com.chris64233.numberporting.domain.Carrier;
import com.chris64233.numberporting.domain.PortingStatus;
import com.chris64233.numberporting.exception.ActiveApplicationExistsException;
import com.chris64233.numberporting.exception.AuthCodeAlreadyUsedException;
import com.chris64233.numberporting.exception.AuthCodeExpiredException;
import com.chris64233.numberporting.exception.AuthCodeMismatchException;
import com.chris64233.numberporting.exception.BusinessRuleException;
import com.chris64233.numberporting.exception.ErrorCode;
import com.chris64233.numberporting.exception.IdempotencyMismatchException;
import com.chris64233.numberporting.repository.AuthorizationCodeRepository;
import com.chris64233.numberporting.service.PortingService;
import com.chris64233.numberporting.service.view.ApplicationView;

/**
 * 授权码规则（单次消费、有效期、归属校验）与申请幂等、唯一活动申请规则。
 */
class ApplicationRulesIntegrationTest extends AbstractIntegrationTest {

    private static final String NUMBER = "13800000002";

    @Autowired
    private PortingService portingService;

    @Autowired
    private AuthorizationCodeRepository authCodeRepository;

    @BeforeEach
    void setUp() {
        provision(NUMBER, Carrier.CHINA_MOBILE);
    }

    @Test
    void sameApplicationIdIsIdempotentAndDoesNotConsumeAnotherCode() {
        String code = issueCode(NUMBER, Duration.ofHours(2));
        var cmd = defaultCommand("idem-1", NUMBER, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, code);

        ApplicationView first = portingService.submit(cmd);
        ApplicationView replay = portingService.submit(cmd);

        assertThat(replay.status()).isEqualTo(PortingStatus.PENDING_REVIEW);
        assertThat(replay.applicationId()).isEqualTo(first.applicationId());
        assertThat(replay.submittedAt()).isEqualTo(first.submittedAt());
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(authCodeRepository.findByCode(code).orElseThrow().getStatus())
                .isEqualTo(AuthCodeStatus.CONSUMED);
    }

    @Test
    void sameApplicationIdWithDifferentPayloadIsRejected() {
        String code = issueCode(NUMBER, Duration.ofHours(2));
        portingService.submit(defaultCommand("idem-2", NUMBER, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, code));

        var differentRecipient = defaultCommand("idem-2", NUMBER, Carrier.CHINA_MOBILE,
                Carrier.CHINA_TELECOM, code);
        assertThatThrownBy(() -> portingService.submit(differentRecipient))
                .isInstanceOf(IdempotencyMismatchException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getErrorCode())
                        .isEqualTo(ErrorCode.IDEMPOTENCY_MISMATCH));

        // 原申请状态不变
        assertThat(portingService.getApplication("idem-2").recipientCarrier())
                .isEqualTo(Carrier.CHINA_UNICOM);
    }

    @Test
    void oneActiveApplicationPerNumber() {
        String code1 = issueCode(NUMBER, Duration.ofHours(2));
        portingService.submit(defaultCommand("act-1", NUMBER, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, code1));

        // 第二笔（不同申请号、新授权码）必须被拒绝
        String code2 = issueCode(NUMBER, Duration.ofHours(2));
        assertThatThrownBy(() -> portingService.submit(
                defaultCommand("act-2", NUMBER, Carrier.CHINA_MOBILE, Carrier.CHINA_TELECOM, code2)))
                .isInstanceOf(ActiveApplicationExistsException.class);

        // 被拒的申请未落库，第二枚授权码仍 ISSUED（未被消费）
        assertThatThrownBy(() -> portingService.getApplication("act-2"))
                .isInstanceOf(com.chris64233.numberporting.exception.ApplicationNotFoundException.class);
        assertThat(authCodeRepository.findByCode(code2).orElseThrow().getStatus())
                .isEqualTo(AuthCodeStatus.ISSUED);
    }

    @Test
    void afterCancellationANewApplicationIsAllowedAndSlotReleased() {
        String code1 = issueCode(NUMBER, Duration.ofHours(2));
        portingService.submit(defaultCommand("act-3", NUMBER, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, code1));
        portingService.cancel("act-3");

        String code2 = issueCode(NUMBER, Duration.ofHours(2));
        ApplicationView second = portingService.submit(
                defaultCommand("act-4", NUMBER, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, code2));
        assertThat(second.status()).isEqualTo(PortingStatus.PENDING_REVIEW);
    }

    @Test
    void authorizationCodeCanBeConsumedOnlyOnce() {
        String code = issueCode(NUMBER, Duration.ofHours(2));
        portingService.submit(defaultCommand("once-1", NUMBER, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, code));
        portingService.cancel("once-1");

        // 同一授权码不能被第二笔申请再次使用，即使前一笔已取消
        assertThatThrownBy(() -> portingService.submit(
                defaultCommand("once-2", NUMBER, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, code)))
                .isInstanceOf(AuthCodeAlreadyUsedException.class);
        assertThat(authCodeRepository.findByCode(code).orElseThrow().getConsumedByApplicationId())
                .isEqualTo("once-1");
    }

    @Test
    void expiredAuthorizationCodeCannotBeUsed() {
        String code = issueCode(NUMBER, Duration.ofMinutes(30));
        clock.advance(Duration.ofMinutes(31));

        assertThatThrownBy(() -> portingService.submit(
                defaultCommand("exp-1", NUMBER, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, code)))
                .isInstanceOf(AuthCodeExpiredException.class);
        assertThat(authCodeRepository.findByCode(code).orElseThrow().getStatus())
                .isEqualTo(AuthCodeStatus.EXPIRED);
    }

    @Test
    void authorizationCodeIsBoundToNumberAndDonorCarrier() {
        String otherNumber = "13800000003";
        provision(otherNumber, Carrier.CHINA_TELECOM);
        String codeOfOther = issueCode(otherNumber, Duration.ofHours(2));

        assertThatThrownBy(() -> portingService.submit(
                defaultCommand("bind-1", NUMBER, Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, codeOfOther)))
                .isInstanceOf(AuthCodeMismatchException.class);
    }

    @Test
    void donorCarrierMustMatchCurrentOwnership() {
        String code = issueCode(NUMBER, Duration.ofHours(2));
        var cmd = command("donor-1", NUMBER, Carrier.CHINA_TELECOM, Carrier.CHINA_UNICOM, code,
                com.chris64233.numberporting.testsupport.TestClockConfiguration.BASE.minus(Duration.ofHours(1)),
                com.chris64233.numberporting.testsupport.TestClockConfiguration.BASE.plus(Duration.ofHours(2)));
        assertThatThrownBy(() -> portingService.submit(cmd))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
        // 授权码未被消费
        assertThat(authCodeRepository.findByCode(code).orElseThrow().getStatus())
                .isEqualTo(AuthCodeStatus.ISSUED);
    }
}
