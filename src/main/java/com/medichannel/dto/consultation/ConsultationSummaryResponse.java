package com.medichannel.dto.consultation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ConsultationSummaryResponse(

        UUID summaryId,
        UUID appointmentId,
        UUID doctorId,
        String doctorName,
        UUID patientId,
        LocalDate consultationDate,
        String symptoms,
        String diagnosis,
        String treatmentNotes,
        String prescription,
        String followUpInstructions,
        LocalDate followUpDate,
        Instant createdAt,
        Instant updatedAt

) {
}
