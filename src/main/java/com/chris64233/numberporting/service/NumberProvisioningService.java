package com.chris64233.numberporting.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.numberporting.domain.AuthorizationCode;
import com.chris64233.numberporting.domain.Carrier;
import com.chris64233.numberporting.domain.PhoneNumber;
import com.chris64233.numberporting.domain.ServiceRelation;
import com.chris64233.numberporting.repository.AuthorizationCodeRepository;
import com.chris64233.numberporting.repository.CarrierRepository;
import com.chris64233.numberporting.repository.PhoneNumberRepository;
import com.chris64233.numberporting.repository.ServiceRelationRepository;

/** 基础资料服务：运营商维护、号码入网（建立初始归属与服务关系）、授权码签发。 */
@Service
public class NumberProvisioningService {

    private final Clock clock;
    private final CarrierRepository carriers;
    private final PhoneNumberRepository numbers;
    private final ServiceRelationRepository relations;
    private final AuthorizationCodeRepository authCodes;

    public NumberProvisioningService(Clock clock,
                                     CarrierRepository carriers,
                                     PhoneNumberRepository numbers,
                                     ServiceRelationRepository relations,
                                     AuthorizationCodeRepository authCodes) {
        this.clock = clock;
        this.carriers = carriers;
        this.numbers = numbers;
        this.relations = relations;
        this.authCodes = authCodes;
    }

    @Transactional
    public Carrier ensureCarrier(String code, String name) {
        return carriers.findById(code).orElseGet(() -> carriers.save(new Carrier(code, name)));
    }

    /** 号码入网：建立初始归属运营商与初始 ACTIVE 服务关系（同一事务）。 */
    @Transactional
    public PhoneNumber registerNumber(String number, String carrierCode) {
        Carrier carrier = requireCarrier(carrierCode);
        if (numbers.existsById(number)) {
            throw new BusinessRuleException(ErrorCode.NUMBER_ALREADY_REGISTERED, number);
        }
        PhoneNumber phone = numbers.save(new PhoneNumber(number, carrier));
        relations.save(ServiceRelation.initial(number, carrier, Instant.now(clock)));
        return phone;
    }

    /**
     * 由原运营商为号码签发携转授权码。
     *
     * @param ttl 有效期，null 时默认 24 小时
     */
    @Transactional
    public AuthorizationCode issueAuthCode(String code, String number, String carrierCode,
                                           java.time.Duration ttl) {
        if (authCodes.findByCode(code).isPresent()) {
            throw new BusinessRuleException(ErrorCode.AUTH_CODE_ALREADY_ISSUED, code);
        }
        PhoneNumber phone = numbers.findById(number)
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.NUMBER_NOT_FOUND, number));
        Carrier carrier = requireCarrier(carrierCode);
        if (!phone.getCurrentCarrier().getCode().equals(carrierCode)) {
            throw new BusinessRuleException(ErrorCode.AUTH_CODE_INVALID,
                    "运营商 " + carrierCode + " 不是号码 " + number + " 的当前归属运营商");
        }
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(ttl != null ? ttl : java.time.Duration.ofHours(24))
                .truncatedTo(ChronoUnit.MILLIS);
        return authCodes.save(new AuthorizationCode(code, number, carrier, now, expiresAt));
    }

    /**
     * 为号码签发授权码，签发运营商取号码当前归属运营商。
     *
     * @param ttl 有效期，null 时默认 24 小时
     */
    @Transactional
    public AuthorizationCode issueAuthCode(String code, String number, java.time.Duration ttl) {
        return issueAuthCode(code, number, currentCarrierCode(number), ttl);
    }

    /**
     * 为号码签发授权码，签发运营商取号码当前归属运营商。
     *
     * @param expiresAt 明确的到期时间，必须晚于当前时间
     */
    @Transactional
    public AuthorizationCode issueAuthCode(String code, String number, Instant expiresAt) {
        if (expiresAt == null) {
            return issueAuthCode(code, number, currentCarrierCode(number), null);
        }
        PhoneNumber phone = numbers.findById(number)
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.NUMBER_NOT_FOUND, number));
        Instant now = Instant.now(clock);
        if (!expiresAt.isAfter(now)) {
            throw new BusinessRuleException(ErrorCode.INVALID_SWITCH_WINDOW,
                    "授权码到期时间必须晚于当前时间");
        }
        return issueAuthCode(code, number, phone.getCurrentCarrier().getCode(),
                java.time.Duration.between(now, expiresAt));
    }

    private String currentCarrierCode(String number) {
        return numbers.findById(number)
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.NUMBER_NOT_FOUND, number))
                .getCurrentCarrier().getCode();
    }

    private Carrier requireCarrier(String code) {
        return carriers.findById(code)
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.CARRIER_NOT_FOUND, code));
    }
}
