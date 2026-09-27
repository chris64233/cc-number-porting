package com.chris64233.numberporting.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.numberporting.domain.AuthCodeStatus;
import com.chris64233.numberporting.domain.AuthorizationCode;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;

public interface AuthorizationCodeRepository extends JpaRepository<AuthorizationCode, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AuthorizationCode c where c.code = :code")
    Optional<AuthorizationCode> findByCodeForUpdate(@Param("code") String code);

    Optional<AuthorizationCode> findByCode(String code);

    /** 标量投影：仅取授权码绑定的号码，避免在号码行锁上阻塞期间持有实体旧版本。 */
    @Query("select c.phoneNumber.number from AuthorizationCode c where c.code = :code")
    Optional<String> findNumberByCode(@Param("code") String code);

    @Query("select c.phoneNumber.number from AuthorizationCode c where c.id = :id")
    Optional<String> findNumberById(@Param("id") Long id);

    /** 非加锁扫描候选 ID，逐笔在独立事务加锁处理。 */
    @Query("select c.id from AuthorizationCode c where c.status = :status and c.expiresAt <= :now")
    List<Long> findExpirableIds(@Param("status") AuthCodeStatus status, @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AuthorizationCode c where c.id = :id")
    Optional<AuthorizationCode> findIdForUpdate(@Param("id") Long id);
}
