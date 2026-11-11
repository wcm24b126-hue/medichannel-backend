package com.medichannel.dto.doctor;

import java.util.UUID;

public record DoctorCreatedResponse(

        String message,
        UUID doctorId,
        UUID userId,
        String email,
        String accountStatus,
        String registrationNumber

) {
}
