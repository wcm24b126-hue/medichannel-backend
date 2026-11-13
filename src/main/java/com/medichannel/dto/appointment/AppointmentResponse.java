package com.medichannel.dto.appointment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.util.UUID;

import com.medichannel.enums.AppointmentStatus;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.PaymentMethod;
import com.medichannel.enums.PaymentStatus;

public record AppointmentResponse(

        UUID appointmentId,
        UUID bookingReference,
        UUID sessionId,
        Integer appointmentNumber,

        LocalDate sessionDate,
        LocalTime sessionStartTime,
        LocalDateTime estimatedConsultationTime,

        AppointmentStatus status,
        PaymentStatus paymentStatus,

        BigDecimal consultationFeeLkr,
        Instant bookedAt,

        // Additional display information. Existing fields above are
        // unchanged for frontend compatibility.
        UUID doctorId,
        String doctorName,
        String specialisation,
        ConsultationType consultationType,
        String roomNumber,
        PaymentMethod paymentMethod,
        Instant arrivedAt,
        Instant consultationStartedAt,
        Instant consultationCompletedAt,
        Instant cancelledAt,
        String acceptanceStatus,
        Instant doctorAcceptedAt

) {
}
