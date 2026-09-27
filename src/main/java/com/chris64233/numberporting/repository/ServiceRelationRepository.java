package com.chris64233.numberporting.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.chris64233.numberporting.domain.ServiceRelation;
import com.chris64233.numberporting.domain.ServiceStatus;

public interface ServiceRelationRepository extends JpaRepository<ServiceRelation, Long> {

    Optional<ServiceRelation> findFirstByPhoneNumberAndStatusOrderByIdDesc(String phoneNumber,
                                                                            ServiceStatus status);

    Optional<ServiceRelation> findFirstByPhoneNumberAndStatusAndCarrier_CodeOrderByIdDesc(
            String phoneNumber, ServiceStatus status, String carrierCode);

    List<ServiceRelation> findByPhoneNumberOrderByIdAsc(String phoneNumber);

    long countByPhoneNumberAndStatus(String phoneNumber, ServiceStatus status);
}
