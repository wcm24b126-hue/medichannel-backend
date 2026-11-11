package com.medichannel.dto.appointment;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AppointmentBookingRequest(

        @NotNull(message = "Consultation session is required.")
        UUID sessionId,

        @NotBlank(message = "Consultation reason is required.")
        @Size(max = 2000, message = "Consultation reason must not exceed 2000 characters.")
        String reason

) {
}
