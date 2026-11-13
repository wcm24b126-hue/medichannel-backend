package com.medichannel.dto.doctor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

import com.medichannel.enums.AppointmentStatus;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.PaymentMethod;
import com.medichannel.enums.PaymentStatus;

public record DoctorAppointmentResponse(

        UUID appointmentId,
        UUID bookingReference,
        Integer appointmentNumber,

        UUID sessionId,
        UUID patientId,
        String patientName,

        LocalDate sessionDate,
        LocalTime startTime,
        LocalDateTime estimatedConsultationTime,

        ConsultationType consultationType,
        String roomNumber,

        AppointmentStatus status,
        PaymentStatus paymentStatus,
        PaymentMethod paymentMethod,

        BigDecimal consultationFeeLkr,

        Instant bookedAt,
        Instant arrivedAt,
        Instant consultationStartedAt,
        Instant consultationCompletedAt,
        Instant cancelledAt,
        String acceptanceStatus,
        Instant doctorAcceptedAt

) {
}
