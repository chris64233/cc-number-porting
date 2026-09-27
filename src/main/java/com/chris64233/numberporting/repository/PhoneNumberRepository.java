package com.chris64233.numberporting.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.numberporting.domain.PhoneNumber;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface PhoneNumberRepository extends JpaRepository<PhoneNumber, String> {

    /** 悲观行锁：切换 / 回退 / 提交互斥的第一道闸门。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PhoneNumber p where p.number = :number")
    Optional<PhoneNumber> findByIdForUpdate(@Param("number") String number);
}
