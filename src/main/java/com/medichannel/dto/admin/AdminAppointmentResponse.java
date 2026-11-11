package com.medichannel.dto.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import com.medichannel.enums.AppointmentStatus;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.PaymentMethod;
import com.medichannel.enums.PaymentStatus;

public record AdminAppointmentResponse(

        UUID appointmentId,
        UUID bookingReference,
        Integer appointmentNumber,

        UUID sessionId,
        UUID doctorId,
        String doctorName,
        UUID patientId,
        String patientName,

        LocalDate sessionDate,
        LocalTime startTime,
        ConsultationType consultationType,

        AppointmentStatus status,
        PaymentStatus paymentStatus,
        PaymentMethod paymentMethod,

        BigDecimal feeLkr,

        Instant bookedAt,
        Instant arrivedAt,
        Instant consultationStartedAt,
        Instant consultationCompletedAt,
        Instant cancelledAt

) {
}
