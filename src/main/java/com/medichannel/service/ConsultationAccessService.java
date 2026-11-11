package com.medichannel.service;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import com.medichannel.repository.AppointmentRepository;

@Service
public class ConsultationAccessService {

    private final AppointmentRepository appointmentRepository;

    public ConsultationAccessService(
            AppointmentRepository appointmentRepository
    ) {
        this.appointmentRepository = appointmentRepository;
    }

    public boolean canAccess(
            UUID appointmentId,
            Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        UUID userId;

        try {
            userId = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            return false;
        }

        if (authentication.getAuthorities().stream()
                .anyMatch(authority ->
                        authority.getAuthority().equals("ROLE_CUSTOMER"))) {

            return appointmentRepository.existsByIdAndPatient_User_Id(
                    appointmentId,
                    userId
            );
        }

        if (authentication.getAuthorities().stream()
                .anyMatch(authority ->
                        authority.getAuthority().equals("ROLE_DOCTOR"))) {

            return appointmentRepository.existsByIdAndSession_Doctor_User_Id(
                    appointmentId,
                    userId
            );
        }

        // Other roles do not automatically receive consultation access.
        return false;
    }
}
