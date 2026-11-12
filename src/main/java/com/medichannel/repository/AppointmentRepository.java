package com.medichannel.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    long countBySession_Id(UUID sessionId);

    Optional<Appointment> findBySession_IdAndAppointmentNumber(
            UUID sessionId,
            Integer appointmentNumber
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

    // Check whether the authenticated doctor is assigned to this appointment.
    boolean existsByIdAndSession_Doctor_User_Id(
            UUID appointmentId,
            UUID userId
    );

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
            findAllBySession_Doctor_User_IdAndSession_SessionDateOrderBySession_StartTimeAsc(
                    UUID userId,
                    LocalDate sessionDate
            );

    List<Appointment>
            findAllBySession_Doctor_User_IdAndStatusOrderBySession_SessionDateDescSession_StartTimeAsc(
                    UUID userId,
                    AppointmentStatus status
            );

    List<Appointment>
            findAllBySession_Doctor_User_IdAndSession_SessionDateAndStatusOrderBySession_StartTimeAsc(
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
