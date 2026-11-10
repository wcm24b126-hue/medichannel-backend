package com.medichannel.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.medichannel.entity.Patient;

public interface PatientRepository extends JpaRepository<Patient, UUID> {

    Optional<Patient> findByUserId(UUID userId);

    Optional<Patient> findByNicLookupHash(String nicLookupHash);

    boolean existsByNicLookupHash(String nicLookupHash);
}
