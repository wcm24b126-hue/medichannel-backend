package com.medichannel.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.medichannel.entity.ConsultationSummary;

@Repository
public interface ConsultationSummaryRepository
        extends JpaRepository<ConsultationSummary, UUID> {

    Optional<ConsultationSummary> findByAppointment_Id(UUID appointmentId);

    boolean existsByAppointment_Id(UUID appointmentId);
}
