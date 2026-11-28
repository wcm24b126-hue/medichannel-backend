package com.medichannel.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.medichannel.entity.Appointment;
import com.medichannel.enums.AppointmentStatus;
import com.medichannel.enums.PaymentStatus;

@Repository
public interface AppointmentRepository
        extends JpaRepository<Appointment, UUID> {

    // Queue ordering is always the database allocated number.
    List<Appointment> findBySession_IdOrderByAppointmentNumberAsc(
            UUID sessionId
    );

    // Doctor queue view excludes rows the assigned doctor has cleared.
    List<Appointment>
            findBySession_IdAndDoctorHiddenAtIsNullOrderByAppointmentNumberAsc(
                    UUID sessionId
            );

    long countBySession_Id(UUID sessionId);

    /**
     * Reads the counter maintained by the allocation trigger. Using the
     * database value avoids a stale in-memory session entity after a
     * booking in the same transaction.
     */
    @Query(value = """
            SELECT last_appointment_number
            FROM doctor_sessions
            WHERE id = :sessionId
            """, nativeQuery = true)
    Integer findLastAppointmentNumberBySessionId(
            @Param("sessionId") UUID sessionId
    );

    // Only these statuses occupy a place in the live queue.
    long countBySession_IdAndStatusIn(
            UUID sessionId,
            Collection<AppointmentStatus> statuses
    );

    /**
     * Reads the allocation counters for many sessions at once. Native on
     * purpose: the booking trigger updates the column after the entity may
     * already be in the persistence context.
     */
    @Query(value = """
            SELECT id, last_appointment_number
            FROM doctor_sessions
            WHERE id IN (:sessionIds)
            """, nativeQuery = true)
    List<Object[]> findLastAppointmentNumbersBySessionIds(
            @Param("sessionIds") Collection<UUID> sessionIds
    );

    /**
     * Batched active counts for a whole page of sessions. A public
     * catalogue request must never issue one count query per session.
     */
    @Query("""
            SELECT a.session.id AS sessionId, COUNT(a) AS activeCount
            FROM Appointment a
            WHERE a.session.id IN :sessionIds
              AND a.status IN :statuses
            GROUP BY a.session.id
            """)
    List<SessionActiveCount> countActiveBySessionIds(
            @Param("sessionIds") Collection<UUID> sessionIds,
            @Param("statuses") Collection<AppointmentStatus> statuses
    );

    /**
     * First appointment number in consultation for each session, batched
     * for the public directory queue summary.
     */
    @Query("""
            SELECT a.session.id AS sessionId,
                   MIN(a.appointmentNumber) AS firstNumber
            FROM Appointment a
            WHERE a.session.id IN :sessionIds
              AND a.status = :status
            GROUP BY a.session.id
            """)
    List<SessionFirstNumber> findFirstNumbersBySessionIds(
            @Param("sessionIds") Collection<UUID> sessionIds,
            @Param("status") AppointmentStatus status
    );

    interface SessionActiveCount {

        UUID getSessionId();

        long getActiveCount();
    }

    interface SessionFirstNumber {

        UUID getSessionId();

        Integer getFirstNumber();
    }

    List<Appointment>
            findBySession_IdAndAppointmentNumberGreaterThanEqualAndStatusInOrderByAppointmentNumberAsc(
                    UUID sessionId,
                    Integer appointmentNumber,
                    Collection<AppointmentStatus> statuses
            );

    @Query("""
            SELECT COUNT(a) FROM Appointment a
            WHERE a.session.id = :sessionId
              AND a.status IN :statuses
              AND a.appointmentNumber <= :appointmentNumber
            """)
    long countActiveUpToNumber(
            @Param("sessionId") UUID sessionId,
            @Param("appointmentNumber") Integer appointmentNumber,
            @Param("statuses") Collection<AppointmentStatus> statuses
    );

    /**
     * The third active appointment strictly after the number currently
     * being served. Gaps left by cancelled appointments are ignored by
     * ordering on the database allocated number.
     */
    @Query(value = """
            SELECT a.*
            FROM appointments a
            WHERE a.session_id = :sessionId
              AND a.status IN ('BOOKED', 'CHECKED_IN', 'ARRIVED', 'IN_CONSULTATION')
              AND a.appointment_number > :afterNumber
            ORDER BY a.appointment_number ASC
            LIMIT 1 OFFSET 2
            """, nativeQuery = true)
    Optional<Appointment> findThirdActiveAppointmentAfterNumber(
            @Param("sessionId") UUID sessionId,
            @Param("afterNumber") Integer afterNumber
    );

    Optional<Appointment>
            findFirstBySession_IdAndStatusOrderByAppointmentNumberAsc(
                    UUID sessionId,
                    AppointmentStatus status
            );

    Optional<Appointment>
            findFirstBySession_IdAndStatusAndPaymentStatusOrderByAppointmentNumberAsc(
                    UUID sessionId,
                    AppointmentStatus status,
                    PaymentStatus paymentStatus
            );

    // Retrieve all appointments belonging to an authenticated patient.
    List<Appointment> findAllByPatient_User_IdOrderByCreatedAtDesc(
            UUID userId
    );

    // Patient history view excludes rows the patient has cleared explicitly.
    List<Appointment>
            findAllByPatient_User_IdAndPatientHiddenAtIsNullOrderByCreatedAtDesc(
                    UUID userId
            );

    // Active/actionable patient appointments ordered by upcoming session date/time.
    @Query("""
            SELECT a
            FROM Appointment a
            JOIN a.session s
            WHERE a.patient.user.id = :userId
              AND a.patientHiddenAt IS NULL
              AND a.status IN :statuses
            ORDER BY s.sessionDate ASC, s.startTime ASC, a.id ASC
            """)
    List<Appointment> findActiveByPatientUserId(
            @Param("userId") UUID userId,
            @Param("statuses") Collection<AppointmentStatus> statuses
    );

    // Paginated closed patient history ordered by most recent session date/time.
    @Query(
            value = """
                    SELECT a
                    FROM Appointment a
                    JOIN a.session s
                    WHERE a.patient.user.id = :userId
                      AND a.patientHiddenAt IS NULL
                      AND a.status IN :statuses
                    ORDER BY s.sessionDate DESC, s.startTime DESC, a.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(a)
                    FROM Appointment a
                    WHERE a.patient.user.id = :userId
                      AND a.patientHiddenAt IS NULL
                      AND a.status IN :statuses
                    """
    )
    Page<Appointment> findHistoryByPatientUserId(
            @Param("userId") UUID userId,
            @Param("statuses") Collection<AppointmentStatus> statuses,
            Pageable pageable
    );

    // Paginated closed doctor appointment history ordered by most recent session date/time.
    @Query(
            value = """
                    SELECT a
                    FROM Appointment a
                    JOIN a.session s
                    WHERE s.doctor.user.id = :userId
                      AND a.doctorHiddenAt IS NULL
                      AND a.status IN :statuses
                      AND (:status IS NULL OR a.status = :status)
                      AND (:sessionDate IS NULL OR s.sessionDate = :sessionDate)
                    ORDER BY s.sessionDate DESC, s.startTime DESC, a.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(a)
                    FROM Appointment a
                    JOIN a.session s
                    WHERE s.doctor.user.id = :userId
                      AND a.doctorHiddenAt IS NULL
                      AND a.status IN :statuses
                      AND (:status IS NULL OR a.status = :status)
                      AND (:sessionDate IS NULL OR s.sessionDate = :sessionDate)
                    """
    )
    Page<Appointment> findHistoryByDoctorUserId(
            @Param("userId") UUID userId,
            @Param("statuses") Collection<AppointmentStatus> statuses,
            @Param("status") AppointmentStatus status,
            @Param("sessionDate") LocalDate sessionDate,
            Pageable pageable
    );

    List<Appointment> findAllBySession_Doctor_User_IdAndStatusInAndDoctorHiddenAtIsNullOrderBySession_SessionDateAscSession_StartTimeAscAppointmentNumberAsc(
            UUID userId,
            Collection<AppointmentStatus> statuses
    );

    // Retrieve one appointment only if it belongs to the given patient.
    Optional<Appointment> findByIdAndPatient_User_Id(
            UUID appointmentId,
            UUID userId
    );

    // Check whether the authenticated patient owns this appointment.
    boolean existsByIdAndPatient_User_Id(
            UUID appointmentId,
            UUID userId
    );

    boolean existsByIdAndPatient_User_IdAndStatus(
            UUID appointmentId,
            UUID userId,
            AppointmentStatus status
    );

    // Check whether the authenticated doctor is assigned to this appointment.
    boolean existsByIdAndSession_Doctor_User_Id(
            UUID appointmentId,
            UUID userId
    );

    boolean existsByIdAndSession_Doctor_User_IdAndStatus(
            UUID appointmentId,
            UUID userId,
            AppointmentStatus status
    );

    // Check whether any appointments exist for the specified doctor.
    boolean existsBySession_Doctor_Id(UUID doctorId);

    // Check whether a patient already has an active appointment
    // in the specified session.
    boolean existsBySession_IdAndPatient_IdAndStatusIn(
            UUID sessionId,
            UUID patientId,
            Collection<AppointmentStatus> statuses
    );

    // Doctor portal.
    Optional<Appointment> findByIdAndSession_Doctor_User_Id(
            UUID appointmentId,
            UUID userId
    );

    List<Appointment>
            findAllBySession_Doctor_User_IdOrderBySession_SessionDateDescSession_StartTimeAsc(
                    UUID userId
            );

    List<Appointment>
            findAllBySession_Doctor_User_IdAndDoctorHiddenAtIsNullOrderBySession_SessionDateDescSession_StartTimeAsc(
                    UUID userId
            );

    List<Appointment>
            findAllBySession_Doctor_User_IdAndSession_SessionDateOrderBySession_StartTimeAsc(
                    UUID userId,
                    LocalDate sessionDate
            );

    List<Appointment>
            findAllBySession_Doctor_User_IdAndSession_SessionDateAndDoctorHiddenAtIsNullOrderBySession_StartTimeAsc(
                    UUID userId,
                    LocalDate sessionDate
            );

    List<Appointment>
            findAllBySession_Doctor_User_IdAndStatusOrderBySession_SessionDateDescSession_StartTimeAsc(
                    UUID userId,
                    AppointmentStatus status
            );

    List<Appointment>
            findAllBySession_Doctor_User_IdAndStatusAndDoctorHiddenAtIsNullOrderBySession_SessionDateDescSession_StartTimeAsc(
                    UUID userId,
                    AppointmentStatus status
            );

    List<Appointment>
            findAllBySession_Doctor_User_IdAndSession_SessionDateAndStatusOrderBySession_StartTimeAsc(
                    UUID userId,
                    LocalDate sessionDate,
                    AppointmentStatus status
            );

    List<Appointment>
            findAllBySession_Doctor_User_IdAndSession_SessionDateAndStatusAndDoctorHiddenAtIsNullOrderBySession_StartTimeAsc(
                    UUID userId,
                    LocalDate sessionDate,
                    AppointmentStatus status
            );

    // Administrative oversight with all display relationships loaded.
    @Query("""
            SELECT a
            FROM Appointment a
            JOIN FETCH a.session s
            JOIN FETCH s.doctor d
            JOIN FETCH d.user du
            JOIN FETCH d.specialisation sp
            JOIN FETCH a.patient p
            JOIN FETCH p.user pu
            LEFT JOIN FETCH s.room r
            WHERE (:sessionId IS NULL OR s.id = :sessionId)
              AND (:doctorId IS NULL OR d.id = :doctorId)
              AND (:status IS NULL OR a.status = :status)
              AND (:sessionDate IS NULL OR s.sessionDate = :sessionDate)
            ORDER BY a.createdAt DESC
            """)
    List<Appointment> findForOversight(
            @Param("sessionId") UUID sessionId,
            @Param("doctorId") UUID doctorId,
            @Param("status") AppointmentStatus status,
            @Param("sessionDate") LocalDate sessionDate
    );

    long countByStatus(AppointmentStatus status);
}
