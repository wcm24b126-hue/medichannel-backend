package com.medichannel.dto.patient;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PatientProfileRequest(

        @NotBlank
        @Size(max = 100)
        String firstName,

        @NotBlank
        @Size(max = 100)
        String lastName,

        @Pattern(regexp = "^07\\d{8}$", message = "Phone must be in the format 07XXXXXXXX.")
        String phone,

        @Pattern(regexp = "^(?:\\d{9}[VvXx]|\\d{12})$", message = "Enter a valid Sri Lankan NIC number.")
        String nic,

        @Past
        LocalDate dateOfBirth,

        @Size(max = 30)
        String gender,

        @Size(max = 300)
        String address
) {
    public PatientProfileRequest(
            String firstName, String lastName, String phone, String nic,
            LocalDate dateOfBirth, String gender
    ) {
        this(firstName, lastName, phone, nic, dateOfBirth, gender, null);
    }
}
