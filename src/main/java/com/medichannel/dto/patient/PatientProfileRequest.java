package com.medichannel.dto.patient;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

public record PatientProfileRequest(

        @NotBlank
        @Size(max = 100)
        String firstName,

        @NotBlank
        @Size(max = 100)
        String lastName,

        @Size(max = 30)
        String phone,

        @Size(max = 20)
        String nic,

        @Past
        LocalDate dateOfBirth,

        @Size(max = 30)
        String gender,

        @Size(max = 500, message = "Address must not exceed 500 characters.")
        String address
) {
}
