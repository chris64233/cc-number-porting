package com.chris64233.numberporting.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.numberporting.domain.Carrier;
import com.chris64233.numberporting.domain.RelationshipStatus;
import com.chris64233.numberporting.domain.ServiceRelationship;

import jakarta.persistence.LockModeType;

public interface ServiceRelationshipRepository extends JpaRepository<ServiceRelationship, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ServiceRelationship r where r.phoneNumber.number = :number and r.status = :status")
    List<ServiceRelationship> findActiveByNumberForUpdate(@Param("number") String number,
                                                          @Param("status") RelationshipStatus status);

    @Query("select count(r) from ServiceRelationship r where r.phoneNumber.number = :number and r.status = :status")
    long countActiveByNumber(@Param("number") String number,
                             @Param("status") RelationshipStatus status);

    @Query("""
            select r.carrier from ServiceRelationship r
            where r.phoneNumber.number = :number and r.status = :status
            """)
    Optional<Carrier> findActiveCarrier(@Param("number") String number,
                                        @Param("status") RelationshipStatus status);

    /** 切换/回退后审计：号码全部历史按开通时间排列。 */
    @Query("select r from ServiceRelationship r where r.phoneNumber.number = :number order by r.openedAt, r.id")
    List<ServiceRelationship> findHistoryByNumber(@Param("number") String number);
}
