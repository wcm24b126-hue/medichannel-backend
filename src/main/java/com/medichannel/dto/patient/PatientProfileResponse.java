package com.medichannel.dto.patient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PatientProfileResponse(
        UUID patientId,
        String firstName,
        String lastName,
        String phone,
        LocalDate dateOfBirth,
        String gender,
        Instant createdAt,
        Instant updatedAt
) {
}
