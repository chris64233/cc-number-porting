package com.chris64233.numberporting.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.chris64233.numberporting.domain.PortOrder;
import com.chris64233.numberporting.domain.PortOrderStatus;

public interface PortOrderRepository extends JpaRepository<PortOrder, Long> {

    Optional<PortOrder> findByRequestId(String requestId);

    /** 只取申请对应的号码字符串（不把申请实体加载进持久化上下文）。 */
    @Query("select o.phoneNumber from PortOrder o where o.id = :id")
    Optional<String> findPhoneNumberById(Long id);

    /** 悲观锁读取申请，保证同一申请上的审核/切换/取消/失效串行执行。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PortOrder o where o.id = :id")
    Optional<PortOrder> findByIdForUpdate(Long id);

    List<PortOrder> findByPhoneNumberOrderByCreatedAtAsc(String phoneNumber);

    List<PortOrder> findByAuthCodeAndStatusIn(String authCode, List<PortOrderStatus> statuses);

    /** 悲观锁版本：吊销授权码时锁住引用它的活动申请。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PortOrder o where o.authCode = :authCode and o.status in :statuses")
    List<PortOrder> findByAuthCodeAndStatusInForUpdate(String authCode,
                                                       List<PortOrderStatus> statuses);
}
