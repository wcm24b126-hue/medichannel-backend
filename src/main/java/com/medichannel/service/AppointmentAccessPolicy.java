package com.medichannel.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.medichannel.entity.Appointment;
import com.medichannel.exception.ForbiddenOperationException;

/**
 * Centralised ownership rules for appointment-related resources.
 *
 * Keeping these checks in one place prevents subtly different
 * authorization rules from creeping into individual services.
 */
@Service
public class AppointmentAccessPolicy {

    public boolean isPatientOwner(Appointment appointment, UUID userId) {
        return appointment.getPatient() != null
                && appointment.getPatient().getUser() != null
                && appointment.getPatient().getUser().getId().equals(userId);
    }

    public boolean isAssignedDoctor(Appointment appointment, UUID userId) {
        return appointment.getSession() != null
                && appointment.getSession().getDoctor() != null
                && appointment.getSession().getDoctor().getUser() != null
                && appointment.getSession().getDoctor().getUser().getId()
                        .equals(userId);
    }

    public boolean isStaff(String role) {
        return "ADMIN".equals(role) || "MANAGER".equals(role);
    }

    public void requirePatientOwner(Appointment appointment, UUID userId) {
        if (!isPatientOwner(appointment, userId)) {
            throw new ForbiddenOperationException(
                    "You are not authorized to access this appointment."
            );
        }
    }

    public void requireAssignedDoctor(Appointment appointment, UUID userId) {
        if (!isAssignedDoctor(appointment, userId)) {
            throw new ForbiddenOperationException(
                    "Only the assigned doctor can manage this consultation."
            );
        }
    }

    /**
     * Read access for appointment records. Patients only see their
     * own records, doctors only their own consultations, and staff
     * have operational oversight.
     */
    public void requireReadAccess(
            Appointment appointment,
            UUID userId,
            String role
    ) {
        if (isPatientOwner(appointment, userId)
                || isAssignedDoctor(appointment, userId)
                || isStaff(role)) {
            return;
        }

        throw new ForbiddenOperationException(
                "You are not authorized to access this appointment."
        );
    }

    /**
     * Consultation chat is strictly private between the patient and
     * the assigned doctor. Administrative roles are excluded.
     */
    public void requireChatParticipant(
            Appointment appointment,
            UUID userId
    ) {
        if (isPatientOwner(appointment, userId)
                || isAssignedDoctor(appointment, userId)) {
            return;
        }

        throw new ForbiddenOperationException(
                "Only the patient and the assigned doctor can access this conversation."
        );
    }
}
