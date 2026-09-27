package com.chris64233.numberporting.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.chris64233.numberporting.domain.PhoneNumber;

public interface PhoneNumberRepository extends JpaRepository<PhoneNumber, String> {

    /** 取行级写锁后读取号码行，用于申请/切换/取消/回退等串行化关键路径。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PhoneNumber p where p.number = :number")
    Optional<PhoneNumber> findForUpdate(String number);
}
