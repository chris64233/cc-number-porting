package com.chris64233.numberporting.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.numberporting.domain.PortingApplication;
import com.chris64233.numberporting.domain.PortingStatus;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface PortingApplicationRepository extends JpaRepository<PortingApplication, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PortingApplication a where a.applicationId = :applicationId")
    Optional<PortingApplication> findByIdForUpdate(@Param("applicationId") String applicationId);

    /** 仅投影号码字符串：先确定要锁的号码行，避免把申请实体以旧版本读入会话。 */
    @Query("select a.phoneNumber.number from PortingApplication a where a.applicationId = :applicationId")
    Optional<String> findNumberById(@Param("applicationId") String applicationId);

    /** 号码当前活动申请（active_slot 非空，部分唯一索引保证最多一条），加行锁。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PortingApplication a where a.phoneNumber.number = :number and a.activeSlot is not null")
    Optional<PortingApplication> findActiveByNumberForUpdate(@Param("number") String number);

    Optional<PortingApplication> findByPhoneNumber_NumberAndActiveSlotNotNull(String number);

    @Query("select a from PortingApplication a where a.status = :status and a.rollbackDeadline <= :now")
    List<PortingApplication> findSwitchedPastDeadline(@Param("status") PortingStatus status,
                                                      @Param("now") Instant now);

    /** 仅当申请为 SWITCHED 时返回回退截止时间（标量投影，不加载实体）。 */
    @Query("""
            select a.rollbackDeadline from PortingApplication a
            where a.applicationId = :applicationId and a.status = :status
            """)
    Optional<Instant> findRollbackDeadline(@Param("applicationId") String applicationId,
                                           @Param("status") PortingStatus status);

    /** 已过期望切换窗口仍未切换的申请候选（非加锁扫描，逐笔在独立事务处理）。 */
    @Query("""
            select a.applicationId from PortingApplication a
            where a.status in :statuses and a.switchWindow.end <= :now
            """)
    List<String> findApplicationIdsPastWindow(@Param("statuses") Collection<PortingStatus> statuses,
                                              @Param("now") Instant now);
}
