package com.chris64233.numberporting.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.chris64233.numberporting.domain.PortEvent;

public interface PortEventRepository extends JpaRepository<PortEvent, Long> {

    /** id 递增即插入顺序，天然构成该号码的事件时间线。 */
    List<PortEvent> findByPhoneNumberOrderByIdAsc(String phoneNumber);

    List<PortEvent> findByOrderIdOrderByIdAsc(Long orderId);
}
