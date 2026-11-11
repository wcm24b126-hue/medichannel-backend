package com.medichannel.dto.consultation;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConsultationSummaryRequest(

        @Size(max = 2000, message = "Symptoms must not exceed 2000 characters.")
        String symptoms,

        @NotBlank(message = "Diagnosis is required.")
        @Size(max = 2000, message = "Diagnosis must not exceed 2000 characters.")
        String diagnosis,

        @Size(max = 4000, message = "Treatment notes must not exceed 4000 characters.")
        String treatmentNotes,

        @Size(max = 4000, message = "Prescription must not exceed 4000 characters.")
        String prescription,

        @Size(max = 2000, message = "Follow-up instructions must not exceed 2000 characters.")
        String followUpInstructions,

        LocalDate followUpDate

) {
}
