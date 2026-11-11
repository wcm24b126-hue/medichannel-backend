package com.medichannel.dto.doctor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DoctorResponse(

        UUID doctorId,
        String firstName,
        String lastName,
        String registrationNumber,
        String qualification,
        String biography,
        String specialisation,
        UUID specialisationId,
        BigDecimal consultationFee,
        boolean available,
        Instant createdAt

) {
}
