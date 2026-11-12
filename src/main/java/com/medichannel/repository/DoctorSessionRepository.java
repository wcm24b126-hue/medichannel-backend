package com.medichannel.repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.medichannel.entity.DoctorSession;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.SessionStatus;

public interface DoctorSessionRepository
        extends JpaRepository<DoctorSession, UUID> {

    List<DoctorSession> findByDoctorIdAndSessionDateAndStatus(
            UUID doctorId,
            LocalDate date,
            SessionStatus status
    );

    List<DoctorSession> findByDoctor_IdAndSessionDate(
            UUID doctorId,
            LocalDate sessionDate
    );

    List<DoctorSession> findBySessionDateAndStatus(
            LocalDate date,
            SessionStatus status
    );

    List<DoctorSession> findByDoctorIdAndStatus(
            UUID doctorId,
            SessionStatus status
    );

    // Doctor portal: all sessions belonging to the authenticated doctor.
    List<DoctorSession>
            findByDoctor_User_IdOrderBySessionDateDescStartTimeDesc(
                    UUID userId
            );

    List<DoctorSession>
            findByDoctor_User_IdAndSessionDateOrderByStartTimeAsc(
                    UUID userId,
                    LocalDate sessionDate
            );

    Optional<DoctorSession> findByIdAndDoctor_User_Id(
            UUID sessionId,
            UUID userId
    );

    // Publicly discoverable sessions must be open, in the future,
    // have remaining capacity, and belong to an eligible doctor.
    @Query("""
            SELECT s
            FROM DoctorSession s
            JOIN FETCH s.doctor d
            JOIN FETCH d.specialisation sp
            JOIN FETCH d.user u
            LEFT JOIN FETCH s.room r
            WHERE s.status = :status
              AND (
                    s.sessionDate > :today
                    OR (
                        s.sessionDate = :today
                        AND s.startTime > :currentTime
                    )
              )
              AND s.lastAppointmentNumber < s.maxAppointments
              AND d.available = true
              AND sp.active = true
              AND u.accountStatus = 'ACTIVE'
              AND u.emailVerified = true
              AND (:doctorId IS NULL OR d.id = :doctorId)
              AND (:specialisationId IS NULL OR sp.id = :specialisationId)
              AND (
                    :sessionDate IS NULL
                    OR CAST(s.sessionDate AS string) = :sessionDate
              )
              AND (
                    :q IS NULL
                    OR LOWER(CONCAT(d.firstName, ' ', d.lastName))
                        LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
              )
              AND (
                    :consultationType IS NULL
                    OR CAST(s.consultationType AS string)
                        = CAST(:consultationType AS string)
              )
            ORDER BY s.sessionDate ASC, s.startTime ASC
            """)
    List<DoctorSession> findPublicOpenSessions(
            @Param("status") SessionStatus status,
            @Param("today") LocalDate today,
            @Param("currentTime") LocalTime currentTime,
            @Param("doctorId") UUID doctorId,
            @Param("specialisationId") UUID specialisationId,
            @Param("sessionDate") String sessionDate,
            @Param("q") String q,
            @Param("consultationType") ConsultationType consultationType
    );
}
