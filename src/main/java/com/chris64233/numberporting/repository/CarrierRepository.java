package com.chris64233.numberporting.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.chris64233.numberporting.domain.Carrier;

public interface CarrierRepository extends JpaRepository<Carrier, String> {
}
