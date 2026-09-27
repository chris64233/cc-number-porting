package com.chris64233.numberporting.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.chris64233.numberporting.domain.AuthorizationCode;

public interface AuthorizationCodeRepository extends JpaRepository<AuthorizationCode, Long> {

    Optional<AuthorizationCode> findByCode(String code);

    /** 悲观锁读取授权码，保证并发切换时只有一个事务能消费它。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AuthorizationCode a where a.code = :code")
    Optional<AuthorizationCode> findByCodeForUpdate(String code);
}
