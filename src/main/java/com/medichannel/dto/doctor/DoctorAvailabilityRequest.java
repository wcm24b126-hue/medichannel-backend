package com.medichannel.dto.doctor;

import jakarta.validation.constraints.NotNull;

public record DoctorAvailabilityRequest(

        @NotNull
        Boolean available

) {
}
