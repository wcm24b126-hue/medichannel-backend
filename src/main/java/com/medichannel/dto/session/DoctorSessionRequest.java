package com.medichannel.dto.session;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import com.medichannel.enums.ConsultationType;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record DoctorSessionRequest(

        @NotNull
        UUID doctorId,

        // Required for PHYSICAL sessions; must be null for ONLINE sessions.
        UUID roomId,

        @NotNull
        @FutureOrPresent
        LocalDate sessionDate,

        @NotNull
        LocalTime startTime,

        @NotNull
        LocalTime endTime,

        @NotNull
        ConsultationType consultationType,

        @Min(1)
        @Max(60)
        int maxAppointments,

        @Min(5)
        @Max(30)
        int minutesPerPatient,

        @NotNull
        @DecimalMin("0.00")
        @Digits(integer = 8, fraction = 2)
        BigDecimal feeLkr

) {
}
