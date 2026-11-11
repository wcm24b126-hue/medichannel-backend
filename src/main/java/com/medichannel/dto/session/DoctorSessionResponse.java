package com.medichannel.dto.session;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.SessionStatus;

public record DoctorSessionResponse(

        UUID sessionId,

        UUID doctorId,
        String doctorName,
        String specialisation,

        UUID roomId,
        String roomNumber,

        LocalDate sessionDate,
        LocalTime startTime,
        LocalTime endTime,

        ConsultationType consultationType,

        int maxAppointments,
        int remainingAppointments,
        int minutesPerPatient,

        BigDecimal feeLkr,
        int delayMinutes,

        SessionStatus status,

        Instant createdAt,
        Instant updatedAt

) {
}
