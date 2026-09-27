package com.chris64233.numberporting.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.chris64233.numberporting.domain.PortingEvent;

public interface PortingEventRepository extends JpaRepository<PortingEvent, Long> {

    /** 时间线：按发生时间排序，同一时刻按追加序号兜底。 */
    List<PortingEvent> findByNumberOrderByOccurredAtAscIdAsc(String number);

    List<PortingEvent> findByApplicationIdOrderByOccurredAtAscIdAsc(String applicationId);
}
