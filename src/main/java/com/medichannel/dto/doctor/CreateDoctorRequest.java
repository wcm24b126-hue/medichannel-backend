package com.medichannel.dto.doctor;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateDoctorRequest(

        @NotBlank
        @Email
        @Size(max = 254)
        String email,

        @NotBlank
        @Size(min = 12, max = 72)
        String initialPassword,

        @NotBlank
        @Size(min = 12, max = 72)
        String confirmPassword,

        @NotBlank
        @Size(max = 100)
        String firstName,

        @NotBlank
        @Size(max = 100)
        String lastName,

        @NotBlank
        @Size(max = 100)
        String registrationNumber,

        @Size(max = 255)
        String qualification,

        @NotNull
        UUID specialisationId,

        @DecimalMin(value = "0.00")
        BigDecimal consultationFee

) {
}
