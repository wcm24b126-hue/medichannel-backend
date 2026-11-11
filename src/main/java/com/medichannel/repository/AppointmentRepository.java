package com.medichannel.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.medichannel.entity.Appointment;
import com.medichannel.enums.AppointmentStatus;

@Repository
public interface AppointmentRepository
        extends JpaRepository<Appointment, UUID> {

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
}
