package com.medichannel.dto.admin;

import java.math.BigDecimal;

public record AdminStatsResponse(

        long totalUsers,
        long totalDoctors,
        long totalPatients,

        long totalAppointments,
        long bookedAppointments,
        long completedAppointments,
        long cancelledAppointments,
        long noShowAppointments,

        long completedPayments,
        BigDecimal completedPaymentTotalLkr

) {
}
