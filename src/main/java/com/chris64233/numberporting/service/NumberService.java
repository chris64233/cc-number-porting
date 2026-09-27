package com.chris64233.numberporting.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.numberporting.domain.Carrier;
import com.chris64233.numberporting.domain.PhoneNumber;
import com.chris64233.numberporting.domain.ServiceRelationship;
import com.chris64233.numberporting.exception.BusinessRuleException;
import com.chris64233.numberporting.exception.ErrorCode;
import com.chris64233.numberporting.repository.PhoneNumberRepository;
import com.chris64233.numberporting.repository.ServiceRelationshipRepository;

/**
 * 号码入网预置与归属查询。预置时在同一事务建立号码与初始 ACTIVE 服务关系，
 * 保证新号码一落库即“有且仅有一个归属”。
 */
@Service
public class NumberService {

    private final PhoneNumberRepository phoneNumberRepository;
    private final ServiceRelationshipRepository relationshipRepository;
    private final Clock clock;

    public NumberService(PhoneNumberRepository phoneNumberRepository,
                         ServiceRelationshipRepository relationshipRepository,
                         Clock clock) {
        this.phoneNumberRepository = phoneNumberRepository;
        this.relationshipRepository = relationshipRepository;
        this.clock = clock;
    }

    @Transactional
    public PhoneNumber provision(String number, Carrier carrier) {
        if (phoneNumberRepository.existsById(number)) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_ERROR, "number already provisioned: " + number);
        }
        Instant now = Instant.now(clock);
        PhoneNumber phoneNumber = phoneNumberRepository.save(new PhoneNumber(number, carrier, now));
        relationshipRepository.save(new ServiceRelationship(phoneNumber, carrier, now));
        return phoneNumber;
    }

    @Transactional(readOnly = true)
    public PhoneNumber requireNumber(String number) {
        return phoneNumberRepository.findById(number)
                .orElseThrow(() -> new com.chris64233.numberporting.exception.NumberNotFoundException(number));
    }
}
