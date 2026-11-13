package com.medichannel.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Manager dashboard and reports. Every figure is computed by the backend
 * with SQL aggregate queries over the requested date range.
 */
public record ReportOverviewResponse(

        LocalDate from,
        LocalDate to,

        List<TodaySession> todaySessions,
        List<DailyAppointmentCount> appointmentsPerDay,

        long physicalAppointments,
        long onlineAppointments,
        long totalAppointments,
        long noShows,
        long cancelledAppointments,
        double noShowRate,

        BigDecimal revenueLkr,
        long completedPayments,

        List<DoctorPerformance> topDoctors,
        List<DoctorRating> doctorRatings

) {

    public record TodaySession(
            UUID sessionId,
            String doctorName,
            String specialisation,
            String roomNumber,
            LocalTime startTime,
            LocalTime endTime,
            String consultationType,
            String status,
            int maxAppointments,
            long booked,
            long completed
    ) {
    }

    public record DailyAppointmentCount(
            LocalDate date,
            long appointments
    ) {
    }

    public record DoctorPerformance(
            UUID doctorId,
            String doctorName,
            long appointments,
            BigDecimal revenueLkr
    ) {
    }

    public record DoctorRating(
            UUID doctorId,
            String doctorName,
            double averageRating,
            long reviewCount
    ) {
    }
}
