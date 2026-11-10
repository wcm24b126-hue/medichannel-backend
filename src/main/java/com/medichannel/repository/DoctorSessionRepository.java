package com.medichannel.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.medichannel.entity.DoctorSession;
import com.medichannel.enums.SessionStatus;

public interface DoctorSessionRepository
        extends JpaRepository<DoctorSession, UUID> {

    List<DoctorSession> findByDoctorIdAndSessionDateAndStatus(
            UUID doctorId,
            LocalDate sessionDate,
            SessionStatus status
    );

    List<DoctorSession> findBySessionDateAndStatus(
            LocalDate sessionDate,
            SessionStatus status
    );

    List<DoctorSession> findByDoctorIdAndStatus(
            UUID doctorId,
            SessionStatus status
    );
}
